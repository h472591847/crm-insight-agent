package com.github.yingli.metrics.validate;

import com.github.yingli.metrics.exception.MetricException;
import com.github.yingli.metrics.request.MetricQueryRequest;
import com.github.yingli.metrics.model.MetricDefinition;
import com.github.yingli.metrics.model.QueryPlan;
import com.github.yingli.shared.limits.ExecutionLimits;
import com.github.yingli.shared.limits.QueryLimits;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description 把模型给出的原始请求归一化 + 校验成 {@link QueryPlan}。
 * 原则与 Phase 1 完全一致：不信任模型入参。非法日期、区间倒置、跨度超限、未知编码、
 * topN 越界一律服务端拒绝，并给出可直接展示给用户的中文原因。
 * <p>
 * 默认值也在服务端补齐（缺时间 = 看板默认近一年半，指标可用 defaultWindowMonths 覆盖），不依赖模型填值。
 *
 * @Author crm-agent
 */
public final class MetricQueryValidator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern(ExecutionLimits.DATE_PATTERN);

    private MetricQueryValidator() {
    }

    /**
     * 归一化并校验。
     *
     * @param def     指标定义
     * @param request 模型给出的原始请求
     * @throws MetricException 参数非法
     */
    public static QueryPlan normalize(MetricDefinition def, MetricQueryRequest request) {
        MetricQueryRequest req = request == null ? new MetricQueryRequest() : request;

        List<String> dimensions = normalizeDimensions(def, req.getDimensions());
        Map<String, String> filters = normalizeFilters(def, req.getFilters());
        String[] range = resolveDateRange(def, req.getStartDate(), req.getEndDate());
        int limit = normalizeLimit(def, req.getTopN());
        String sortBy = normalizeSortBy(def, req.getSortBy());
        String sortDir = normalizeSortDir(req.getSortDir());

        return new QueryPlan(def.metric(), dimensions, filters, range[0], range[1], limit, sortBy, sortDir);
    }

    /**
     * 域归属校验：工具与指标必须同域，避免把会员指标挂到订单工具上绕过边界。
     */
    public static void requireDomain(MetricDefinition def, String expectedDomain) {
        if (StringUtils.isNotBlank(expectedDomain) && !expectedDomain.equalsIgnoreCase(def.domain())) {
            throw MetricException.invalidParam("指标 " + def.metric() + " 属于 " + def.domain()
                    + " 域，不能通过 " + expectedDomain + " 域工具查询");
        }
    }

    private static List<String> normalizeDimensions(MetricDefinition def, List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        List<String> allowed = def.dimensions().stream().map(MetricDefinition.Dimension::code).toList();
        List<String> out = new ArrayList<>(requested.size());
        for (String raw : requested) {
            String code = StringUtils.upperCase(StringUtils.trimToEmpty(raw));
            if (code.isEmpty()) {
                continue;
            }
            if (!allowed.contains(code)) {
                throw MetricException.invalidParam("指标 " + def.metric() + " 不支持维度 " + code
                        + "，可用维度=" + allowed);
            }
            if (!out.contains(code)) {
                out.add(code);
            }
        }
        return out;
    }

    private static Map<String, String> normalizeFilters(MetricDefinition def,
                                                        List<com.github.yingli.metrics.request.FilterItem> requested) {
        Map<String, String> out = new LinkedHashMap<>();
        if (requested == null || requested.isEmpty()) {
            return out;
        }
        List<String> allowed = def.optionalFilters().stream().map(MetricDefinition.Filter::code).toList();
        for (com.github.yingli.metrics.request.FilterItem item : requested) {
            if (item == null) {
                continue;
            }
            String code = StringUtils.upperCase(StringUtils.trimToEmpty(item.getCode()));
            String value = StringUtils.trimToNull(item.getValue());
            if (code.isEmpty() || value == null) {
                // 空值过滤按"不过滤"处理，避免模型用空串表达"全部"时误伤结果
                continue;
            }
            if (!allowed.contains(code)) {
                throw MetricException.invalidParam("指标 " + def.metric() + " 不支持过滤条件 " + code
                        + "，可用过滤=" + allowed);
            }
            if (value.length() > 100) {
                throw MetricException.invalidParam("过滤值过长（>" + 100 + " 字符），请检查是否误传：" + code);
            }
            out.put(code, value);
        }
        return out;
    }

    /**
     * 时间范围解析。非时间口径指标（timeColumn 为空）忽略区间，返回 null。
     * 缺省区间：指标声明了 defaultWindowMonths 时按该窗口（如活跃会员＝近 3 个月）；
     * 否则取看板默认近一年半（{@link QueryLimits#DEFAULT_RANGE_MONTHS}，
     * 2026-10-29 产品确认，取代 Phase 1 “当前自然月”旧口径）。
     * <p>
     * 跨度上限按指标取：指标声明了 maxRangeDays 就用它（不得超过全局上限），否则用全局值。
     * 显式区间越界 → 拒绝；缺省窗口越界 → 收敛到上限，避免“缺省即非法”的自相矛盾配置
     * （与 topN 静默夹取同一策略，且实际使用区间会回显在结果 scope 中）。
     */
    private static String[] resolveDateRange(MetricDefinition def, String start, String end) {
        if (!def.isTimeBased()) {
            return new String[]{null, null};
        }
        LocalDate today = LocalDate.now();
        int months = def.defaultWindowMonths() > 0 ? def.defaultWindowMonths() : QueryLimits.DEFAULT_RANGE_MONTHS;
        int capDays = def.maxRangeDays() > 0
                ? Math.min(def.maxRangeDays(), QueryLimits.MAX_RANGE_DAYS)
                : QueryLimits.MAX_RANGE_DAYS;
        LocalDate s;
        LocalDate e;
        try {
            s = StringUtils.isBlank(start) ? today.minusMonths(months) : LocalDate.parse(start.trim(), DATE_FMT);
            e = StringUtils.isBlank(end) ? today : LocalDate.parse(end.trim(), DATE_FMT);
        } catch (DateTimeParseException ex) {
            throw MetricException.invalidParam("日期格式必须为 yyyy-MM-dd");
        }
        if (e.isBefore(s)) {
            throw MetricException.invalidParam("结束日期不能早于开始日期");
        }
        if (StringUtils.isBlank(start) && ChronoUnit.DAYS.between(s, e) > capDays) {
            s = e.minusDays(capDays);
        }
        long days = ChronoUnit.DAYS.between(s, e);
        if (days > capDays) {
            throw MetricException.invalidParam("时间跨度不能超过 " + capDays + " 天"
                    + (capDays < QueryLimits.MAX_RANGE_DAYS ? "（指标 " + def.metric() + " 的窗口上限）" : ""));
        }
        return new String[]{s.format(DATE_FMT), e.format(DATE_FMT)};
    }

    private static int normalizeLimit(MetricDefinition def, Integer topN) {
        int n = (topN == null || topN <= 0) ? def.limitDefault() : topN;
        return Math.min(n, def.limitMax());
    }

    private static String normalizeSortBy(MetricDefinition def, String sortBy) {
        String code = StringUtils.upperCase(StringUtils.trimToNull(sortBy));
        if (code == null) {
            return null;
        }
        if (!def.sortable().contains(code)) {
            throw MetricException.invalidParam("指标 " + def.metric() + " 不允许按 " + code
                    + " 排序，可排序度量=" + def.sortable());
        }
        return code;
    }

    private static String normalizeSortDir(String sortDir) {
        String v = StringUtils.lowerCase(StringUtils.trimToEmpty(sortDir));
        if (v.isEmpty()) {
            return QueryLimits.SORT_DESC;
        }
        if (!QueryLimits.SORT_ASC.equals(v) && !QueryLimits.SORT_DESC.equals(v)) {
            throw MetricException.invalidParam("排序方向只能是 asc 或 desc，实际=" + sortDir);
        }
        return v;
    }
}

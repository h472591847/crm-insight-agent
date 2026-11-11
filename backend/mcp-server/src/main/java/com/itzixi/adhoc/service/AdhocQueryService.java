package com.itzixi.adhoc.service;

import com.itzixi.adhoc.exception.AdhocException;
import com.itzixi.adhoc.compile.AdhocSqlCompiler;
import com.itzixi.adhoc.validate.AdhocRequestValidator;
import com.itzixi.adhoc.model.SemanticRegistry;
import com.itzixi.adhoc.request.AdhocQueryRequest;
import com.itzixi.adhoc.response.AdhocResult;
import com.itzixi.adhoc.request.MeasureSpec;
import com.itzixi.adhoc.request.TimeRangeSpec;
import com.itzixi.shared.calc.DerivedCalculator;
import com.itzixi.shared.sql.GeneratedSql;
import com.itzixi.shared.limits.ExecutionLimits;
import com.itzixi.shared.sql.SqlExecutor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * @Description Ad-hoc 编排层：校验→编译→受限执行→派生补值。职责边界与 MetricQueryService 一致：
 * 模型组参数，服务端裁决一切；失败一律转 errorCode，不存在静默降级。
 * 校验无条件先行（编译器信任已校验输入）；执行层异常收敛为 ADHOC_EXEC_FAILED，
 * 服务端超时（MySQL 3024/1317）单独收敛为 ADHOC_QUERY_TIMEOUT，提示缩小范围而非重复重试；
 * 模型只转述不重试绕过。日志只记 sqlHash/原因等元信息，不记个人级数据。
 * @Author crm-agent
 */
@Service
@RequiredArgsConstructor
public class AdhocQueryService {

    private static final Logger log = LoggerFactory.getLogger(AdhocQueryService.class);

    /** 单次批量请求的块数上限：防止模型把一次提问拆成过多查询打满连接池 */
    public static final int MAX_BATCH_QUERIES = 5;

    private final AdhocRequestValidator validator;
    private final AdhocSqlCompiler compiler;
    private final SqlExecutor sqlExecutor;

    public AdhocResult query(AdhocQueryRequest request) {
        GeneratedSql gen;
        try {
            validator.validate(request); // 无条件先行：编译器信任已校验输入
            gen = compiler.compile(request);
        } catch (AdhocException e) {
            log.warn("====== Ad-hoc 校验/编译失败：{} {} ======", e.code(), e.getMessage());
            return AdhocResult.error(e.code(), e.getMessage());
        }

        List<Map<String, Object>> rows;
        try {
            rows = sqlExecutor.query(gen.sql(), gen.params(), SqlExecutor.SOURCE_ADHOC);
        } catch (Exception e) {
            if (SqlExecutor.isExecutionTimeout(e)) {
                log.warn("====== Ad-hoc 查询超时 sqlHash={} ======", gen.sqlHash());
                return AdhocResult.error("ADHOC_QUERY_TIMEOUT", "查询超过服务端 "
                        + (ExecutionLimits.MAX_EXECUTION_TIME_MS / 1000)
                        + " 秒上限：请缩小时间范围或增加等值过滤条件后重查；不要用相同参数重复查询");
            }
            log.error("====== Ad-hoc 执行失败 sqlHash={} 原因={} ======", gen.sqlHash(), e.getMessage());
            return AdhocResult.error("ADHOC_EXEC_FAILED", "查询执行失败：" + e.getMessage());
        }

        applyDerived(request, gen, rows);
        // 输出列用 SQL 别名（行 Map 的实际 key），派生列别名缺失时回退编码——columns 与 rows 键恒一致
        List<String> columns = gen.columns().stream()
                .map(c -> c.alias() == null || c.alias().isBlank() ? c.code() : c.alias())
                .toList();
        String caliber = caliberOf(request);
        // 空结果是正常答案而非异常：在口径行钉死，模型转述时即带上『0 条』事实，杜绝联想补查
        if (rows.isEmpty()) {
            caliber = caliber + "；本区间无匹配数据（0 条）";
        }
        return new AdhocResult(null, null, columns, rows, gen.sqlHash(), caliber, rows.size());
    }

    /**
     * 多块查询：每块独立校验、编译、执行，结果按请求顺序返回。
     * 单块失败不影响其他块（失败信息落在该块的 errorCode/errorMessage），
     * 块之间不做 join，因此各块可以安全地使用不同度量粒度。
     */
    public List<AdhocResult> queryBatch(List<AdhocQueryRequest> queries) {
        if (queries == null || queries.isEmpty()) {
            return List.of(AdhocResult.error("INVALID_PARAM", "queries 不能为空：至少需要一个查询块"));
        }
        if (queries.size() > MAX_BATCH_QUERIES) {
            return List.of(AdhocResult.error("INVALID_PARAM",
                    "一次最多 " + MAX_BATCH_QUERIES + " 个查询块，实际 " + queries.size() + " 个"));
        }
        List<AdhocResult> out = new ArrayList<>(queries.size());
        for (AdhocQueryRequest q : queries) {
            try {
                out.add(query(q));
            } catch (RuntimeException e) {
                log.error("====== Ad-hoc 批量块执行失败 原因={} ======", e.getMessage());
                out.add(AdhocResult.error("ADHOC_EXEC_FAILED", "查询块执行失败：" + e.getMessage()));
            }
        }
        return out;
    }

    /** 干跑：校验+编译，把 SQL/params/sqlHash 作为单行结果返回（不触达执行层），供 explainAdhoc 使用 */
    public AdhocResult dryRun(AdhocQueryRequest request) {
        validator.validate(request);
        GeneratedSql gen = compiler.compile(request);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("sql", gen.sql());
        view.put("params", gen.params());
        view.put("sqlHash", gen.sqlHash());
        view.put("columns", gen.columns().stream().map(GeneratedSql.ColumnRef::code).toList());
        return new AdhocResult(null, null, List.of("sql", "params", "sqlHash", "columns"),
                List.of(view), gen.sqlHash(), null, 1);
    }

    /** 派生列逐行 Java 计算（四则运算白名单表达式引擎，除零返回 0） */
    private void applyDerived(AdhocQueryRequest request, GeneratedSql gen, List<Map<String, Object>> rows) {
        List<MeasureSpec> derived = orEmpty(request.getMeasures()).stream()
                .filter(m -> "derived".equalsIgnoreCase(m.getType())).toList();
        if (derived.isEmpty()) {
            return;
        }
        for (Map<String, Object> row : rows) {
            Map<String, BigDecimal> values = new LinkedHashMap<>();
            for (GeneratedSql.ColumnRef ref : gen.columns()) {
                if (!ref.derived() && row.get(ref.alias()) instanceof Number n) {
                    values.put(ref.alias(), new BigDecimal(n.toString()));
                }
            }
            for (MeasureSpec d : derived) {
                int scale = d.getScale() == null ? 2 : d.getScale();
                BigDecimal v = DerivedCalculator.evaluate(d.getFormula(), values, scale);
                values.put(d.getAlias(), v);
                row.put(d.getAlias(), v);
            }
        }
    }

    /**
     * 口径行：让转述包含统计范围事实，禁止模型自行补口径。
     * 过滤片段为实际注入内容（主实体 baselineFilters + 各级关系 joinFilters），
     * 与编译器 chain() 同一来源；joinFilters 实际落在 ON，主实体基线落在 WHERE。
     */
    private String caliberOf(AdhocQueryRequest request) {
        StringBuilder sb = new StringBuilder("Ad-hoc 查询｜实体=" + request.getEntities());
        if (request.getDimensions() != null && !request.getDimensions().isEmpty()) {
            sb.append("｜维度=").append(request.getDimensions());
        }
        TimeRangeSpec t = request.getTimeRange();
        if (t != null) {
            String timeEntity = StringUtils.isBlank(t.getEntity())
                    ? request.getEntities().get(0) : t.getEntity();
            sb.append("｜时间=").append(timeEntity).append('.').append(t.getTimeDimension())
                    .append(' ').append(t.getStartDate()).append("~").append(t.getEndDate());
        }
        List<String> fragments = new ArrayList<>();
        // 与编译器同一来源：主实体基线 + 各级关系 joinFilters（编译器提供只读导出，避免服务依赖其包内可见结构）
        fragments.addAll(compiler.injectedFilters(request));
        sb.append("｜口径：自动注入过滤 ").append(fragments)
                .append("；个人信息禁止输出，结果为只读聚合");
        return sb.toString();
    }

    /**
     * 列表空值语义：null 视为空列表。
     * B3 服务移包后不再调用编译器包内静态方法，就地保留以免扩大编译器公开 API。
     */
    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}

package com.github.yingli.adhoc;

import com.github.yingli.adhoc.compile.AdhocSqlCompiler;
import com.github.yingli.adhoc.request.AdhocQueryRequest;
import com.github.yingli.adhoc.response.AdhocResult;
import com.github.yingli.adhoc.service.AdhocQueryService;
import com.github.yingli.adhoc.request.FilterSpec;
import com.github.yingli.adhoc.request.MeasureSpec;
import com.github.yingli.adhoc.request.TimeRangeSpec;
import com.github.yingli.metrics.request.MetricQueryRequest;
import com.github.yingli.metrics.response.MetricResult;
import com.github.yingli.metrics.BaselineFixtures;
import com.github.yingli.metrics.service.MetricQueryService;
import com.github.yingli.shared.sql.SqlExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName AdhocReconciliationIT
 * @Description Ad-hoc 与固化指标的真库交叉对账：同一口径的两条链路（YAML 指标编译 vs 语义元数据
 * Ad-hoc 编译）在 crmtest 真库上必须给出逐值相等的数字。这是"白名单解析与指标编译等价"的证明。
 * <p>
 * 对账对（等价性依据）：
 * <ul>
 *   <li>VIP 总数：指标 VIP_OVERVIEW = WHERE del_flag='0' AND track=1 的 COUNT(*)；
 *       Ad-hoc = member 实体（自动注入 mv.del_flag='0'）上 IS_VIP（track='1'）条件计数——
 *       "过滤后计数" 与 "全量上的条件计数" 同表同列同值域，逐值相等；</li>
 *   <li>VIP 消费/人均：指标 SUM(COALESCE(totalsum,0))（track=1 内 NULL 兜零）vs
 *       Ad-hoc SUM(CASE WHEN track THEN totalsum ELSE 0 END)（条件内 NULL 落 0）——空值处理等价；
 *       人均两侧都由 {@link com.github.yingli.shared.calc.DerivedCalculator} 在 Java 层以 scale=2 计算；</li>
 *   <li>门店分组：指标 VIP_BY_STORE = WHERE del_flag='0' AND track=1 后按 member_allot_store
 *       分组 COUNT(*)；Ad-hoc = STORE_CODE（同列）+ filter preset IS_VIP 对齐同一基线过滤
 *       （同分组宇宙：不注入过滤会让无要客门店以 0 计数出现，真库实测 256 组 vs 指标 192 组）；
 *       两侧 GROUP BY 均用原始列（NULL 与空串各自成组），空值展示标签不同不影响分组数值——
 *       按归一化后的门店→计数映射整体相等断言（空门店组求和归并，非静默放宽）；</li>
 *   <li>性别 join：指标 GENDER_CONSUMPTION_COMPARE = e LEFT JOIN m 取性别分组 SUM(NUM(realamt))
 *       vs Ad-hoc entities=[order,member]（元数据声明同向 LEFT JOIN + 同 joinFilters）
 *       + ORDER_GENDER 维度 + SALES 度量（VARCHAR 自动 NUM 展开，同 golden 口径）；
 *       时间窗口两侧显式传基线末端 7 天（外部库冷缓存下 27 天 join 会顶到 25s 服务端上限），不依赖缺省值。</li>
 * </ul>
 * 只读查询，不写入任何数据；输出仅汇总数字，无行级个人数据。
 * 连到非 crmtest 库时经 {@link BaselineFixtures#assumeMatchesCurrentDb} 自动跳过。
 * @Author crm-agent
 */
@SpringBootTest
class AdhocReconciliationIT {

    /** 与基线一致的固定历史窗口（Phase 2 对账约定：不依赖当前时间，结果可反复复核） */
    private static final String START = BaselineFixtures.windowStart();
    private static final String END = BaselineFixtures.windowEnd();

    /**
     * 基线末端 7 天：跨表 JOIN 的重查询用它。
     * 外部库上同一 SQL 冷/热缓存差 2~3 倍（27 天 order×member 实测热 ~10s、冷 >25s），
     * 会顶到服务端 MAX_EXECUTION_TIME 上限，把环境抖动伪装成口径失败。
     * 两侧同窗口，逐值对账口径不受窗口长度影响。
     */
    private static final String SHORT_START = java.time.LocalDate.parse(END).minusDays(6).toString();

    @Autowired
    private AdhocQueryService adhocQueryService;

    @Autowired
    private MetricQueryService metricQueryService;

    @Autowired
    private AdhocSqlCompiler compiler;

    @Autowired
    private SqlExecutor sqlExecutor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ToolCallbackProvider toolCallbackProvider;

    /** 基线采集自 crmtest；连到其它库时跳过，避免把环境问题误读成口径错误 */
    @BeforeEach
    void requireBaselineDatabase() {
        BaselineFixtures.assumeMatchesCurrentDb(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class));
    }

    @Test
    @DisplayName("保留字 alias 冒烟：lines/interval/groups 加反引号后可在 MySQL 8.0 真库执行")
    void reservedAliasesQuotedAndExecutable() {
        AdhocQueryRequest r = new AdhocQueryRequest();
        r.setEntities(List.of("detail"));
        r.setMeasures(List.of(
                spec("simple", "DETAIL_LINES", null, "lines", null),
                spec("simple", "DETAIL_LINES", null, "interval", null),
                spec("simple", "DETAIL_LINES", null, "groups", null)));
        FilterSpec mobile = new FilterSpec();
        mobile.setEntity("detail");
        mobile.setColumn("expense_d_mobile");
        mobile.setOp("=");
        mobile.setValue("13800000000");
        r.setFilters(List.of(mobile));

        var gen = compiler.compile(r);
        assertThat(gen.sql())
                .contains("AS `lines`")
                .contains("AS `interval`")
                .contains("AS `groups`");
        List<Map<String, Object>> rows = sqlExecutor.query(gen.sql(), gen.params(), SqlExecutor.SOURCE_ADHOC);
        assertThat(rows).hasSize(1);
        // 反引号只影响 SQL 文本，JDBC 返回的 columnLabel 仍是裸 alias
        assertThat(rows.get(0)).containsKeys("lines", "interval", "groups");
    }

    @Test
    @DisplayName("member 手机号直查可执行：member_mobile 等值命中单会员，不补默认时间窗")
    void memberMobileFilterExecutable() {
        AdhocQueryRequest r = new AdhocQueryRequest();
        r.setEntities(List.of("member"));
        r.setMeasures(List.of(spec("simple", "MEMBER_COUNT", null, "cnt", null)));
        r.setFilters(List.of(mobileFilter("member", "member_mobile")));

        var gen = compiler.compile(r);
        assertThat(gen.sql()).contains("mv.member_mobile = ?");
        assertThat(gen.sql()).doesNotContain("member_created >=");
        List<Map<String, Object>> rows = sqlExecutor.query(gen.sql(), gen.params(), SqlExecutor.SOURCE_ADHOC);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("cnt")).isInstanceOf(Number.class);
    }

    @Test
    @DisplayName("member 会员级别真库可查：手机号等值 + MEMBER_LEVEL 维度返回自描述等级")
    void memberLevelByMobile() {
        AdhocQueryRequest r = new AdhocQueryRequest();
        r.setEntities(List.of("member"));
        r.setDimensions(List.of("MEMBER_LEVEL"));
        r.setMeasures(List.of(spec("simple", "MEMBER_COUNT", null, "cnt", null)));
        r.setFilters(List.of(mobileFilter("member", "member_mobile")));

        var gen = compiler.compile(r);
        assertThat(gen.sql()).contains("mv.member_mobile = ?")
                .contains("GROUP BY mv.new_member_level_value");
        List<Map<String, Object>> rows = sqlExecutor.query(gen.sql(), gen.params(), SqlExecutor.SOURCE_ADHOC);
        assertThat(rows).hasSize(1);
        assertThat(String.valueOf(rows.get(0).get("member_level"))).isNotBlank();
    }

    @Test
    @DisplayName("member 新开放维度/度量真库可执行：级别、顾客价值、分配状态、会员池、消费次数")
    void memberNewFieldsQueryable() {
        AdhocQueryRequest r = new AdhocQueryRequest();
        r.setEntities(List.of("member"));
        r.setDimensions(List.of("MEMBER_LEVEL", "CUSTOMER_VALUE",
                "ALLOT_STORE_FLAG", "STAFF_ALLOT_FLAG", "POND_TYPE"));
        r.setMeasures(List.of(spec("simple", "MEMBER_EXPENSE_TIMES", null, "times", null),
                spec("simple", "YEAR_EXPENSE_TIMES", null, "year_times", null)));
        r.setFilters(List.of(mobileFilter("member", "member_mobile")));

        var gen = compiler.compile(r);
        assertThat(gen.sql()).contains("GROUP BY mv.new_member_level_value, mv.customer_cost");
        List<Map<String, Object>> rows = sqlExecutor.query(gen.sql(), gen.params(), SqlExecutor.SOURCE_ADHOC);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("member_level")).isNotNull();
        assertThat(row.get("customer_value")).isNotNull();
        assertThat(row.get("allot_store_flag")).isEqualTo("已分配门店");
        assertThat(row.get("staff_allot_flag")).isIn("已分配店员", "未分配店员");
        assertThat(row.get("pond_type")).isIn("正常", "池中");
        assertThat(row.get("times")).isInstanceOf(Number.class);
        assertThat(row.get("year_times")).isInstanceOf(Number.class);
    }

    @Test
    @DisplayName("member 多套级别口径与要客标签真库可执行：两批覆盖新增维度")
    void memberLevelCandidatesQueryable() {
        AdhocQueryRequest first = new AdhocQueryRequest();
        first.setEntities(List.of("member"));
        first.setDimensions(List.of("HAIDING_LEVEL", "COMPANY_LEVEL", "CITY_LEVEL",
                "STORE_LEVEL", "PERSONAL_LEVEL"));
        first.setMeasures(List.of(spec("simple", "MEMBER_COUNT", null, "cnt", null)));
        first.setFilters(List.of(mobileFilter("member", "member_mobile")));

        var gen1 = compiler.compile(first);
        assertThat(gen1.sql()).contains(
                "GROUP BY mv.reserved15, mv.company_level, mv.city_level, mv.store_level, mv.personal_level");
        List<Map<String, Object>> rows1 = sqlExecutor.query(gen1.sql(), gen1.params(), SqlExecutor.SOURCE_ADHOC);
        assertThat(rows1).hasSize(1);
        Map<String, Object> row1 = rows1.get(0);
        assertThat(row1.get("haiding_level")).isEqualTo("高级会员");
        assertThat(row1.get("company_level")).isEqualTo("FB");
        assertThat(row1.get("city_level")).isEqualTo("C");
        assertThat(row1.get("store_level")).isEqualTo("D");
        // personal_level 真库存在字面量 "null"（非 SQL NULL），IFNULL 不会归并，故两种取值都允许
        assertThat(row1.get("personal_level")).isIn("未填写", "null");

        AdhocQueryRequest second = new AdhocQueryRequest();
        second.setEntities(List.of("member"));
        second.setDimensions(List.of("VIP_FLAG", "HALF_YEAR_SALES_LEVEL",
                "TOTAL_YEAR_SALES_LEVEL", "MEMBER_LEVEL"));
        second.setMeasures(List.of(spec("simple", "MEMBER_COUNT", null, "cnt", null)));
        second.setFilters(List.of(mobileFilter("member", "member_mobile")));

        var gen2 = compiler.compile(second);
        List<Map<String, Object>> rows2 = sqlExecutor.query(gen2.sql(), gen2.params(), SqlExecutor.SOURCE_ADHOC);
        assertThat(rows2).hasSize(1);
        Map<String, Object> row2 = rows2.get(0);
        assertThat(row2.get("vip_flag")).isEqualTo("非要客");
        assertThat(row2.get("half_year_sales_level")).isEqualTo("EC");
        assertThat(row2.get("total_year_sales_level")).isEqualTo("FB");
        assertThat(row2.get("member_level")).isEqualTo("荣享");
    }

    private FilterSpec mobileFilter(String entity, String column) {
        FilterSpec f = new FilterSpec();
        f.setEntity(entity);
        f.setColumn(column);
        f.setOp("=");
        f.setValue("13199288863");
        return f;
    }

    private MeasureSpec spec(String type, String name, String preset, String alias, String formula) {
        MeasureSpec m = new MeasureSpec();
        m.setType(type);
        m.setName(name);
        m.setConditionPreset(preset);
        m.setAlias(alias);
        m.setFormula(formula);
        m.setScale(2);
        return m;
    }

    private BigDecimal bd(Object v) {
        return new BigDecimal(String.valueOf(v));
    }

    /**
     * 门店分组键归一化：NULL/"null"/空串/空值展示标签（未知门店）统一归并为 "#"，
     * 两侧各自的空值展示语义（指标层 nullDimensionLabel 重标 vs Ad-hoc 层 IFNULL）
     * 不影响分组数值的可比性；同键计数求和后整表相等。
     */
    private String storeKey(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty() || "null".equalsIgnoreCase(v) || "未知门店".equals(v)) {
            return "#";
        }
        return v;
    }

    @Test
    @DisplayName("VIP 总数交叉对账：Ad-hoc IS_VIP 条件聚合 == VIP_OVERVIEW 指标（真库快照 10,779）")
    void vipCountMatchesVipOverviewMetric() {
        MetricQueryRequest q = new MetricQueryRequest();
        q.setMetric("VIP_OVERVIEW");
        MetricResult metric = metricQueryService.query("member", q);
        assertThat(metric.getErrorCode()).as("指标查询失败：" + metric.getMessage()).isNull();
        BigDecimal metricVipCount = bd(metric.value(0, "VIP_COUNT"));

        AdhocQueryRequest r = new AdhocQueryRequest();
        r.setEntities(List.of("member"));
        r.setMeasures(List.of(spec("conditional", "MEMBER_COUNT", "IS_VIP", "vip_cnt", null)));
        AdhocResult adhoc = adhocQueryService.query(r);
        assertThat(adhoc.errorCode()).as("Ad-hoc 查询失败：" + adhoc.errorMessage()).isNull();
        assertThat(bd(adhoc.rows().get(0).get("vip_cnt")))
                .as("要客总数：Ad-hoc 条件聚合与 VIP_OVERVIEW 逐值相等")
                .isEqualByComparingTo(metricVipCount);
    }

    @Test
    @DisplayName("要客消费交叉对账：Ad-hoc order.SALES + 会员 track=1 过滤 == VIP_CONSUMPTION_BY_STORE 合计行")
    void vipExpenseMatchesMetric() {
        MetricQueryRequest q = new MetricQueryRequest();
        q.setMetric("VIP_CONSUMPTION_BY_STORE");
        q.setDimensions(List.of("STORE"));
        q.setTopN(500);
        MetricResult metric = metricQueryService.query("member", q);
        assertThat(metric.getErrorCode()).as("指标查询失败：" + metric.getMessage()).isNull();

        AdhocQueryRequest r = new AdhocQueryRequest();
        r.setEntities(List.of("order", "member"));
        r.setMeasures(List.of(spec("simple", "SALES", null, "vip_expense", null)));
        FilterSpec vip = new FilterSpec();
        vip.setEntity("member");
        vip.setColumn("track");
        vip.setOp("=");
        vip.setValue("1");
        r.setFilters(List.of(vip));

        AdhocResult adhoc = adhocQueryService.query(r);
        assertThat(adhoc.errorCode()).as("Ad-hoc 查询失败：" + adhoc.errorMessage()).isNull();

        assertThat(bd(adhoc.rows().get(0).get("vip_expense")))
                .as("要客消费总额一致（两侧均为订单实付 expense_realamt 汇总）")
                .isEqualByComparingTo(bd(metric.totalValue("TOTAL_EXPENSE")));
    }

    @Test
    @DisplayName("门店分组交叉对账：Ad-hoc STORE_CODE 分组要客数 == VIP_BY_STORE 逐门店一致")
    void vipByStoreGroupByGroup() {
        MetricQueryRequest q = new MetricQueryRequest();
        q.setMetric("VIP_BY_STORE");
        q.setDimensions(List.of("STORE"));
        q.setTopN(500);
        MetricResult metric = metricQueryService.query("member", q);
        assertThat(metric.getErrorCode()).as("指标查询失败：" + metric.getMessage()).isNull();

        AdhocQueryRequest r = new AdhocQueryRequest();
        r.setEntities(List.of("member"));
        r.setDimensions(List.of("STORE_CODE"));
        // 两侧必须同分组宇宙：指标是 WHERE del_flag='0' AND track=1 后 GROUP BY，
        // Ad-hoc 侧用 filter preset IS_VIP（track='1'）对齐同一基线过滤；若不注入该过滤，
        // 全量会员分组会让无要客门店以 0 计数出现（真库实测 256 组 vs 指标 192 组），
        // 属分组宇宙差异而非口径错误——按指标框架对齐注入过滤，行数与逐店数值全量断言。
        // 条件聚合与基线过滤的计数等价已由 VIP 总数/消费两对对账覆盖，这里用 simple 计数与指标 COUNT(*) 同构。
        FilterSpec vipOnly = new FilterSpec();
        vipOnly.setPreset("IS_VIP");
        r.setFilters(List.of(vipOnly));
        r.setMeasures(List.of(spec("simple", "MEMBER_COUNT", null, "vip_cnt", null)));
        r.setLimit(500);
        r.setOrderBy("vip_cnt desc");
        AdhocResult adhoc = adhocQueryService.query(r);
        assertThat(adhoc.errorCode()).as("Ad-hoc 查询失败：" + adhoc.errorMessage()).isNull();

        assertThat(adhoc.rowCount()).as("分组行数一致").isEqualTo(metric.rowCount());

        Map<String, Long> metricByStore = new HashMap<>();
        for (int i = 0; i < metric.rowCount(); i++) {
            metricByStore.merge(storeKey(String.valueOf(metric.value(i, "STORE"))),
                    ((Number) metric.value(i, "VIP_COUNT")).longValue(), Long::sum);
        }
        Map<String, Long> adhocByStore = new HashMap<>();
        for (Map<String, Object> row : adhoc.rows()) {
            adhocByStore.merge(storeKey(String.valueOf(row.get("store_code"))),
                    ((Number) row.get("vip_cnt")).longValue(), Long::sum);
        }
        assertThat(adhocByStore).as("逐门店要客数一致（空门店组归并后整表相等）")
                .isEqualTo(metricByStore);
    }

    @Test
    @DisplayName("join 交叉对账：Ad-hoc order×member 分组 == GENDER_CONSUMPTION_COMPARE 指标（窗口内分性别销售额）")
    void genderJoinMatchesMetric() {
        MetricQueryRequest q = new MetricQueryRequest();
        q.setMetric("GENDER_CONSUMPTION_COMPARE");
        q.setDimensions(List.of("GENDER"));
        q.setStartDate(SHORT_START);
        q.setEndDate(END);
        MetricResult metric = metricQueryService.query("order", q);
        assertThat(metric.getErrorCode()).as("指标查询失败：" + metric.getMessage()).isNull();

        AdhocQueryRequest r = new AdhocQueryRequest();
        r.setEntities(List.of("order", "member"));
        r.setDimensions(List.of("ORDER_GENDER"));
        r.setMeasures(List.of(spec("simple", "SALES", null, "sales", null)));
        TimeRangeSpec t = new TimeRangeSpec();
        t.setEntity("order");
        t.setTimeDimension("BUY_DATE");
        t.setStartDate(SHORT_START);
        t.setEndDate(END);
        r.setTimeRange(t);
        AdhocResult adhoc = adhocQueryService.query(r);
        assertThat(adhoc.errorCode()).as("Ad-hoc 查询失败：" + adhoc.errorMessage()).isNull();

        Map<String, BigDecimal> metricSales = new LinkedHashMap<>();
        for (int i = 0; i < metric.rowCount(); i++) {
            metricSales.put(String.valueOf(metric.value(i, "GENDER")), bd(metric.value(i, "SALES")));
        }
        Map<String, BigDecimal> adhocSales = new LinkedHashMap<>();
        for (Map<String, Object> row : adhoc.rows()) {
            adhocSales.put(String.valueOf(row.get("order_gender")), bd(row.get("sales")));
        }
        assertThat(adhocSales.keySet()).as("性别分组一致（男/女/未知性别）")
                .isEqualTo(metricSales.keySet());
        adhocSales.forEach((gender, sales) -> assertThat(sales)
                .as("性别组销售额一致：" + gender)
                .isEqualByComparingTo(metricSales.get(gender)));
    }

    @Test
    @DisplayName("门店编码过滤真库冒烟：名称列过滤被拒 → member 名称解析编码 → order 编码等值命中")
    void storeCodeFilterAfterNameResolution() {
        // ① info 表名称列已移出过滤白名单（无索引）：服务端拒绝并指路编码列
        AdhocQueryRequest byName = new AdhocQueryRequest();
        byName.setEntities(List.of("order"));
        byName.setMeasures(List.of(spec("simple", "SALES", null, "sales", null)));
        FilterSpec nameFilter = new FilterSpec();
        nameFilter.setColumn("expense_stationName");
        nameFilter.setOp("LIKE");
        nameFilter.setValue("%长江路%");
        byName.setFilters(List.of(nameFilter));
        AdhocResult rejected = adhocQueryService.query(byName);
        assertThat(rejected.errorCode()).as("名称列过滤必须被拒").isEqualTo("INVALID_PARAM");
        assertThat(rejected.errorMessage())
                .contains("仅可输出、不可过滤")
                .contains("expense_stationCode");

        // ② 名称→编码解析：member 的 member_allot_stationName 有索引，允许名称过滤
        AdhocQueryRequest resolve = new AdhocQueryRequest();
        resolve.setEntities(List.of("member"));
        resolve.setDimensions(List.of("STORE_CODE", "STORE_NAME"));
        resolve.setMeasures(List.of(spec("simple", "MEMBER_COUNT", null, "cnt", null)));
        FilterSpec resolveByName = new FilterSpec();
        resolveByName.setColumn("member_allot_stationName");
        resolveByName.setOp("LIKE");
        resolveByName.setValue("%长江路%");
        resolve.setFilters(List.of(resolveByName));
        AdhocResult resolved = adhocQueryService.query(resolve);
        assertThat(resolved.errorCode()).as("member 名称解析失败：" + resolved.errorMessage()).isNull();
        assertThat(resolved.rows()).as("member 店名应能解析出门店编码").isNotEmpty();
        String code = String.valueOf(resolved.rows().get(0).get("store_code"));
        assertThat(code).as("解析出的门店编码非空").isNotBlank();

        // ③ 编码等值查 order：命中真实门店，0 行说明过滤落点又错了
        AdhocQueryRequest byCode = new AdhocQueryRequest();
        byCode.setEntities(List.of("order"));
        byCode.setDimensions(List.of("STATION_CODE"));
        byCode.setMeasures(List.of(
                spec("simple", "SALES", null, "sales", null),
                spec("simple", "ORDER_COUNT", null, "orders", null)));
        FilterSpec codeFilter = new FilterSpec();
        codeFilter.setEntity("order");
        codeFilter.setColumn("expense_stationCode");
        codeFilter.setOp("=");
        codeFilter.setValue(code);
        byCode.setFilters(List.of(codeFilter));
        TimeRangeSpec t = new TimeRangeSpec();
        t.setEntity("order");
        t.setTimeDimension("BUY_DATE");
        t.setStartDate(START);
        t.setEndDate(END);
        byCode.setTimeRange(t);
        AdhocResult res = adhocQueryService.query(byCode);
        assertThat(res.errorCode()).as("编码过滤查询失败：" + res.errorMessage()).isNull();
        assertThat(res.rows()).as("门店编码过滤应命中真实门店").isNotEmpty();
        assertThat(String.valueOf(res.rows().get(0).get("station_code"))).isEqualTo(code);
    }

    @Test
    @DisplayName("detail 单表对账：92 天守卫窗口内行数与实付金额 == 独立 oracle（NUM 展开逐值相等）")
    void detailSingleTableMatchesOracle() {
        AdhocQueryRequest r = new AdhocQueryRequest();
        r.setEntities(List.of("detail"));
        r.setMeasures(List.of(
                spec("simple", "DETAIL_LINES", null, "line_cnt", null),
                spec("simple", "DETAIL_SALES", null, "sales", null)));
        TimeRangeSpec t = new TimeRangeSpec();
        t.setTimeDimension("BUY_DATE");
        t.setStartDate(START);
        t.setEndDate(END);
        r.setTimeRange(t);
        AdhocResult res = adhocQueryService.query(r);
        assertThat(res.errorCode()).as("Ad-hoc 查询失败：" + res.errorMessage()).isNull();

        // 别名不用 lines：MySQL 保留字，8.0.21 上 SELECT COUNT(*) lines 直接 1064
        Map<String, Object> oracle = jdbcTemplate.queryForMap(
                "SELECT COUNT(*) line_cnt, SUM(CAST(NULLIF(TRIM(expense_d_realamt),'') AS DECIMAL(20,2))) sales"
                        + " FROM expense_calendar_details"
                        + " WHERE expense_d_state='10' AND del_flag='0'"
                        + " AND expense_d_buyDate >= ? AND expense_d_buyDate < DATE_ADD(?, INTERVAL 1 DAY)",
                START, END);
        assertThat(bd(res.rows().get(0).get("line_cnt")))
                .as("明细行数一致").isEqualByComparingTo(bd(oracle.get("line_cnt")));
        assertThat(bd(res.rows().get(0).get("sales")))
                .as("实付金额（NUM 展开）一致").isEqualByComparingTo(bd(oracle.get("sales")));
    }

    @Test
    @DisplayName("detail→member LEFT JOIN 不乘行：join 后行数/金额与单表全等（2026-11-03 授权 expense_d_mobile 关联）")
    void detailMemberJoinDoesNotMultiplyRows() {
        AdhocQueryRequest r = new AdhocQueryRequest();
        r.setEntities(List.of("detail", "member"));
        r.setDimensions(List.of("DETAIL_GENDER"));
        r.setMeasures(List.of(
                spec("simple", "DETAIL_LINES", null, "line_cnt", null),
                spec("simple", "DETAIL_SALES", null, "sales", null)));
        TimeRangeSpec t = new TimeRangeSpec();
        t.setTimeDimension("BUY_DATE");
        // join 与单表基准同窗口（SHORT_START）：断言的是「join 不乘行」，窗口长度不参与口径
        t.setStartDate(SHORT_START);
        t.setEndDate(END);
        r.setTimeRange(t);
        AdhocResult res = adhocQueryService.query(r);
        assertThat(res.errorCode()).as("Ad-hoc 查询失败：" + res.errorMessage()).isNull();

        long joinLines = res.rows().stream().mapToLong(row -> ((Number) row.get("line_cnt")).longValue()).sum();
        BigDecimal joinSales = res.rows().stream()
                .map(row -> bd(row.get("sales"))).reduce(BigDecimal.ZERO, BigDecimal::add);

        AdhocQueryRequest single = new AdhocQueryRequest();
        single.setEntities(List.of("detail"));
        single.setMeasures(List.of(
                spec("simple", "DETAIL_LINES", null, "line_cnt", null),
                spec("simple", "DETAIL_SALES", null, "sales", null)));
        single.setTimeRange(t);
        AdhocResult base = adhocQueryService.query(single);
        assertThat(base.errorCode()).as("单表基准查询失败：" + base.errorMessage()).isNull();

        // LEFT JOIN 保留全部明细行，join 不乘行的证明=分组求和与单表全等；
        // 性别维度里「未知性别」组吸收未匹配行（joinFilters 的 OR IS NULL 语义）
        assertThat(joinLines).as("join 未乘行：分组行数求和==单表行数")
                .isEqualTo(((Number) base.rows().get(0).get("line_cnt")).longValue());
        assertThat(joinSales).as("join 未改变金额：分组金额求和==单表金额")
                .isEqualByComparingTo(bd(base.rows().get(0).get("sales")));
    }

    @Test
    @DisplayName("守卫负例：detail 无定向条件被拒且原因可读；超 92 天窗同样被拒")
    void detailGuardRejectsUnguidedAndOverWide() {
        AdhocQueryRequest bare = new AdhocQueryRequest();
        bare.setEntities(List.of("detail"));
        bare.setMeasures(List.of(spec("simple", "DETAIL_LINES", null, "line_cnt", null)));
        AdhocResult rejected = adhocQueryService.query(bare);
        assertThat(rejected.errorCode()).isEqualTo("INVALID_PARAM");
        assertThat(rejected.errorMessage()).contains("定向查询条件");

        AdhocQueryRequest wide = new AdhocQueryRequest();
        wide.setEntities(List.of("detail"));
        wide.setMeasures(List.of(spec("simple", "DETAIL_LINES", null, "line_cnt", null)));
        TimeRangeSpec t = new TimeRangeSpec();
        t.setTimeDimension("BUY_DATE");
        t.setStartDate("2026-01-01");
        t.setEndDate("2026-06-30");
        wide.setTimeRange(t);
        AdhocResult wideRejected = adhocQueryService.query(wide);
        assertThat(wideRejected.errorCode()).isEqualTo("INVALID_PARAM");
        assertThat(wideRejected.errorMessage()).contains("92");
    }

    @Test
    @DisplayName("MCP 工具注册：含 adhocQuery/listSemanticCatalog/explainAdhoc 与 resolveOfficeStores")
    void adhocToolsRegistered() {
        List<String> names = Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .map(ToolCallback::getToolDefinition)
                .map(def -> def == null ? null : def.name())
                .toList();
        assertThat(names).contains("adhocQuery", "listSemanticCatalog", "explainAdhoc", "resolveOfficeStores");
    }
}

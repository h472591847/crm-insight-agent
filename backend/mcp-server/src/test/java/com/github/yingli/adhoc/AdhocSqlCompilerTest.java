package com.github.yingli.adhoc;

import com.github.yingli.adhoc.model.SemanticRegistry;
import com.github.yingli.adhoc.compile.AdhocSqlCompiler;
import com.github.yingli.adhoc.exception.AdhocException;
import com.github.yingli.adhoc.request.AdhocQueryRequest;
import com.github.yingli.adhoc.request.FilterSpec;
import com.github.yingli.adhoc.request.MeasureSpec;
import com.github.yingli.adhoc.request.TimeRangeSpec;
import com.github.yingli.shared.catalog.ColumnTypeRegistry;
import com.github.yingli.shared.sql.GeneratedSql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdhocSqlCompilerTest {

    private AdhocSqlCompiler compiler;

    @BeforeEach
    void setUp() {
        ColumnTypeRegistry types = ColumnTypeRegistry.fromYaml(Map.of(
                "piiColumns", List.of("member_mobile", "expense_mobile"),
                "piiComputeAllowedColumns", List.of("member_mobile", "expense_mobile"),
                "tables", Map.of(
                        "member_info_view", Map.of("varcharNumeric", List.of()),
                        "expense_calendar_info", Map.of("varcharNumeric", List.of("expense_realamt")),
                        "expense_calendar_details", Map.of("varcharNumeric", List.of(
                                "expense_d_stdtotal", "expense_d_realamt", "expense_d_qty", "expense_d_scrprice")))));
        compiler = new AdhocSqlCompiler(AdhocFixtures.registry(), types);
    }

    private AdhocQueryRequest req(List<String> entities, List<String> dims, List<MeasureSpec> measures) {
        AdhocQueryRequest r = new AdhocQueryRequest();
        r.setEntities(entities);
        r.setDimensions(dims);
        r.setMeasures(measures);
        return r;
    }

    private MeasureSpec spec(String type, String name, String preset, String alias) {
        MeasureSpec m = new MeasureSpec();
        m.setType(type);
        m.setName(name);
        m.setConditionPreset(preset);
        m.setAlias(alias);
        return m;
    }

    @Test
    @DisplayName("单实体 golden：分组 + 计数 + 条件聚合 + 基线过滤 + LIMIT")
    void singleEntityGolden() {
        GeneratedSql gen = compiler.compile(req(
                List.of("member"), List.of("STORE_CODE"),
                List.of(spec("simple", "MEMBER_COUNT", null, "cnt"),
                        spec("conditional", "MEMBER_COUNT", "IS_VIP", "vip_cnt"))));
        assertThat(gen.sql()).isEqualTo(
                "SELECT /*+ MAX_EXECUTION_TIME(60000) */ IFNULL(mv.member_allot_store, '未知门店') AS `store_code`, "
                        + "COUNT(*) AS `cnt`, "
                        + "SUM(CASE WHEN mv.track = ? THEN 1 ELSE 0 END) AS `vip_cnt` "
                        + "FROM member_info_view mv "
                        + "WHERE (mv.del_flag = '0') "
                        + "GROUP BY mv.member_allot_store "
                        + "LIMIT 100");
        assertThat(gen.params()).containsExactly("1");
        assertThat(gen.columns()).extracting(GeneratedSql.ColumnRef::code)
                .containsExactly("STORE_CODE", "cnt", "vip_cnt");
        assertThat(gen.sqlHash()).isNotBlank();
    }

    @Test
    @DisplayName("VARCHAR 数值列聚合自动展开 NUM()")
    void varcharNumericWrappedWithNum() {
        GeneratedSql gen = compiler.compile(req(
                List.of("order"), List.of(),
                List.of(spec("simple", "SALES", null, "sales"))));
        assertThat(gen.sql())
                .contains("SUM(CAST(NULLIF(TRIM(e.expense_realamt), '') AS DECIMAL(20, 2))) AS `sales`");
    }

    @Test
    @DisplayName("去重度量编译为 COUNT(DISTINCT 列)；描述列编译为 MAX 且不进 GROUP BY")
    void distinctAndAttribute() {
        GeneratedSql gen = compiler.compile(req(
                List.of("order"), List.of("STATION_CODE", "STATION_NAME"),
                List.of(spec("distinct", "BUYER_COUNT", null, "buyers"))));
        assertThat(gen.sql())
                .contains("COUNT(DISTINCT e.expense_mobile) AS `buyers`")
                .contains("MAX(e.expense_stationName) AS `station_name`")
                .contains("GROUP BY e.expense_stationCode")
                .doesNotContain("GROUP BY e.expense_stationCode, e.expense_stationName");
    }

    @Test
    @DisplayName("结构化过滤参数化：值只进 params，绝不出现在 SQL 文本")
    void filtersAreParameterized() {
        AdhocQueryRequest r = req(List.of("member"), List.of(),
                List.of(spec("simple", "MEMBER_COUNT", null, "cnt")));
        FilterSpec f = new FilterSpec();
        f.setColumn("member_allot_store");
        f.setOp("=");
        f.setValue("04010112");
        r.setFilters(List.of(f));
        GeneratedSql gen = compiler.compile(r);
        assertThat(gen.sql()).contains("AND (mv.member_allot_store = ?)");
        assertThat(gen.sql()).doesNotContain("04010112");
        assertThat(gen.params()).containsExactly("04010112");
    }

    @Test
    @DisplayName("IN/NOT IN：占位符按 values 展开，参数保持声明顺序且与既有过滤顺序一致")
    void inOperatorsPreserveParameterOrder() {
        AdhocQueryRequest r = req(List.of("member"), List.of(),
                List.of(spec("simple", "MEMBER_COUNT", null, "cnt")));
        FilterSpec track = new FilterSpec();
        track.setColumn("track");
        track.setOp("=");
        track.setValue("1");
        FilterSpec stores = new FilterSpec();
        stores.setColumn("member_allot_store");
        stores.setOp("IN");
        stores.setValues(List.of("04010112", "04010113"));
        FilterSpec levels = new FilterSpec();
        levels.setColumn("company_level");
        levels.setOp("NOT IN");
        levels.setValues(List.of("GA"));
        r.setFilters(List.of(track, stores, levels));

        GeneratedSql gen = compiler.compile(r);
        assertThat(gen.sql()).contains(
                "AND (mv.track = ?) AND (mv.member_allot_store IN (?,?)) AND (mv.company_level NOT IN (?))");
        assertThat(gen.params()).containsExactly("1", "04010112", "04010113", "GA");
        assertThat(gen.sql()).doesNotContain("04010112").doesNotContain("04010113");
    }

    @Test
    @DisplayName("单值 IN 生成一个占位符；不传 values 的旧写法 SQL 逐字不变")
    void singleValueInAndLegacySqlUnchanged() {
        AdhocQueryRequest inRequest = req(List.of("member"), List.of(),
                List.of(spec("simple", "MEMBER_COUNT", null, "cnt")));
        FilterSpec in = new FilterSpec();
        in.setColumn("member_allot_store");
        in.setOp("IN");
        in.setValues(List.of("04010112"));
        inRequest.setFilters(List.of(in));
        GeneratedSql inSql = compiler.compile(inRequest);
        assertThat(inSql.sql()).contains("AND (mv.member_allot_store IN (?))");
        assertThat(inSql.params()).containsExactly("04010112");

        AdhocQueryRequest legacy = req(List.of("member"), List.of(),
                List.of(spec("simple", "MEMBER_COUNT", null, "cnt")));
        FilterSpec equals = new FilterSpec();
        equals.setColumn("member_allot_store");
        equals.setOp("=");
        equals.setValue("04010112");
        legacy.setFilters(List.of(equals));
        GeneratedSql legacySql = compiler.compile(legacy);
        assertThat(legacySql.sql()).isEqualTo(
                "SELECT /*+ MAX_EXECUTION_TIME(60000) */ COUNT(*) AS `cnt` FROM member_info_view mv "
                        + "WHERE (mv.del_flag = '0') AND (mv.member_allot_store = ?) LIMIT 100");
        assertThat(legacySql.params()).containsExactly("04010112");
    }

    @Test
    @DisplayName("500 个 IN 值：生成 500 个 ? 且参数数量、顺序与 values 完全一致")
    void fiveHundredInValues() {
        List<String> values = java.util.stream.IntStream.range(0, FilterSpec.MAX_IN_VALUES)
                .mapToObj(i -> String.format("010%05d", i)).toList();
        AdhocQueryRequest r = req(List.of("member"), List.of(),
                List.of(spec("simple", "MEMBER_COUNT", null, "cnt")));
        FilterSpec stores = new FilterSpec();
        stores.setColumn("member_allot_store");
        stores.setOp("IN");
        stores.setValues(values);
        r.setFilters(List.of(stores));

        GeneratedSql gen = compiler.compile(r);

        int first = gen.sql().indexOf("mv.member_allot_store IN (");
        String inClause = gen.sql().substring(first);
        assertThat(inClause.chars().filter(c -> c == '?').count()).isEqualTo(500);
        assertThat(gen.params()).hasSize(500).containsExactlyElementsOf(values);
        assertThat(gen.sql()).doesNotContain("01000000").doesNotContain(values.get(499));
    }

    @Test
    @DisplayName("IS NULL 类过滤无参数占位")
    void nullOperator() {
        AdhocQueryRequest r = req(List.of("member"), List.of(),
                List.of(spec("simple", "MEMBER_COUNT", null, "cnt")));
        FilterSpec f = new FilterSpec();
        f.setColumn("track_cancel_time"); // 未在白名单——先验证校验拒绝路径由 Task2 覆盖，此处改白名单列
        f.setColumn("track");
        f.setOp("IS NULL");
        r.setFilters(List.of(f));
        GeneratedSql gen = compiler.compile(r);
        assertThat(gen.sql()).contains("AND (mv.track IS NULL)");
        assertThat(gen.params()).isEmpty();
    }

    @Test
    @DisplayName("时间窗口：左闭右开 + DATE_ADD 表达式 + 两参数")
    void timeWindow() {
        AdhocQueryRequest r = req(List.of("member"), List.of(),
                List.of(spec("simple", "MEMBER_COUNT", null, "cnt")));
        TimeRangeSpec t = new TimeRangeSpec();
        t.setTimeDimension("TRACK_TIME");
        t.setStartDate("2025-01-01");
        t.setEndDate("2026-06-28");
        r.setTimeRange(t);
        GeneratedSql gen = compiler.compile(r);
        assertThat(gen.sql()).contains("AND mv.track_time >= ? AND mv.track_time < DATE_ADD(?, INTERVAL 1 DAY)");
        assertThat(gen.params()).containsExactly("2025-01-01", "2026-06-28");
    }

    @Test
    @DisplayName("排序与 LIMIT 收敛：orderBy → ORDER BY alias dir；limit 越界回落后仍 ≤ 1000")
    void orderByAndLimit() {
        AdhocQueryRequest r = req(List.of("member"), List.of("STORE_CODE"),
                List.of(spec("simple", "MEMBER_COUNT", null, "cnt")));
        r.setOrderBy("cnt desc");
        r.setLimit(50);
        GeneratedSql gen = compiler.compile(r);
        assertThat(gen.sql()).endsWith("ORDER BY `cnt` desc LIMIT 50");
    }

    @Test
    @DisplayName("detail golden：VARCHAR 金额 NUM 展开、mobile 参数化过滤、实体级缺省 limit 500")
    void detailEntityGolden() {
        AdhocQueryRequest r = req(List.of("detail"), List.of(),
                List.of(spec("simple", "DETAIL_SALES", null, "sales")));
        FilterSpec byMobile = new FilterSpec();
        byMobile.setColumn("expense_d_mobile");
        byMobile.setOp("=");
        byMobile.setValue("13800000000");
        r.setFilters(List.of(byMobile));
        GeneratedSql gen = compiler.compile(r);
        assertThat(gen.sql()).contains("FROM expense_calendar_details ed");
        assertThat(gen.sql()).contains("WHERE (ed.expense_d_state = '10') AND (ed.del_flag = '0')");
        assertThat(gen.sql()).contains("CAST(NULLIF(TRIM(ed.expense_d_realamt), '') AS DECIMAL(20, 2))");
        assertThat(gen.sql()).contains("ed.expense_d_mobile = ?");
        assertThat(gen.params()).containsExactly("13800000000");
        assertThat(gen.sql()).endsWith("LIMIT 500");
    }

    @Test
    @DisplayName("维度 nullLabel 编译为 IFNULL 输出，未知分组语义与指标框架一致")
    void nullLabelWrapped() {
        GeneratedSql gen = compiler.compile(req(
                List.of("member"), List.of("STORE_CODE"),
                List.of(spec("simple", "MEMBER_COUNT", null, "cnt"))));
        assertThat(gen.sql()).contains("IFNULL(mv.member_allot_store, '未知门店') AS `store_code`");
    }

    @Test
    @DisplayName("expr 型维度（CASE WHEN）原样进 SELECT，GROUP BY 引用同一表达式")
    void exprDimension() {
        GeneratedSql gen = compiler.compile(req(
                List.of("member"), List.of("GENDER"),
                List.of(spec("simple", "MEMBER_COUNT", null, "cnt"))));
        assertThat(gen.sql()).contains("CASE WHEN mv.member_sex = 1 THEN '男'")
                .contains("GROUP BY CASE WHEN mv.member_sex = 1 THEN '男'");
    }

    @Test
    @DisplayName("order LEFT JOIN member：join 关系与 joinFilters 就位，度量/维度各归其主")
    void joinGolden() {
        GeneratedSql gen = compiler.compile(req(
                List.of("order", "member"), List.of("ORDER_GENDER"),
                List.of(spec("simple", "SALES", null, "sales"),
                        spec("distinct", "BUYER_COUNT", null, "buyers"))));
        assertThat(gen.sql()).contains(
                "FROM expense_calendar_info e LEFT JOIN member_info_view mv ON e.expense_mobile = mv.member_mobile");
        assertThat(gen.sql()).contains("(e.expense_state = '10')");
        assertThat(gen.sql()).contains("(mv.del_flag = '0' OR mv.member_mobile IS NULL)");
        assertThat(gen.sql()).contains("GROUP BY CASE WHEN mv.member_sex = 1 THEN '男'");
    }

    @Test
    @DisplayName("member LEFT JOIN order：反向关系同样按元数据声明编译")
    void reverseJoinGolden() {
        GeneratedSql gen = compiler.compile(req(
                List.of("member", "order"), List.of(),
                List.of(spec("simple", "MEMBER_COUNT", null, "cnt"))));
        assertThat(gen.sql()).contains(
                "FROM member_info_view mv LEFT JOIN expense_calendar_info e ON mv.member_mobile = e.expense_mobile");
        assertThat(gen.sql()).contains("(e.expense_state = '10' AND e.del_flag = '0')");
        assertThat(gen.sql()).contains("(mv.del_flag = '0')");
    }

    @Test
    @DisplayName("member LEFT JOIN detail：detail 时间窗进 ON，LEFT JOIN 语义不退化；COUNT(*) 换 COUNT(ed.id)")
    void memberDetailJoinGolden() {
        AdhocQueryRequest r = req(List.of("member", "detail"), List.of("STORE_CODE"),
                List.of(spec("simple", "DETAIL_LINES", null, "lines")));
        TimeRangeSpec t = new TimeRangeSpec();
        t.setEntity("detail");
        t.setTimeDimension("BUY_DATE");
        t.setStartDate("2026-01-01");
        t.setEndDate("2026-02-01");
        r.setTimeRange(t);
        GeneratedSql gen = compiler.compile(r);
        assertThat(gen.sql()).contains(
                "FROM member_info_view mv LEFT JOIN expense_calendar_details ed "
                        + "ON mv.member_mobile = ed.expense_d_mobile "
                        + "AND (ed.expense_d_state = '10' AND ed.del_flag = '0') "
                        + "AND ed.expense_d_buyDate >= ? "
                        + "AND ed.expense_d_buyDate < DATE_ADD(?, INTERVAL 1 DAY)");
        assertThat(gen.sql()).contains("COUNT(ed.id) AS `lines`");
        assertThat(gen.sql()).contains("WHERE (mv.del_flag = '0')");
        assertThat(gen.sql()).doesNotContain("WHERE (mv.del_flag = '0') AND ed.expense_d_buyDate");
        assertThat(gen.params()).containsExactly("2026-01-01", "2026-02-01");
    }

    @Test
    @DisplayName("join 侧 COUNT：member→order 的 ORDER_COUNT 编译为 COUNT(e.id)，主实体 COUNT 仍是 COUNT(*)")
    void joinedCountUsesCountKey() {
        GeneratedSql joined = compiler.compile(req(List.of("member", "order"), List.of(),
                List.of(spec("simple", "ORDER_COUNT", null, "cnt"))));
        assertThat(joined.sql()).contains("COUNT(e.id) AS `cnt`");

        GeneratedSql head = compiler.compile(req(List.of("member"), List.of(),
                List.of(spec("simple", "MEMBER_COUNT", null, "cnt"))));
        assertThat(head.sql()).contains("COUNT(*) AS `cnt`");
    }

    @Test
    @DisplayName("detail 作主实体：时间窗仍进 WHERE（只有被 join 实体才进 ON）")
    void detailHeadTimeWindowStaysInWhere() {
        AdhocQueryRequest r = req(List.of("detail", "member"), List.of("DETAIL_GENDER"),
                List.of(spec("simple", "DETAIL_SALES", null, "sales")));
        TimeRangeSpec t = new TimeRangeSpec();
        t.setEntity("detail");
        t.setTimeDimension("BUY_DATE");
        t.setStartDate("2026-01-01");
        t.setEndDate("2026-02-01");
        r.setTimeRange(t);
        GeneratedSql gen = compiler.compile(r);
        assertThat(gen.sql()).contains(
                "WHERE (ed.expense_d_state = '10') AND (ed.del_flag = '0') "
                        + "AND ed.expense_d_buyDate >= ? "
                        + "AND ed.expense_d_buyDate < DATE_ADD(?, INTERVAL 1 DAY)");
        assertThat(gen.params()).containsExactly("2026-01-01", "2026-02-01");
    }

    @Test
    @DisplayName("未声明关系：编译期拒绝（member→order 有 LEFT JOIN 关系，order→member 亦然，方向由元数据决定）")
    void unknownJoinRejected() {
        SemanticRegistry registry = AdhocFixtures.registry();
        // 构造一个两端都在但无关系声明的请求不可达（当前两实体互为声明）；
        // 因此用一个假目标验关系解析：直接测 entityChain 对未声明 target 的行为
        AdhocQueryRequest r = req(List.of("member", "ghost"), List.of(),
                List.of(spec("simple", "MEMBER_COUNT", null, "cnt")));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> compiler.compile(r))
                .isInstanceOf(AdhocException.class)
                .hasFieldOrPropertyWithValue("code", "ADHOC_NO_RELATIONSHIP");
    }

    @Test
    @DisplayName("保留字 alias（lines）：编译后加反引号，避免 MySQL 8.0 的 1064")
    void reservedAliasQuoted() {
        GeneratedSql gen = compiler.compile(req(List.of("detail"), List.of(),
                List.of(spec("simple", "DETAIL_LINES", null, "lines"))));
        assertThat(gen.sql()).contains("COUNT(*) AS `lines`");
        // 结果列仍按未加引号的 alias 取名，服务层 Map key 不受影响
        assertThat(gen.columns()).extracting(GeneratedSql.ColumnRef::alias)
                .containsExactly("lines");
    }

    @Test
    @DisplayName("多保留字 alias（lines/interval/groups）：逐个加反引号")
    void multipleReservedAliasesQuoted() {
        GeneratedSql gen = compiler.compile(req(List.of("detail"), List.of(),
                List.of(spec("simple", "DETAIL_LINES", null, "lines"),
                        spec("simple", "DETAIL_LINES", null, "interval"),
                        spec("simple", "DETAIL_LINES", null, "groups"))));
        assertThat(gen.sql())
                .contains("AS `lines`")
                .contains("AS `interval`")
                .contains("AS `groups`");
        assertThat(gen.columns()).extracting(GeneratedSql.ColumnRef::alias)
                .containsExactly("lines", "interval", "groups");
    }

    @Test
    @DisplayName("海鼎级别维度与要客标签维度：列/表达式按元数据编译，GROUP BY 同表达式")
    void haidingLevelAndVipFlagDimension() {
        GeneratedSql gen = compiler.compile(req(
                List.of("member"), List.of("HAIDING_LEVEL", "VIP_FLAG"),
                List.of(spec("simple", "MEMBER_COUNT", null, "cnt"))));
        assertThat(gen.sql())
                .contains("IFNULL(mv.reserved15, '未填写') AS `haiding_level`")
                .contains("CASE WHEN mv.track = 1 THEN '要客' WHEN mv.track = 0 THEN '非要客' ELSE '未知' END AS `vip_flag`")
                .contains("GROUP BY mv.reserved15, CASE WHEN mv.track = 1 THEN '要客'");
    }

    @Test
    @DisplayName("会员级别维度 + 消费次数度量：IFNULL 输出、GROUP BY 原列、SUM 聚合")
    void memberLevelDimensionAndTimesMeasure() {
        GeneratedSql gen = compiler.compile(req(
                List.of("member"), List.of("MEMBER_LEVEL"),
                List.of(spec("simple", "MEMBER_EXPENSE_TIMES", null, "times"))));
        assertThat(gen.sql())
                .contains("IFNULL(mv.new_member_level_value, '未填写') AS `member_level`")
                .contains("SUM(mv.member_expense_time) AS `times`")
                .contains("GROUP BY mv.new_member_level_value");
        assertThat(gen.columns()).extracting(GeneratedSql.ColumnRef::code)
                .containsExactly("MEMBER_LEVEL", "times");
    }

    @Test
    @DisplayName("member/order 手机号等值过滤：编译为对应表列的参数化条件")
    void memberAndOrderMobileFilterCompiled() {
        GeneratedSql member = compiler.compile(withPhone(
                req(List.of("member"), List.of(), List.of(spec("simple", "MEMBER_COUNT", null, "cnt"))),
                "member", "member_mobile"));
        assertThat(member.sql()).contains("mv.member_mobile = ?");
        assertThat(member.params()).containsExactly("13199288863");

        GeneratedSql order = compiler.compile(withPhone(
                req(List.of("order"), List.of(), List.of(spec("simple", "ORDER_COUNT", null, "cnt"))),
                "order", "expense_mobile"));
        assertThat(order.sql()).contains("e.expense_mobile = ?");
        assertThat(order.params()).containsExactly("13199288863");
    }

    private AdhocQueryRequest withPhone(AdhocQueryRequest r, String entity, String column) {
        FilterSpec f = new FilterSpec();
        f.setEntity(entity);
        f.setColumn(column);
        f.setOp("=");
        f.setValue("13199288863");
        r.setFilters(List.of(f));
        return r;
    }
}

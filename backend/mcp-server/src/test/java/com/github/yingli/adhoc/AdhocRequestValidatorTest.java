package com.github.yingli.adhoc;

import com.github.yingli.adhoc.exception.AdhocException;
import com.github.yingli.adhoc.request.AdhocQueryRequest;
import com.github.yingli.adhoc.request.FilterSpec;
import com.github.yingli.adhoc.request.MeasureSpec;
import com.github.yingli.adhoc.request.TimeRangeSpec;
import com.github.yingli.adhoc.validate.AdhocRequestValidator;
import com.github.yingli.shared.catalog.ColumnTypeRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdhocRequestValidatorTest {

    private AdhocRequestValidator validator;

    @BeforeEach
    void setUp() {
        ColumnTypeRegistry types = ColumnTypeRegistry.fromYaml(Map.of(
                "piiColumns", List.of("member_mobile", "member_name", "expense_mobile"),
                "piiComputeAllowedColumns", List.of("member_mobile", "expense_mobile"),
                "tables", Map.of(
                        "member_info_view", Map.of("varcharNumeric", List.of()),
                        "expense_calendar_info", Map.of("varcharNumeric", List.of("expense_realamt")),
                        "expense_calendar_details", Map.of("varcharNumeric", List.of(
                                "expense_d_stdtotal", "expense_d_realamt", "expense_d_qty", "expense_d_scrprice")))));
        validator = new AdhocRequestValidator(AdhocFixtures.registry(), types);
    }

    private AdhocQueryRequest req(List<String> entities, List<String> dims, List<MeasureSpec> measures) {
        AdhocQueryRequest r = new AdhocQueryRequest();
        r.setEntities(entities);
        r.setDimensions(dims);
        r.setMeasures(measures);
        return r;
    }

    private MeasureSpec simple(String name, String alias) {
        MeasureSpec m = new MeasureSpec();
        m.setType("simple");
        m.setName(name);
        m.setAlias(alias);
        return m;
    }

    @Test
    @DisplayName("合法单实体请求通过：member 快照聚合")
    void legalSingleEntity() {
        assertThatCode(() -> validator.validate(req(
                List.of("member"), List.of("STORE_CODE"),
                List.of(simple("MEMBER_COUNT", "cnt"))))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("实体缺失 / 空 measures：拒绝")
    void structuralChecks() {
        assertThatThrownBy(() -> validator.validate(req(List.of(), List.of(), List.of())))
                .isInstanceOf(AdhocException.class).hasMessageContaining("entities");
        assertThatThrownBy(() -> validator.validate(req(
                List.of("member"), List.of("STORE_CODE"), List.of())))
                .isInstanceOf(AdhocException.class).hasMessageContaining("measures");
    }

    @Test
    @DisplayName("未声明维度/度量：拒绝并给出可用清单")
    void unknownNamesRejected() {
        assertThatThrownBy(() -> validator.validate(req(
                List.of("member"), List.of("MOBILE"),
                List.of(simple("MEMBER_COUNT", "cnt")))))
                .isInstanceOf(AdhocException.class).hasMessageContaining("MOBILE");
        assertThatThrownBy(() -> validator.validate(req(
                List.of("member"), List.of(),
                List.of(simple("AVG_AGE", "x")))))
                .isInstanceOf(AdhocException.class).hasMessageContaining("AVG_AGE");
    }

    @Test
    @DisplayName("conditional 必须引用已声明 conditionPreset；distinct/derived 规则")
    void measureTypeChecks() {
        MeasureSpec cond = new MeasureSpec();
        cond.setType("conditional");
        cond.setName("MEMBER_COUNT");
        cond.setConditionPreset("IS_VIP");
        cond.setAlias("vip_cnt");
        assertThatCode(() -> validator.validate(req(List.of("member"), List.of("STORE_CODE"), List.of(cond))))
                .doesNotThrowAnyException();

        MeasureSpec badPreset = new MeasureSpec();
        badPreset.setType("conditional");
        badPreset.setName("MEMBER_COUNT");
        badPreset.setConditionPreset("IS_ADMIN");
        badPreset.setAlias("x");
        assertThatThrownBy(() -> validator.validate(req(List.of("member"), List.of(), List.of(badPreset))))
                .isInstanceOf(AdhocException.class).hasMessageContaining("IS_ADMIN");

        MeasureSpec derived = new MeasureSpec();
        derived.setType("derived");
        derived.setFormula("vip_cnt / cnt");
        derived.setAlias("avg_vip_share");
        // 引用的 alias 必须在同一请求中先行声明
        assertThatThrownBy(() -> validator.validate(req(List.of("member"), List.of(), List.of(derived))))
                .isInstanceOf(AdhocException.class);
        assertThatCode(() -> validator.validate(req(
                List.of("member"), List.of(),
                List.of(simple("MEMBER_COUNT", "cnt"), cond, derived)))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("PII 原值输出拒绝（member_mobile 不可作维度）；PII 计算允许（BUYER_COUNT DISTINCT）")
    void piiBoundaries() {
        AdhocQueryRequest mobileDim = req(List.of("member"), List.of("MOBILE"),
                List.of(simple("MEMBER_COUNT", "cnt")));
        // metadata 未声明 MOBILE 维度 → 走未知维度分支，同样拒绝；此处直接验证 filterable 也不放行原值输出列
        assertThatThrownBy(() -> validator.validate(mobileDim))
                .isInstanceOf(AdhocException.class);

        // BUYER_COUNT 为 COUNT_DISTINCT 度量：type 必须用 distinct（simple 会被校验器拒绝改道）
        MeasureSpec buyers = new MeasureSpec();
        buyers.setType("distinct");
        buyers.setName("BUYER_COUNT");
        buyers.setAlias("buyers");
        AdhocQueryRequest orderReq = req(List.of("order"), List.of("STATION_CODE"), List.of(buyers));
        orderReq.setFilters(List.of());
        assertThatCode(() -> validator.validate(orderReq)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("filter：preset 或 column/op/value 二选一；op 白名单；column 必须 filterable")
    void filterChecks() {
        AdhocQueryRequest r = req(List.of("member"), List.of(),
                List.of(simple("MEMBER_COUNT", "cnt")));
        FilterSpec byPreset = new FilterSpec();
        byPreset.setPreset("IS_VIP");
        r.setFilters(List.of(byPreset));
        assertThatCode(() -> validator.validate(r)).doesNotThrowAnyException();

        FilterSpec structured = new FilterSpec();
        structured.setColumn("member_allot_store");
        structured.setOp("=");
        structured.setValue("04010112");
        r.setFilters(List.of(structured));
        assertThatCode(() -> validator.validate(r)).doesNotThrowAnyException();

        structured.setOp("SLEEP");
        assertThatThrownBy(() -> validator.validate(r))
                .isInstanceOf(AdhocException.class).hasMessageContaining("SLEEP");

        structured.setOp("=");
        structured.setColumn("member_address");
        assertThatThrownBy(() -> validator.validate(r))
                .isInstanceOf(AdhocException.class).hasMessageContaining("不可过滤");
    }

    @Test
    @DisplayName("IN/NOT IN：单值、两值合法；同列等值与 IN 并存仍按 AND 叠加")
    void inFiltersAllowed() {
        AdhocQueryRequest r = req(List.of("member"), List.of(),
                List.of(simple("MEMBER_COUNT", "cnt")));
        FilterSpec one = filter("company_level", "IN", null);
        one.setValues(List.of("AB"));
        FilterSpec two = filter("member_allot_store", "IN", null);
        two.setValues(List.of("04010112", "04010113"));
        FilterSpec notIn = filter("half_year_level", "NOT IN", null);
        notIn.setValues(List.of("FC"));
        r.setFilters(List.of(filter("member_allot_store", "=", "04010112"), one, two, notIn));
        assertThatCode(() -> validator.validate(r)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("IN values 必须非空、去重后不超过 500，且 value 必须为空")
    void inValuesRequiredAndCapped() {
        AdhocQueryRequest r = req(List.of("member"), List.of(),
                List.of(simple("MEMBER_COUNT", "cnt")));
        FilterSpec in = filter("member_allot_store", "IN", null);
        r.setFilters(List.of(in));
        assertThatThrownBy(() -> validator.validate(r)).hasMessageContaining("values");

        in.setValues(List.of());
        assertThatThrownBy(() -> validator.validate(r)).hasMessageContaining("非空");

        // 边界：正好 500 个通过（覆盖 CRM 总部级 375 个门店）
        in.setValues(IntStream.range(0, 500).mapToObj(i -> "store" + i).toList());
        assertThatCode(() -> validator.validate(r)).doesNotThrowAnyException();

        in.setValues(IntStream.range(0, 501).mapToObj(i -> "store" + i).toList());
        assertThatThrownBy(() -> validator.validate(r)).hasMessageContaining("500");

        in.setValues(List.of("04010112"));
        in.setValue("04010112");
        assertThatThrownBy(() -> validator.validate(r)).hasMessageContaining("value");

        in.setValues(List.of("04010112", "04010112"));
        in.setValue(null);
        assertThatCode(() -> validator.validate(r)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("非 IN/NOT IN 操作符不得携带 values；value 不按逗号拆分")
    void valuesOnlyAllowedForInOperators() {
        AdhocQueryRequest r = req(List.of("member"), List.of(),
                List.of(simple("MEMBER_COUNT", "cnt")));
        FilterSpec equals = filter("member_allot_store", "=", "04010112,04010113");
        equals.setValues(List.of("04010112", "04010113"));
        r.setFilters(List.of(equals));
        assertThatThrownBy(() -> validator.validate(r)).hasMessageContaining("values");
    }

    @ParameterizedTest
    @MethodSource("phoneColumns")
    @DisplayName("手机号列禁止 IN/NOT IN，仍只允许完整号码精确匹配")
    void mobileInRejected(String entity, String column, String measure) {
        AdhocQueryRequest r = req(List.of(entity), List.of(), List.of(simple(measure, "cnt")));
        for (String op : List.of("IN", "NOT IN")) {
            FilterSpec f = phone(entity, column);
            f.setOp(op);
            f.setValue(null);
            f.setValues(List.of("13199288863", "13800000000"));
            r.setFilters(List.of(f));
            assertThatThrownBy(() -> validator.validate(r))
                    .isInstanceOf(AdhocException.class)
                    .hasMessageContaining("完整手机号");
        }
    }

    @Test
    @DisplayName("门店名称列禁止过滤：expense_stationName 无索引，必须用编码列等值（拒绝并提示可过滤列）")
    void stationNameNotFilterable() {
        AdhocQueryRequest r = req(List.of("order"), List.of("STATION_CODE"),
                List.of(simple("SALES", "sales")));
        FilterSpec byName = new FilterSpec();
        byName.setColumn("expense_stationName");
        byName.setOp("LIKE");
        byName.setValue("%哈市长江路店%");
        r.setFilters(List.of(byName));
        assertThatThrownBy(() -> validator.validate(r))
                .isInstanceOf(AdhocException.class)
                .hasMessageContaining("仅可输出、不可过滤")
                .hasMessageContaining("expense_stationCode");
    }

    @Test
    @DisplayName("member 门店名称可过滤：member_allot_stationName 有索引，供名称→编码解析")
    void memberStationNameFilterable() {
        AdhocQueryRequest r = req(List.of("member"),
                List.of("STORE_CODE", "STORE_NAME"),
                List.of(simple("MEMBER_COUNT", "cnt")));
        FilterSpec byName = new FilterSpec();
        byName.setColumn("member_allot_stationName");
        byName.setOp("LIKE");
        byName.setValue("哈市长江路%");
        r.setFilters(List.of(byName));
        assertThatCode(() -> validator.validate(r)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("守卫：detail 作主实体无任何定向条件 → 拒绝，中文原因点名三选一")
    void detailHeadRequiresGuardCondition() {
        assertThatThrownBy(() -> validator.validate(detailReq(List.of(), null)))
                .isInstanceOf(AdhocException.class)
                .hasMessageContaining("时间范围")
                .hasMessageContaining("手机号")
                .hasMessageContaining("流水号");
    }

    @Test
    @DisplayName("守卫：detail 时间窗超实体上限 92 天 → 拒绝（全局上限 550 不适用明细表）")
    void detailTimeWindowCappedAt92Days() {
        TimeRangeSpec wide = new TimeRangeSpec();
        wide.setTimeDimension("BUY_DATE");
        wide.setStartDate("2025-01-01");
        wide.setEndDate("2025-06-30");
        assertThatThrownBy(() -> validator.validate(detailReq(List.of(), wide)))
                .isInstanceOf(AdhocException.class)
                .hasMessageContaining("92");
    }

    @Test
    @DisplayName("守卫：手机号或流水号定向可免时间窗（≤92 天窗口带时间也放行）")
    void detailGuardSatisfiedByPiiTargetOrOrderFlow() {
        FilterSpec byMobile = new FilterSpec();
        byMobile.setColumn("expense_d_mobile");
        byMobile.setOp("=");
        byMobile.setValue("13800000000");
        assertThatCode(() -> validator.validate(detailReq(List.of(byMobile), null)))
                .doesNotThrowAnyException();

        FilterSpec byOrderId = new FilterSpec();
        byOrderId.setColumn("expense_d_orderId");
        byOrderId.setOp("=");
        byOrderId.setValue("X123");
        assertThatCode(() -> validator.validate(detailReq(List.of(byOrderId), null)))
                .doesNotThrowAnyException();

        TimeRangeSpec window = new TimeRangeSpec();
        window.setTimeDimension("BUY_DATE");
        window.setStartDate("2026-01-01");
        window.setEndDate("2026-03-01");
        assertThatCode(() -> validator.validate(detailReq(List.of(), window)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("守卫只认等值定向：orderId 用 != / IS NULL 不能免时间窗；手机号模糊由手机号列精确匹配规则拒绝")
    void detailGuardRequiresEquality() {
        for (FilterSpec f : List.of(
                filter("expense_d_orderId", "!=", "X123"),
                filter("expense_d_orderId", "IS NULL", null))) {
            assertThatThrownBy(() -> validator.validate(detailReq(List.of(f), null)))
                    .isInstanceOf(AdhocException.class)
                    .hasMessageContaining("定向查询条件");
        }
        assertThatThrownBy(() -> validator.validate(
                detailReq(List.of(filter("expense_d_mobile", "LIKE", "%138%")), null)))
                .isInstanceOf(AdhocException.class)
                .hasMessageContaining("完整手机号");
    }

    private FilterSpec filter(String column, String op, String value) {
        FilterSpec f = new FilterSpec();
        f.setColumn(column);
        f.setOp(op);
        f.setValue(value);
        return f;
    }

    @Test
    @DisplayName("守卫对链上任意 detail 生效：detail=detail 主实体时由自身时间窗满足")
    void guardAppliesToDetailInChain() {
        AdhocQueryRequest r = req(List.of("detail", "member"), List.of("DETAIL_GENDER"),
                List.of(simple("DETAIL_LINES", "line_cnt")));
        FilterSpec byStation = new FilterSpec();
        byStation.setEntity("detail");
        byStation.setColumn("expense_d_stationCode");
        byStation.setOp("=");
        byStation.setValue("04010112");
        r.setFilters(List.of(byStation));
        TimeRangeSpec window = new TimeRangeSpec();
        window.setEntity("detail");
        window.setTimeDimension("BUY_DATE");
        window.setStartDate("2026-01-01");
        window.setEndDate("2026-02-01");
        r.setTimeRange(window);
        assertThatCode(() -> validator.validate(r)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("member 主表 join detail：detail 仍必须带守卫条件（时间范围需显式落 detail）")
    void memberDetailRequiresDetailGuard() {
        AdhocQueryRequest r = req(List.of("member", "detail"), List.of("STORE_CODE"),
                List.of(simple("DETAIL_LINES", "line_cnt")));
        assertThatThrownBy(() -> validator.validate(r))
                .isInstanceOf(AdhocException.class)
                .hasMessageContaining("detail")
                .hasMessageContaining("时间范围");
    }

    @Test
    @DisplayName("member 主表 join detail：时间窗落 detail 且 ≤92 天时放行")
    void memberDetailAllowedWithDetailTimeWindow() {
        AdhocQueryRequest r = req(List.of("member", "detail"), List.of("STORE_CODE"),
                List.of(simple("DETAIL_LINES", "line_cnt")));
        r.setTimeRange(window("detail", "BUY_DATE", "2026-01-01", "2026-02-01"));
        assertThatCode(() -> validator.validate(r)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("粒度守卫：member→detail 行粒度是 detail，member 快照度量不可同选")
    void memberDetailRejectsMemberMeasure() {
        AdhocQueryRequest r = req(List.of("member", "detail"), List.of(),
                List.of(simple("MEMBER_COUNT", "cnt")));
        r.setTimeRange(window("detail", "BUY_DATE", "2026-01-01", "2026-02-01"));
        assertThatThrownBy(() -> validator.validate(r))
                .isInstanceOf(AdhocException.class)
                .hasMessageContaining("MEMBER_COUNT")
                .hasMessageContaining("adhocQueryBatch");
    }

    @Test
    @DisplayName("粒度守卫：member→order 行粒度是 order，member 快照度量不可同选")
    void memberOrderRejectsMemberMeasure() {
        assertThatThrownBy(() -> validator.validate(req(List.of("member", "order"), List.of(),
                List.of(simple("MEMBER_COUNT", "cnt")))))
                .isInstanceOf(AdhocException.class)
                .hasMessageContaining("MEMBER_COUNT")
                .hasMessageContaining("order");
    }

    @Test
    @DisplayName("粒度守卫：detail→member 行粒度是 detail，member 度量不可同选")
    void joinedMemberMeasureRejected() {
        AdhocQueryRequest r = req(List.of("detail", "member"), List.of(),
                List.of(simple("MEMBER_EXPENSE_TIMES", "times")));
        r.setTimeRange(window("detail", "BUY_DATE", "2026-01-01", "2026-02-01"));
        assertThatThrownBy(() -> validator.validate(r))
                .isInstanceOf(AdhocException.class)
                .hasMessageContaining("MEMBER_EXPENSE_TIMES")
                .hasMessageContaining("detail");
    }

    @Test
    @DisplayName("粒度守卫：detail→member→order 先一对一再一对多，复合粒度直接拒绝")
    void compositeChainRejected() {
        AdhocQueryRequest r = req(List.of("detail", "member", "order"), List.of(),
                List.of(simple("DETAIL_LINES", "line_cnt")));
        r.setTimeRange(window("detail", "BUY_DATE", "2026-01-01", "2026-02-01"));
        assertThatThrownBy(() -> validator.validate(r))
                .isInstanceOf(AdhocException.class)
                .hasMessageContaining("复合粒度")
                .hasMessageContaining("adhocQueryBatch");
    }

    @Test
    @DisplayName("member→order 选 order 粒度度量：合法（行粒度与度量一致）")
    void memberOrderAllowsOrderMeasure() {
        assertThatCode(() -> validator.validate(req(List.of("member", "order"), List.of(),
                List.of(simple("ORDER_COUNT", "cnt")))))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"lines", "interval", "groups", "order", "rank", "window", "recursive"})
    @DisplayName("度量 alias 命中 MySQL 8.0 保留字：INVALID_PARAM 并提示换名")
    void reservedAliasRejected(String alias) {
        assertThatThrownBy(() -> validator.validate(req(List.of("detail"), List.of(),
                List.of(simple("DETAIL_LINES", alias)))))
                .isInstanceOf(AdhocException.class)
                .hasMessageContaining("保留字")
                .hasMessageContaining(alias);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sales", "qty", "line_cnt", "cnt", "value", "status"})
    @DisplayName("非保留关键字不误伤：alias 可正常通过校验")
    void nonReservedAliasAllowed(String alias) {
        assertThatCode(() -> validator.validate(detailByMobile(alias))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("detail 直查：完整手机号等值过滤可单独满足守卫，无需时间窗")
    void detailMobileOnlySatisfiesGuard() {
        assertThatCode(() -> validator.validate(detailByMobile("line_cnt"))).doesNotThrowAnyException();
    }

    private AdhocQueryRequest detailByMobile(String alias) {
        AdhocQueryRequest r = req(List.of("detail"), List.of(), List.of(simple("DETAIL_LINES", alias)));
        FilterSpec mobile = new FilterSpec();
        mobile.setEntity("detail");
        mobile.setColumn("expense_d_mobile");
        mobile.setOp("=");
        mobile.setValue("13199288863");
        r.setFilters(List.of(mobile));
        return r;
    }

    @Test
    @DisplayName("多种会员级别口径与要客维度：新开放维度、过滤列均通过白名单")
    void memberLevelCandidatesAllowed() {
        AdhocQueryRequest r = req(List.of("member"),
                List.of("HAIDING_LEVEL", "COMPANY_LEVEL", "CITY_LEVEL", "STORE_LEVEL", "VIP_FLAG"),
                List.of(simple("MEMBER_COUNT", "cnt")));
        r.setFilters(List.of(filter("reserved15", "=", "VIP"),
                filter("half_year_level", "=", "DB"),
                filter("track", "=", "1")));
        assertThatCode(() -> validator.validate(r)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("会员级别/层级字段：新开放维度、过滤列与消费次数度量均通过白名单")
    void memberLevelFieldsAllowed() {
        AdhocQueryRequest r = req(List.of("member"), List.of("MEMBER_LEVEL"),
                List.of(simple("MEMBER_EXPENSE_TIMES", "times")));
        r.setFilters(List.of(filter("new_member_level_value", "=", "臻享"),
                filter("customer_cost", "=", "重要价值用户")));
        assertThatCode(() -> validator.validate(r)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("phoneColumns")
    @DisplayName("手机号列等值过滤：member/order/detail 三列均放行")
    void exactMobileFilterAllowed(String entity, String column, String measure) {
        AdhocQueryRequest r = req(List.of(entity), List.of(), List.of(simple(measure, "cnt")));
        r.setFilters(List.of(phone(entity, column)));
        assertThatCode(() -> validator.validate(r)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("phoneColumns")
    @DisplayName("手机号列 LIKE 模糊匹配：三列均 INVALID_PARAM 并提示需要完整手机号")
    void fuzzyMobileFilterRejected(String entity, String column, String measure) {
        AdhocQueryRequest r = req(List.of(entity), List.of(), List.of(simple(measure, "cnt")));
        FilterSpec f = phone(entity, column);
        f.setOp("LIKE");
        f.setValue("1319928%");
        r.setFilters(List.of(f));
        assertThatThrownBy(() -> validator.validate(r))
                .isInstanceOf(AdhocException.class)
                .hasMessageContaining("完整手机号")
                .extracting(e -> ((AdhocException) e).code())
                .isEqualTo("INVALID_PARAM");
    }

    static Stream<Arguments> phoneColumns() {
        return Stream.of(
                Arguments.of("member", "member_mobile", "MEMBER_COUNT"),
                Arguments.of("order", "expense_mobile", "ORDER_COUNT"),
                Arguments.of("detail", "expense_d_mobile", "DETAIL_LINES"));
    }

    private FilterSpec phone(String entity, String column) {
        FilterSpec f = new FilterSpec();
        f.setEntity(entity);
        f.setColumn(column);
        f.setOp("=");
        f.setValue("13199288863");
        return f;
    }

    private TimeRangeSpec window(String entity, String dim, String start, String end) {
        TimeRangeSpec t = new TimeRangeSpec();
        t.setEntity(entity);
        t.setTimeDimension(dim);
        t.setStartDate(start);
        t.setEndDate(end);
        return t;
    }

    private AdhocQueryRequest detailReq(List<FilterSpec> filters, TimeRangeSpec timeRange) {
        AdhocQueryRequest r = req(List.of("detail"), List.of(), List.of(simple("DETAIL_LINES", "line_cnt")));
        r.setFilters(filters);
        r.setTimeRange(timeRange);
        return r;
    }

    @Test
    @DisplayName("filter 显式 entity 必须已在 entities 声明：entities=[member] + filter entity=order → 拒绝")
    void filterEntityMustBeDeclared() {
        AdhocQueryRequest r = req(List.of("member"), List.of(),
                List.of(simple("MEMBER_COUNT", "cnt")));
        FilterSpec crossEntity = new FilterSpec();
        crossEntity.setEntity("order");
        crossEntity.setColumn("expense_stationCode");   // order 白名单内的列，但实体未参与本次请求
        crossEntity.setOp("=");
        crossEntity.setValue("04010112");
        r.setFilters(List.of(crossEntity));
        assertThatThrownBy(() -> validator.validate(r))
                .isInstanceOf(AdhocException.class)
                .hasMessageContaining("未在 entities 中声明")
                .hasMessageContaining("order");
    }

    @Test
    @DisplayName("时间窗口：timeDimension 必须声明；日期格式校验；跨度上限复用 QueryLimits")
    void timeRangeChecks() {
        AdhocQueryRequest r = req(List.of("member"), List.of(),
                List.of(simple("MEMBER_COUNT", "cnt")));
        TimeRangeSpec t = new TimeRangeSpec();
        t.setEntity("member");
        t.setTimeDimension("TRACK_TIME");
        t.setStartDate("2025-01-01");
        t.setEndDate("2026-06-28");
        r.setTimeRange(t);
        assertThatCode(() -> validator.validate(r)).doesNotThrowAnyException();

        t.setStartDate("2024-01-01");
        t.setEndDate("2026-06-28");
        r.setTimeRange(t);
        assertThatThrownBy(() -> validator.validate(r))
                .isInstanceOf(AdhocException.class).hasMessageContaining("跨度");

        t.setStartDate("2026-13-01");
        assertThatThrownBy(() -> validator.validate(r))
                .isInstanceOf(AdhocException.class).hasMessageContaining("格式");
    }

    @Test
    @DisplayName("跨实体维度 ORDER_GENDER 未 join member：拒绝")
    void requiresRelationship() {
        AdhocQueryRequest r = req(List.of("order"), List.of("ORDER_GENDER"),
                List.of(simple("SALES", "s")));
        assertThatThrownBy(() -> validator.validate(r))
                .isInstanceOf(AdhocException.class).hasMessageContaining("member");

        r.setEntities(List.of("order", "member"));
        org.assertj.core.api.Assertions.assertThatCode(() -> validator.validate(r))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("实体重复出现：同一实体在 entities 中只允许出现一次（防环/防无限交替链）")
    void duplicateEntityRejected() {
        AdhocQueryRequest r = req(List.of("member", "order", "member"), List.of(),
                List.of(simple("MEMBER_COUNT", "cnt")));
        assertThatThrownBy(() -> validator.validate(r))
                .isInstanceOf(AdhocException.class)
                .hasMessageContaining("实体重复出现")
                .hasMessageContaining("member")
                .extracting(e -> ((AdhocException) e).code())
                .isEqualTo("INVALID_PARAM");
    }

    @Test
    @DisplayName("alias 命名规则：^[a-z][a-z0-9_]{0,31}$；同请求内唯一；orderBy 必须引用请求内 alias")
    void aliasAndOrderChecks() {
        AdhocQueryRequest r = req(List.of("member"), List.of(),
                List.of(simple("MEMBER_COUNT", "cnt; DROP")));
        assertThatThrownBy(() -> validator.validate(r)).isInstanceOf(AdhocException.class);

        r.setMeasures(List.of(simple("MEMBER_COUNT", "cnt")));
        r.setOrderBy("not_in_request desc");
        assertThatThrownBy(() -> validator.validate(r)).isInstanceOf(AdhocException.class);
        r.setOrderBy("cnt desc");
        assertThatCode(() -> validator.validate(r)).doesNotThrowAnyException();
    }
}

package com.itzixi.metrics;

import com.itzixi.metrics.validate.MetricConfigValidator;
import com.itzixi.metrics.request.MetricDraftRequest;
import com.itzixi.metrics.request.MetricDraftRequest.AttributeSpec;
import com.itzixi.metrics.request.MetricDraftRequest.DimensionSpec;
import com.itzixi.metrics.request.MetricDraftRequest.JoinSpec;
import com.itzixi.metrics.request.MetricDraftRequest.MeasureSpec;
import com.itzixi.metrics.config.MetricDefinitionParser;
import com.itzixi.metrics.model.MetricDefinition;
import com.itzixi.metrics.tooling.MetricDrafter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName MetricDrafterTest
 * @Description 指标脚手架单测（不连库）。核心闭环断言：脚手架产出的 YAML 片段必须能被
 * 生产解析器 {@link MetricDefinitionParser} 再解析回 {@link MetricDefinition} 并通过
 * {@link MetricConfigValidator} —— 等价于"粘进配置文件后能否启动"。
 * 覆盖：合法草稿闭环、VARCHAR 金额自动 NUM、PII 列拦截、未知表拒绝、caliber 必填、
 * 缺省 status=UNCONFIRMED、编码重名拒绝、grain 按主表推断、join 的 ON 组装与 on 键加引号。
 * @Author crm-agent
 */
class MetricDrafterTest {

    private final MetricDrafter drafter = MetricsFixtures.drafter();

    /** 与生产加载同源：SafeConstructor 解析产出的 YAML 片段。 */
    @SuppressWarnings("unchecked")
    private MetricDefinition reparse(String yaml) {
        Yaml loader = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object root = loader.load(yaml);
        List<MetricDefinition> defs = MetricDefinitionParser.parseFile(root, "draft-test");
        assertThat(defs).hasSize(1);
        return defs.get(0);
    }

    private MetricDraftRequest validOrderDraft() {
        MetricDraftRequest r = new MetricDraftRequest();
        r.setMetric("T_DRAFT_STATION");
        r.setTitle("草稿门店业绩");
        r.setDomain("order");
        r.setCaliber("开发代拟口径，待业务确认");
        r.setTable("expense_calendar_info");
        r.setTimeColumn("expense_buyDate");
        r.setBaselineFilters(List.of("expense_state = '10'", "del_flag = '0'"));
        DimensionSpec dim = new DimensionSpec();
        dim.setCode("STATION");
        dim.setColumn("expense_stationCode");
        dim.setAlias("station_code");
        r.setDimensions(List.of(dim));
        AttributeSpec attr = new AttributeSpec();
        attr.setColumn("expense_stationName");
        attr.setAlias("station_name");
        r.setAttributes(List.of(attr));
        MeasureSpec sales = new MeasureSpec();
        sales.setCode("SALES");
        sales.setAgg("SUM");
        sales.setColumn("expense_realamt"); // VARCHAR 数值列：应自动包 NUM()
        sales.setAlias("sales_amount");
        sales.setUnit("CNY");
        sales.setZeroFill(true);
        MeasureSpec orders = new MeasureSpec();
        orders.setCode("ORDERS");
        orders.setAgg("COUNT");
        orders.setColumn("*");
        orders.setAlias("order_count");
        orders.setUnit("COUNT");
        r.setMeasures(List.of(sales, orders));
        return r;
    }

    @Test
    @DisplayName("合法单表草稿：ok=true，产出的 YAML 可再解析并通过加载期校验器")
    void validDraft_roundTripsAndPassesValidator() {
        Map<String, Object> res = drafter.draft(validOrderDraft());

        assertThat(res.get("ok")).isEqualTo(true);
        assertThat((List<?>) res.get("validationErrors")).isEmpty();
        String yaml = (String) res.get("yaml");
        assertThat(yaml).isNotBlank();

        MetricDefinition def = reparse(yaml);
        assertThat(MetricConfigValidator.validate(def, MetricsFixtures.types(), com.itzixi.metrics.registry.MetricDomains.ALLOWED))
                .as("脚手架产出等价于可启动配置").isEmpty();
        assertThat(def.metric()).isEqualTo("T_DRAFT_STATION");
        assertThat(def.grain()).isEqualTo(MetricDefinition.Grain.ORDER_HEAD);
    }

    @Test
    @DisplayName("VARCHAR 金额列命中登记表：脚手架自动包 NUM()，样例 SQL 展开安全数值转换")
    void varcharAmount_autoWrappedInNum() {
        Map<String, Object> res = drafter.draft(validOrderDraft());

        assertThat((List<String>) res.get("autoWrappedColumns")).contains("expense_realamt");
        String yaml = (String) res.get("yaml");
        assertThat(yaml).contains("expr: \"NUM(expense_realamt)\"");
        assertThat((String) res.get("compiledSqlSample"))
                .contains("CAST(NULLIF(TRIM(expense_realamt), '') AS DECIMAL(20, 2))");
    }

    @Test
    @DisplayName("非时间敏感列不包裹：COUNT(*) 直通，不被误加 NUM")
    void countStar_notWrapped() {
        Map<String, Object> res = drafter.draft(validOrderDraft());
        String yaml = (String) res.get("yaml");
        assertThat(yaml).contains("expr: \"*\"").contains("agg: COUNT");
    }

    @Test
    @DisplayName("维度引用 PII 列（原值输出）：被拒绝、ok=false、blockedPiiColumns 点名")
    void piiDimensionBlocked() {
        MetricDraftRequest r = validOrderDraft();
        DimensionSpec pii = new DimensionSpec();
        pii.setCode("MEMBER");
        pii.setColumn("member_name"); // PII，且非计算白名单
        pii.setAlias("member_name");
        r.setDimensions(List.of(pii));

        Map<String, Object> res = drafter.draft(r);
        assertThat(res.get("ok")).isEqualTo(false);
        assertThat((List<?>) res.get("validationErrors")).isNotEmpty();
        assertThat((List<String>) res.get("blockedPiiColumns")).contains("member_name");
    }

    @Test
    @DisplayName("未知表：白名单外，校验拒绝")
    void unknownTableRejected() {
        MetricDraftRequest r = validOrderDraft();
        r.setTable("ghost_table");
        Map<String, Object> res = drafter.draft(r);
        assertThat(res.get("ok")).isEqualTo(false);
        assertThat((List<?>) res.get("validationErrors"))
                .anyMatch(e -> String.valueOf(e).contains("table 未登记"));
    }

    @Test
    @DisplayName("caliber 缺失：口径是唯一事实来源，脚手架要求必填")
    void blankCaliberRejected() {
        MetricDraftRequest r = validOrderDraft();
        r.setCaliber("   ");
        Map<String, Object> res = drafter.draft(r);
        assertThat(res.get("ok")).isEqualTo(false);
        assertThat((List<?>) res.get("validationErrors"))
                .anyMatch(e -> String.valueOf(e).contains("caliber"));
    }

    @Test
    @DisplayName("缺省 status=UNCONFIRMED：起草不自动\"看起来权威\"（AGENTS 第五节）")
    void statusDefaultsToUnconfirmed() {
        MetricDraftRequest r = validOrderDraft();
        r.setStatus(null);
        String yaml = (String) drafter.draft(r).get("yaml");
        assertThat(yaml).contains("status: UNCONFIRMED");
        assertThat(reparse(yaml).status()).isEqualTo(MetricDefinition.Status.UNCONFIRMED);
    }

    @Test
    @DisplayName("显式 CONFIRMED 才落地为可执行状态")
    void explicitConfirmedKept() {
        MetricDraftRequest r = validOrderDraft();
        r.setStatus("confirmed");
        String yaml = (String) drafter.draft(r).get("yaml");
        assertThat(yaml).contains("status: CONFIRMED");
    }

    @Test
    @DisplayName("编码与已注册指标重名：提前点名，避免启动期加载失败")
    void duplicateMetricCodeRejected() {
        MetricDraftRequest r = validOrderDraft();
        r.setMetric("STATION_SALES"); // 已在 order.yaml 注册
        Map<String, Object> res = drafter.draft(r);
        assertThat(res.get("ok")).isEqualTo(false);
        assertThat((List<?>) res.get("validationErrors"))
                .anyMatch(e -> String.valueOf(e).contains("重名"));
    }

    @Test
    @DisplayName("grain 缺省按主表推断：明细表→ORDER_DETAIL")
    void grainInferredFromTable() {
        MetricDraftRequest r = validOrderDraft();
        r.setTable("expense_calendar_details");
        r.setTimeColumn("expense_d_buyDate");
        r.setBaselineFilters(List.of("expense_d_state = '10'", "del_flag = '0'"));
        DimensionSpec dim = new DimensionSpec();
        dim.setCode("SKU");
        dim.setColumn("expense_d_gdcode");
        dim.setAlias("sku");
        r.setDimensions(List.of(dim));
        r.setAttributes(List.of());
        MeasureSpec sales = new MeasureSpec();
        sales.setCode("SALES");
        sales.setAgg("SUM");
        sales.setColumn("expense_d_realamt");
        sales.setAlias("sales_amount");
        r.setMeasures(List.of(sales));

        String yaml = (String) drafter.draft(r).get("yaml");
        assertThat(yaml).contains("grain: ORDER_DETAIL");
    }

    @Test
    @DisplayName("join 草稿：ON 按别名组装、on 键加引号（规避 SnakeYAML 布尔键陷阱），限定列自动 NUM")
    void joinDraft_composesOnAndQualifies() {
        MetricDraftRequest r = new MetricDraftRequest();
        r.setMetric("T_DRAFT_GENDER");
        r.setTitle("草稿男女消费对比");
        r.setDomain("order");
        r.setCaliber("订单按关联会员性别分组对比（脚手架产物，待对账）");
        r.setTable("expense_calendar_info");
        r.setTimeColumn("e.expense_buyDate");
        JoinSpec join = new JoinSpec();
        join.setJoinTable("member_info");
        join.setJoinAlias("m");
        join.setMainAlias("e");
        join.setOnLeftCol("expense_mobile");   // 裸列：脚手架补 mainAlias
        join.setOnRightCol("member_mobile");   // 裸列：脚手架补 joinAlias（member_mobile 在 piiComputeAllowed 内）
        r.setJoin(join);
        r.setBaselineFilters(List.of("e.expense_state = '10'", "e.del_flag = '0'"));
        DimensionSpec gender = new DimensionSpec();
        gender.setCode("GENDER");
        gender.setAlias("gender");
        gender.setExpr("CASE WHEN m.member_sex = 1 THEN '男' WHEN m.member_sex = 2 THEN '女' ELSE '未知' END");
        r.setDimensions(List.of(gender));
        MeasureSpec sales = new MeasureSpec();
        sales.setCode("SALES");
        sales.setAgg("SUM");
        sales.setColumn("e.expense_realamt"); // 限定列：按 mainAlias→主表判定 VARCHAR 数值 → NUM(e.expense_realamt)
        sales.setAlias("sales_amount");
        sales.setUnit("CNY");
        MeasureSpec buyers = new MeasureSpec();
        buyers.setCode("BUYERS");
        buyers.setAgg("COUNT");
        buyers.setExpr("DISTINCT e.expense_mobile"); // 完整表达式直通
        buyers.setAlias("buyer_count");
        r.setMeasures(List.of(sales, buyers));

        Map<String, Object> res = drafter.draft(r);
        assertThat(res.get("ok")).as(String.valueOf(res.get("validationErrors"))).isEqualTo(true);
        String yaml = (String) res.get("yaml");
        assertThat(yaml).contains("mainAlias: e");
        assertThat(yaml).contains("\"on\": \"e.expense_mobile = m.member_mobile\"");
        assertThat(yaml).contains("expr: \"NUM(e.expense_realamt)\"");
        assertThat(yaml).contains("expr: \"DISTINCT e.expense_mobile\"");
        assertThat((String) res.get("compiledSqlSample"))
                .contains("FROM expense_calendar_info e LEFT JOIN member_info m ON e.expense_mobile = m.member_mobile");

        // 闭环：join 草稿产出可再解析并过校验器
        MetricDefinition def = reparse(yaml);
        assertThat(MetricConfigValidator.validate(def, MetricsFixtures.types(), com.itzixi.metrics.registry.MetricDomains.ALLOWED))
                .isEmpty();
    }

    @Test
    @DisplayName("非法度量（对未登记列用 NUM）等配置错误：validationErrors 原样中文回显、ok=false")
    void invalidMeasureSurfacesValidationErrors() {
        MetricDraftRequest r = validOrderDraft();
        List<MeasureSpec> measures = new ArrayList<>(r.getMeasures());
        MeasureSpec bad = new MeasureSpec();
        bad.setCode("QTY");
        bad.setAgg("SUM");
        bad.setExpr("NUM(not_a_numeric_col)"); // 列未登记为 VARCHAR 数值
        bad.setAlias("quantity");
        measures.add(bad);
        r.setMeasures(measures);

        Map<String, Object> res = drafter.draft(r);
        assertThat(res.get("ok")).isEqualTo(false);
        assertThat((List<?>) res.get("validationErrors"))
                .anyMatch(e -> String.valueOf(e).contains("未登记为 VARCHAR 数值"));
    }
}

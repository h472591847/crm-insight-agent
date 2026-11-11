package com.itzixi.metrics;

import com.itzixi.metrics.validate.MetricConfigValidator;
import com.itzixi.metrics.config.MetricDefinitionParser;
import com.itzixi.metrics.model.MetricDefinition;
import com.itzixi.shared.limits.QueryLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName MetricDefinitionValidatorTest
 * @Description 加载期安全校验的规则覆盖（方案 §3.3）。这些用例保证：即使 AI 生成配置出错，
 * 也会在启动期被拦下，而不是变成生产上"看起来正确的错数字"。不连接数据库。
 * @Author crm-agent
 */
class MetricDefinitionValidatorTest {

    private MetricDefinition minimalDef(Map<String, Object> overrides) {
        Map<String, Object> item = new HashMap<>();
        item.put("metric", "T_DEMO");
        item.put("title", "演示指标");
        item.put("domain", "order");
        item.put("status", "CONFIRMED");
        item.put("caliber", "演示口径");
        item.put("table", "expense_calendar_info");
        item.put("grain", "ORDER_HEAD");
        item.put("timeColumn", "expense_buyDate");
        item.put("baselineFilters", List.of("del_flag = '0'"));
        item.put("measures", List.of(Map.of("code", "SALES", "expr", "NUM(expense_realamt)",
                "agg", "SUM", "alias", "sales_amount")));
        item.putAll(overrides);
        return MetricDefinitionParser.fromMap(item);
    }

    private List<String> errors(Map<String, Object> overrides) {
        return MetricConfigValidator.validate(minimalDef(overrides), MetricsFixtures.types(),
                com.itzixi.metrics.registry.MetricDomains.ALLOWED);
    }

    @Test
    @DisplayName("真实配置（classpath:/metrics）加载即通过全部校验，共 17 个指标")
    void realConfigsPassValidation() {
        assertThat(MetricsFixtures.registry().size()).isEqualTo(17);
        for (MetricDefinition def : MetricsFixtures.registry().all()) {
            assertThat(MetricConfigValidator.validate(def, MetricsFixtures.types(),
                    com.itzixi.metrics.registry.MetricDomains.ALLOWED)).isEmpty();
        }
    }

    @Test
    @DisplayName("裸 VARCHAR 金额列做 SUM 必须走 NUM()，否则启动期失败")
    void varcharAmount_requiresNum() {
        Map<String, Object> bad = new HashMap<>();
        bad.put("measures", List.of(Map.of("code", "SALES", "expr", "expense_realamt",
                "agg", "SUM", "alias", "sales_amount")));
        assertThat(errors(bad)).anyMatch(e -> e.contains("必须写成 NUM(expense_realamt)"));
    }

    @Test
    @DisplayName("未登记的表禁止引用（表白名单）")
    void unknownTable_rejected() {
        assertThat(errors(Map.of("table", "crm_secret_table")))
                .anyMatch(e -> e.contains("未登记在白名单内"));
    }

    @Test
    @DisplayName("PII 列禁止作为维度/描述列输出")
    void piiColumn_rejected() {
        assertThat(errors(Map.of("dimensions", List.of(Map.of(
                "code", "MOBILE", "expr", "expense_mobile", "alias", "mobile")))))
                .anyMatch(e -> e.contains("PII"));
    }

    @Test
    @DisplayName("PII 三态裁决①：计算白名单列的裸列输出仍一律拒绝（生日/手机号不可原值回显）")
    void piiBareColumn_rejectedEvenIfComputeAllowed() {
        assertThat(errors(Map.of("dimensions", List.of(Map.of(
                "code", "BIRTHDAY", "expr", "member_yang_birthday", "alias", "bd")))))
                .anyMatch(e -> e.contains("PII") && e.contains("不可原值输出"));
        // 阴历生日是 varchar，加入计算白名单后仍不得裸列输出（否则原值直接离开数据库）
        assertThat(errors(Map.of("dimensions", List.of(Map.of(
                "code", "BIRTHDAY_YIN", "expr", "member_yin_birthday", "alias", "bd_yin")))))
                .anyMatch(e -> e.contains("PII") && e.contains("不可原值输出"));
    }

    @Test
    @DisplayName("PII 三态裁决②：白名单列在 CASE 分桶/COUNT DISTINCT 表达式内引用 → 允许")
    void piiInBucketingExpression_allowed() {
        // 生日仅作分桶计算输入（AGE_DIST：阴历列为 varchar，需 STR_TO_DATE 后才能参与日期运算）
        assertThat(errors(Map.of("dimensions", List.of(Map.of(
                "code", "AGE_BAND", "alias", "age_band",
                "expr", "CASE WHEN STR_TO_DATE(member_yin_birthday, '%Y-%m-%d') IS NULL THEN '未知' ELSE '已知' END")))))
                .noneMatch(e -> e.contains("PII"));
        assertThat(errors(Map.of("dimensions", List.of(Map.of(
                "code", "AGE_BAND", "alias", "age_band",
                "expr", "CASE WHEN member_yang_birthday IS NULL THEN '未知' ELSE '已知' END")))))
                .noneMatch(e -> e.contains("PII"));
        // 手机号仅作去重计数输入（ACTIVE_MEMBER）
        assertThat(errors(Map.of("measures", List.of(Map.of(
                "code", "ACTIVE", "expr", "DISTINCT expense_mobile", "agg", "COUNT", "alias", "active")))))
                .noneMatch(e -> e.contains("PII"));
    }

    @Test
    @DisplayName("PII 三态裁决③：不在计算白名单的 PII（member_cardno）即使包在表达式里也拒绝")
    void piiNotInComputeWhitelist_rejectedEvenInExpression() {
        // 注：member_mobile 已于 2026-10-30 路线 C 入计算白名单（仅作 join 关联键），
        // 改用从未入白名单的 member_cardno 验证同一拦截规则
        assertThat(errors(Map.of("dimensions", List.of(Map.of(
                "code", "MASKED", "alias", "masked",
                "expr", "CASE WHEN member_cardno IS NULL THEN 'x' ELSE 'y' END")))))
                .anyMatch(e -> e.contains("PII"));
    }

    @Test
    @DisplayName("受控 join：非法表/非法 ON 形态/未知限定符全部拒绝；合法声明通过")
    void controlledJoin_validated() {
        Map<String, Object> good = new HashMap<>();
        good.put("join", Map.of("table", "member_info", "alias", "m", "mainAlias", "e",
                "on", "e.expense_mobile = m.member_mobile"));
        good.put("timeColumn", "e.expense_buyDate");
        good.put("baselineFilters", List.of("e.del_flag = '0'"));
        good.put("measures", List.of(Map.of("code", "SALES",
                "expr", "NUM(e.expense_realamt)", "agg", "SUM", "alias", "sales_amount")));
        assertThat(errors(good)).isEmpty();

        // 未登记表
        Map<String, Object> badTable = new HashMap<>(good);
        badTable.put("join", Map.of("table", "crm_secret", "alias", "x", "mainAlias", "e",
                "on", "e.expense_mobile = x.some_col"));
        assertThat(errors(badTable)).anyMatch(e -> e.contains("join.table 未登记"));

        // ON 携带多余文本/子查询/占位符
        Map<String, Object> badOn = new HashMap<>(good);
        badOn.put("join", Map.of("table", "member_info", "alias", "m", "mainAlias", "e",
                "on", "e.expense_mobile = m.member_mobile OR 1=1"));
        assertThat(errors(badOn)).anyMatch(e -> e.contains("join.on 只允许"));

        // 未知限定符（企图引入第三张表）
        Map<String, Object> badQualifier = new HashMap<>(good);
        badQualifier.put("dimensions", List.of(Map.of(
                "code", "X", "alias", "xx", "expr", "z.member_sex")));
        assertThat(errors(badQualifier)).anyMatch(e -> e.contains("未知限定符"));

        // 表达式里用裸 PII 列名（起别名后主表列也必须限定，裸列命中 PII 仍拒）
        Map<String, Object> rawPii = new HashMap<>(good);
        rawPii.put("dimensions", List.of(Map.of(
                "code", "MOBILE", "alias", "mob", "expr", "e.expense_mobile")));
        assertThat(errors(rawPii)).anyMatch(e -> e.contains("PII") && e.contains("不可原值输出"));
    }

    @Test
    @DisplayName("join.on 关联列必须是 PII 计算白名单内列（新 PII 列参与关联需先逐个 review）")
    void joinOnColumn_requiresPiiComputeWhitelist() {
        // member_address 是 PII 且从未入白名单 → 企图用它做关联键必须拒绝
        assertThat(errors(Map.of("join", Map.of("table", "member_info", "alias", "m", "mainAlias", "e",
                "on", "e.expense_stationCode = m.member_address"))))
                .anyMatch(e -> e.contains("未授权 PII"));
    }

    @Test
    @DisplayName("defaultWindowMonths：越界拒绝；非时间口径声明窗口视为配置错误；合法值通过")
    void defaultWindowMonths_validated() {
        assertThat(errors(Map.of("defaultWindowMonths", QueryLimits.MAX_DEFAULT_WINDOW_MONTHS + 1)))
                .anyMatch(e -> e.contains("defaultWindowMonths"));
        assertThat(errors(Map.of("timeColumn", "", "defaultWindowMonths", 3)))
                .anyMatch(e -> e.contains("配置可疑"));
        assertThat(errors(Map.of("defaultWindowMonths", 3))).isEmpty();
    }

    @Test
    @DisplayName("maxRangeDays：负数/超全局上限拒绝；非时间口径声明上限视为配置错误；合法值与缺省 0 通过")
    void maxRangeDays_validated() {
        assertThat(errors(Map.of("maxRangeDays", QueryLimits.MAX_RANGE_DAYS + 1)))
                .anyMatch(e -> e.contains("maxRangeDays"));
        assertThat(errors(Map.of("maxRangeDays", -1)))
                .anyMatch(e -> e.contains("maxRangeDays"));
        assertThat(errors(Map.of("timeColumn", "", "maxRangeDays", 92)))
                .anyMatch(e -> e.contains("配置可疑"));
        assertThat(errors(Map.of("maxRangeDays", 92))).isEmpty();
        // 缺省 0：沿用全局上限，不得因未声明而报错（存量指标不受影响）
        assertThat(errors(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("租户维度已全面取消（2026-10-30）：任何指标都不得再声明或引用租户列")
    void noMetricReferencesTenantColumns() {
        for (MetricDefinition def : MetricsFixtures.registry().all()) {
            assertThat(def.optionalFilters())
                    .as("指标 " + def.metric() + " 不应再声明租户过滤")
                    .noneMatch(f -> f.column().contains("tenant") || "TENANT".equals(f.code()));
            assertThat(def.dimensions())
                    .as("指标 " + def.metric() + " 不应再有租户维度")
                    .noneMatch(d -> String.valueOf(d.expr()).contains("tenant") || "TENANT".equals(d.code()));
        }
    }

    @Test
    @DisplayName("基线过滤只允许字面量：多语句/注释/写操作/占位符全部拒绝")
    void baselineFilters_literalsOnly() {
        assertThat(errors(Map.of("baselineFilters", List.of("1=1; DROP TABLE member_info"))))
                .anyMatch(e -> e.contains("被禁止的写法"));
        assertThat(errors(Map.of("baselineFilters", List.of("expense_state = /*10*/ '10'"))))
                .anyMatch(e -> e.contains("被禁止的写法"));
        assertThat(errors(Map.of("baselineFilters", List.of("expense_state = #{state}"))))
                .anyMatch(e -> e.contains("只允许字面量"));
    }

    @Test
    @DisplayName("limitMax 不得超过全局硬上限")
    void limitMax_beyondHardCap_rejected() {
        Map<String, Object> bad = new HashMap<>();
        bad.put("limitDefault", 10);
        bad.put("limitMax", QueryLimits.HARD_MAX_ROWS + 1);
        assertThat(errors(bad)).anyMatch(e -> e.contains("超过全局硬上限"));
    }

    @Test
    @DisplayName("sortable 必须指向存在的度量编码；派生编码不可作为 SQL 排序键")
    void sortable_mustReferenceExistingMeasure() {
        assertThat(errors(Map.of("sortable", List.of("NOT_EXIST"))))
                .anyMatch(e -> e.contains("不允许引用") || e.contains("不存在"));

        // 派生编码加入 sortable：静态规则要求 sortable ⊆ measures，派生编码应被拒
        Map<String, Object> withDerived = new HashMap<>();
        withDerived.put("sortable", List.of("SALES", "AVG_DEMO"));
        withDerived.put("derived", List.of(Map.of("code", "AVG_DEMO", "formula", "SALES / SALES", "scale", 2)));
        assertThat(errors(withDerived)).anyMatch(e -> e.contains("AVG_DEMO"));
    }

    @Test
    @DisplayName("派生公式只允许四则运算 + 已声明编码，引用未知编码即失败")
    void derivedFormula_checkedAtLoadTime() {
        Map<String, Object> bad = new HashMap<>();
        bad.put("derived", List.of(Map.of("code", "RATIO", "formula", "SALES / MISSING_COL", "scale", 2)));
        assertThat(errors(bad)).anyMatch(e -> e.contains("未声明的编码"));

        Map<String, Object> injection = new HashMap<>();
        injection.put("derived", List.of(Map.of("code", "RATIO",
                "formula", "SALES / (SELECT 1)", "scale", 2)));
        assertThat(errors(injection)).anyMatch(e -> e.contains("公式非法"));
    }

    @Test
    @DisplayName("口径缺失/域非法/编码非 UPPER_SNAKE 等元信息问题一次性全部报出")
    void metaErrors_collectedTogether() {
        Map<String, Object> bad = new HashMap<>();
        bad.put("metric", "lower_snake");
        bad.put("domain", "hr");
        bad.put("caliber", "");
        List<String> errs = errors(bad);
        assertThat(errs).anyMatch(e -> e.contains("UPPER_SNAKE"));
        assertThat(errs).anyMatch(e -> e.contains("domain 非法"));
        assertThat(errs).anyMatch(e -> e.contains("caliber"));
    }

    @Test
    @DisplayName("status 缺失或写错一律按 UNCONFIRMED 处理，绝不因笔误放开查询")
    void unknownStatus_fallsBackToUnconfirmed() {
        MetricDefinition missing = minimalDef(Map.of());
        missing = MetricDefinitionParser.fromMap(withoutStatus(Map.of(
                "metric", "T_DEMO2", "title", "t", "domain", "order", "caliber", "c",
                "table", "expense_calendar_info", "grain", "ORDER_HEAD",
                "measures", List.of(Map.of("code", "S", "expr", "NUM(expense_realamt)", "agg", "SUM", "alias", "a")))));
        assertThat(missing.isConfirmed()).isFalse();

        MetricDefinition typo = MetricDefinitionParser.fromMap(Map.of(
                "metric", "T_DEMO3", "title", "t", "domain", "order", "status", "CONFIRM",
                "caliber", "c", "table", "expense_calendar_info", "grain", "ORDER_HEAD",
                "measures", List.of(Map.of("code", "S", "expr", "NUM(expense_realamt)", "agg", "SUM", "alias", "a"))));
        assertThat(typo.isConfirmed()).isFalse();
    }

    private Map<String, Object> withoutStatus(Map<String, Object> base) {
        Map<String, Object> copy = new HashMap<>(base);
        copy.remove("status");
        return copy;
    }
}

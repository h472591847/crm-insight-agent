package com.github.yingli.metrics;

import com.github.yingli.metrics.request.MetricQueryRequest;
import com.github.yingli.metrics.response.MetricResult;
import com.github.yingli.metrics.service.MetricQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName Phase2MetricsReconciliationTest
 * @Description Phase 2 新指标的逐值对账（方案 §8.2 验收口径的延伸）：编译层结果必须与
 * 同区间、同基线的手写 oracle SQL 在真实测试库上完全一致。
 * 区间取 {@link BaselineFixtures} 里的固定历史窗口（2026-06-01 ~ 2026-06-27），
 * 不依赖“当前时间”，结果可反复复核；crmtest 基线数值作二次校验。
 * <p>
 * 2026-10-30 口径变更已落实到断言：年龄改由**阴历**生日 member_yin_birthday 计算
 * （阳历列真实库 100% 为空），因此 AGE_DIST 不再是“只出未知一组”，而是逐分桶与 oracle 比对。
 * 只读查询，不写入任何数据。
 * @Author crm-agent
 */
@SpringBootTest
class Phase2MetricsReconciliationTest {

    private static final String START = BaselineFixtures.windowStart();
    private static final String END = BaselineFixtures.windowEnd();
    /** 左闭右开的右边界：DATE_ADD(END, INTERVAL 1 DAY)，与基线文件一致 */
    private static final String END_EXCLUSIVE = BaselineFixtures.windowEndExclusive();

    @Autowired
    private MetricQueryService metricQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 基线采集自 crmtest；连到其它库时跳过，避免把环境问题误读成口径错误 */
    @BeforeEach
    void requireBaselineDatabase() {
        BaselineFixtures.assumeMatchesCurrentDb(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class));
    }

    private MetricResult queryMember(String metric, List<String> dims) {
        MetricQueryRequest q = new MetricQueryRequest();
        q.setMetric(metric);
        q.setDimensions(dims);
        q.setStartDate(START);
        q.setEndDate(END);
        return metricQueryService.query("member", q);
    }

    private String norm(String v) {
        return v == null ? "" : v.trim();
    }

    /** 把 blank（NULL/空串）归一化为“未填写”，与结果层 nullDimensionLabel 语义对齐 */
    private String blankToUnfilled(String v) {
        return norm(v).isEmpty() ? "未填写" : norm(v);
    }

    @Test
    @DisplayName("ACTIVE_MEMBER：逐门店与 oracle 手写 SQL 一致；合计行=全局去重手机号数（≠分店求和）")
    void activeMember_matchesOracle() {
        MetricResult res = queryMember("ACTIVE_MEMBER", List.of("STATION"));
        assertThat(res.getErrorCode()).isNull();

        List<Map<String, Object>> oracle = jdbcTemplate.queryForList("""
                SELECT expense_stationCode AS station_code,
                       COUNT(DISTINCT expense_mobile) AS active_members,
                       COUNT(*) AS order_count
                FROM expense_calendar_info
                WHERE expense_state = '10' AND del_flag = '0'
                  AND expense_buyDate >= ? AND expense_buyDate < ?
                GROUP BY expense_stationCode
                """, START, END_EXCLUSIVE);
        Map<String, Map<String, Object>> byCode = new HashMap<>();
        for (Map<String, Object> row : oracle) {
            byCode.put(norm((String) row.get("station_code")), row);
        }
        assertThat(res.rowCount()).isEqualTo(oracle.size());

        BigDecimal prevActive = null;
        for (int i = 0; i < res.rowCount(); i++) {
            String code = norm((String) res.value(i, "STATION"));
            Map<String, Object> exp = byCode.get(code);
            assertThat(exp).as("编译结果出现 oracle 没有的门店分组：" + code).isNotNull();
            assertThat(res.value(i, "ACTIVE_MEMBERS"))
                    .as("门店 " + code + " 活跃会员数一致").isEqualTo(exp.get("active_members"));
            assertThat(res.value(i, "ORDERS"))
                    .as("门店 " + code + " 订单数一致").isEqualTo(exp.get("order_count"));
            BigDecimal active = new BigDecimal(String.valueOf(res.value(i, "ACTIVE_MEMBERS")));
            if (prevActive != null) {
                assertThat(prevActive).as("必须按活跃会员数降序").isGreaterThanOrEqualTo(active);
            }
            prevActive = active;
        }

        // 合计行：跨店用户会重复出现在多个分组，全局去重数通常小于分店求和——必须与无 GROUP BY 的 oracle 一致
        Map<String, Object> totals = jdbcTemplate.queryForMap("""
                SELECT COUNT(DISTINCT expense_mobile) AS active_members, COUNT(*) AS order_count
                FROM expense_calendar_info
                WHERE expense_state = '10' AND del_flag = '0'
                  AND expense_buyDate >= ? AND expense_buyDate < ?
                """, START, END_EXCLUSIVE);
        assertThat(res.totalValue("ACTIVE_MEMBERS")).isEqualTo(totals.get("active_members"));
        assertThat(res.totalValue("ORDERS")).isEqualTo(totals.get("order_count"));
        long sumOfGroups = oracle.stream().mapToLong(r -> ((Number) r.get("active_members")).longValue()).sum();
        long globalDistinct = ((Number) totals.get("active_members")).longValue();
        assertThat(globalDistinct).as("全局去重 ≤ 分店求和").isLessThanOrEqualTo(sumOfGroups);
    }

    @Test
    @DisplayName("MEMBER_NEW：按注册门店分组与 oracle 一致；合计=区间内新增总数")
    void memberNew_matchesOracle() {
        MetricResult res = queryMember("MEMBER_NEW", List.of("STORE"));
        assertThat(res.getErrorCode()).isNull();

        List<Map<String, Object>> oracle = jdbcTemplate.queryForList("""
                SELECT member_store AS store_code, COUNT(*) AS new_members
                FROM member_info_view
                WHERE del_flag = '0'
                  AND member_created >= ? AND member_created < ?
                GROUP BY member_store
                """, START, END_EXCLUSIVE);
        Map<String, Long> byStore = new HashMap<>();
        for (Map<String, Object> row : oracle) {
            byStore.merge(norm((String) row.get("store_code")),
                    ((Number) row.get("new_members")).longValue(), Long::sum);
        }
        assertThat(res.rowCount()).isEqualTo(byStore.size());

        for (int i = 0; i < res.rowCount(); i++) {
            String code = norm((String) res.value(i, "STORE"));
            Long exp = byStore.get(code);
            assertThat(exp).as("编译结果出现 oracle 没有的门店：" + code).isNotNull();
            assertThat(res.value(i, "NEW_MEMBERS")).as("门店 " + code + " 新增数一致").isEqualTo(exp);
        }

        long expectTotal = byStore.values().stream().mapToLong(Long::longValue).sum();
        assertThat(((Number) res.totalValue("NEW_MEMBERS")).longValue()).isEqualTo(expectTotal);
        // crmtest 基线：窗口内新增 23,487 人 / 260 个注册门店（旧“样本库 5000 人全部创建于此区间”已作废）
        assertThat(expectTotal).as("区间内新增会员总数=基线")
                .isEqualTo(BaselineFixtures.count("memberNew", "totalNewMembers"));
        assertThat(res.rowCount()).as("注册门店分组数=基线")
                .isEqualTo((int) BaselineFixtures.count("memberNew", "storeGroupCount"));
    }

    @Test
    @DisplayName("MEMBER_LEVEL_DIST：存量分布无时间条件；分组与 oracle 一致，空值归并'未填写'")
    void memberLevelDist_matchesOracle() {
        MetricResult res = queryMember("MEMBER_LEVEL_DIST", List.of("NEW_LEVEL"));
        assertThat(res.getErrorCode()).isNull();

        List<Map<String, Object>> oracle = jdbcTemplate.queryForList("""
                SELECT new_member_level_key AS lvl, COUNT(*) AS members
                FROM member_info_view WHERE del_flag = '0'
                GROUP BY new_member_level_key
                """);
        Map<String, Long> byLevel = new LinkedHashMap<>();
        for (Map<String, Object> row : oracle) {
            byLevel.merge(blankToUnfilled((String) row.get("lvl")),
                    ((Number) row.get("members")).longValue(), Long::sum);
        }
        // NULL 与空串在 MySQL GROUP BY 里是两组，结果层都归并成"未填写"，行数可能多于 oracle 原始组数——按归并后 Map 逐值比
        Map<String, Long> actual = new LinkedHashMap<>();
        for (int i = 0; i < res.rowCount(); i++) {
            String key = blankToUnfilled((String) res.value(i, "NEW_LEVEL"));
            actual.merge(key, ((Number) res.value(i, "MEMBERS")).longValue(), Long::sum);
        }
        assertThat(actual).isEqualTo(byLevel);

        long total = byLevel.values().stream().mapToLong(Long::longValue).sum();
        assertThat(total).as("存量分布总数=全部有效会员")
                .isEqualTo(BaselineFixtures.count("memberOverview", "totalMembers"));
        assertThat(byLevel).containsKey("未填写");
        // crmtest 基线：new_member_level_key 填充 414,905 / 去重 7 值 / 未填写 213,622
        long unfilled = byLevel.getOrDefault("未填写", 0L);
        assertThat(total - unfilled).as("层级已填写人数=基线")
                .isEqualTo(BaselineFixtures.count("memberLevelDist", "filledCount"));
        assertThat(byLevel.size() - 1).as("非空层级取值数=基线")
                .isEqualTo((int) BaselineFixtures.count("memberLevelDist", "distinctValues"));
    }

    @Test
    @DisplayName("AGE_DIST：逐分桶与阴历生日 oracle 一致；分桶求和=会员总数，非未知人数=阴历填充数")
    void ageDist_matchesLunarOracle() {
        MetricResult res = queryMember("AGE_DIST", List.of("AGE_BAND"));
        assertThat(res.getErrorCode()).isNull();

        // oracle 与指标配置同口径：阴历 varchar 先 STR_TO_DATE，再算周岁分桶（只聚合计数，不输出生日原值）
        List<Map<String, Object>> oracle = jdbcTemplate.queryForList("""
                SELECT CASE
                         WHEN member_yin_birthday IS NULL OR TRIM(member_yin_birthday) = ''
                              OR STR_TO_DATE(member_yin_birthday, '%Y-%m-%d') IS NULL
                              OR YEAR(STR_TO_DATE(member_yin_birthday, '%Y-%m-%d')) < 1900 THEN '未知'
                         WHEN TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE()) <= 19
                              THEN '19岁及以下'
                         WHEN TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE()) <= 29
                              THEN '20-29岁'
                         WHEN TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE()) <= 39
                              THEN '30-39岁'
                         WHEN TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE()) <= 49
                              THEN '40-49岁'
                         WHEN TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE()) <= 59
                              THEN '50-59岁'
                         ELSE '60岁及以上' END AS band,
                       COUNT(*) AS members
                FROM member_info_view WHERE del_flag = '0'
                GROUP BY 1
                """);
        Map<String, Long> expected = new LinkedHashMap<>();
        oracle.forEach(row -> expected.merge((String) row.get("band"),
                ((Number) row.get("members")).longValue(), Long::sum));

        Map<String, Long> actual = new LinkedHashMap<>();
        for (int i = 0; i < res.rowCount(); i++) {
            actual.merge(String.valueOf(res.value(i, "AGE_BAND")),
                    ((Number) res.value(i, "MEMBERS")).longValue(), Long::sum);
        }
        assertThat(actual).as("每个年龄分桶逐值一致").isEqualTo(expected);

        // 口径自洽：分桶无遗漏无重叠 → 求和等于有效会员总数
        long total = actual.values().stream().mapToLong(Long::longValue).sum();
        assertThat(total).as("分桶求和=全部有效会员")
                .isEqualTo(BaselineFixtures.count("memberOverview", "totalMembers"));

        long unknown = actual.getOrDefault("未知", 0L);
        assertThat(total - unknown).as("能算出年龄的人数=阴历生日填充数")
                .isEqualTo(BaselineFixtures.count("ageDist", "yinBirthdayFilled"));
        // 阳历列仍全空是本次口径切换的事实依据，固定为断言防止静默回退
        assertThat(((Number) jdbcTemplate.queryForObject(
                "SELECT COUNT(NULLIF(TRIM(member_yang_birthday), '')) FROM member_info_view WHERE del_flag = '0'",
                Long.class)).longValue()).as("阳历生日仍 100% 为空").isZero();
        // “未知”仍占绝大多数（阴历覆盖率约 0.44%）属数据覆盖限制，不是口径错误
        assertThat(unknown).as("未知占比超过 99%（数据覆盖限制）")
                .isGreaterThan((long) (total * 0.99));
    }

    @Test
    @DisplayName("ANNUAL_AVG_CONSUMPTION：逐门店金额/去重人数与 oracle 一致，派生=金额/人数(HALF_UP 2)")
    void annualAvg_matchesOracle() {
        MetricResult res = queryMember("ANNUAL_AVG_CONSUMPTION", List.of("STATION"));
        assertThat(res.getErrorCode()).isNull();

        List<Map<String, Object>> oracle = jdbcTemplate.queryForList("""
                SELECT expense_stationCode AS station_code,
                       COALESCE(SUM(CAST(NULLIF(TRIM(expense_realamt), '') AS DECIMAL(20, 2))), 0) AS total_consumption,
                       COUNT(DISTINCT expense_mobile) AS active_members
                FROM expense_calendar_info
                WHERE expense_state = '10' AND del_flag = '0'
                  AND expense_buyDate >= ? AND expense_buyDate < ?
                GROUP BY expense_stationCode
                """, START, END_EXCLUSIVE);
        Map<String, Map<String, Object>> byCode = new HashMap<>();
        for (Map<String, Object> row : oracle) {
            byCode.put(norm((String) row.get("station_code")), row);
        }
        assertThat(res.rowCount()).isEqualTo(oracle.size());

        for (int i = 0; i < res.rowCount(); i++) {
            String code = norm((String) res.value(i, "STATION"));
            Map<String, Object> exp = byCode.get(code);
            assertThat(exp).as("编译结果出现 oracle 没有的门店分组：" + code).isNotNull();
            BigDecimal consumption = (BigDecimal) res.value(i, "TOTAL_CONSUMPTION");
            long members = ((Number) res.value(i, "ACTIVE_MEMBERS")).longValue();
            assertThat(consumption).as("门店 " + code + " 消费额逐值一致")
                    .isEqualByComparingTo((BigDecimal) exp.get("total_consumption"));
            assertThat(members).as("门店 " + code + " 消费人数逐值一致")
                    .isEqualTo(((Number) exp.get("active_members")).longValue());
            BigDecimal expectAvg = members <= 0 ? BigDecimal.ZERO
                    : consumption.divide(BigDecimal.valueOf(members), 2, RoundingMode.HALF_UP);
            assertThat((BigDecimal) res.value(i, "AVG_CONSUMPTION_PER_MEMBER"))
                    .as("门店 " + code + " 单客年均一致").isEqualByComparingTo(expectAvg);
        }

        Map<String, Object> totals = jdbcTemplate.queryForMap("""
                SELECT COALESCE(SUM(CAST(NULLIF(TRIM(expense_realamt), '') AS DECIMAL(20, 2))), 0) AS total_consumption,
                       COUNT(DISTINCT expense_mobile) AS active_members
                FROM expense_calendar_info
                WHERE expense_state = '10' AND del_flag = '0'
                  AND expense_buyDate >= ? AND expense_buyDate < ?
                """, START, END_EXCLUSIVE);
        assertThat((BigDecimal) res.totalValue("TOTAL_CONSUMPTION"))
                .isEqualByComparingTo((BigDecimal) totals.get("total_consumption"));
        assertThat(res.totalValue("ACTIVE_MEMBERS")).isEqualTo(totals.get("active_members"));
    }
}

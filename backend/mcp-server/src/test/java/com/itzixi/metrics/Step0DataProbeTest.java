package com.itzixi.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName Step0DataProbeTest
 * @Description Phase 2 新指标开发前的只读数据体检（AGENTS.md 规则：业务规则不能靠猜）。
 * 探测内容：会员层级六字段/注册门店/阴历生日/创建时间/member_info 基线，
 * 以及订单表 expense_mobile 的去重可用性——这些都是新增指标 YAML 设计的数据依据。
 * 全部为只读聚合，不输出任何个人级数据；结果打印到控制台供记录 DEVELOPMENT_LOG。
 * <p>
 * 生日相关探测一律只看<b>粗粒度</b>结论（填充数、年份区间、分桶计数），不得 SELECT 生日原值：
 * 生日属于个人信息，控制台与日志都不该出现（AGENTS §八）。
 * 2026-10-30 口径：年龄改用 {@code member_yin_birthday}（阳历列真实库 100% 为空），
 * 该列为 varchar，探测需先 STR_TO_DATE，与指标配置的 expr 保持同一写法。
 * @Author crm-agent
 */
@SpringBootTest
class Step0DataProbeTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Map<String, Object> one(String sql, Object... args) {
        return jdbcTemplate.queryForMap(sql, args);
    }

    private void printRow(String title, Map<String, Object> row) {
        System.out.println("[Step0] " + title + " => " + row);
    }

    @Test
    @DisplayName("member_info 基线：总数、层级六字段与注册门店的填充率/去重数")
    void memberInfoBaseline() {
        printRow("会员总数(del_flag=0)", one("""
                SELECT COUNT(*) AS total FROM member_info WHERE del_flag = '0'
                """));
        // 层级六字段：填充率与基数决定"是否值得作为可选维度"（2026-10-29 口径：全部统计）
        for (String col : new String[]{"customer_level", "new_member_level_key", "member_importance",
                "consume_hierarchy", "half_year_level", "total_year_level"}) {
            printRow("层级字段 " + col, one(String.format("""
                    SELECT COUNT(NULLIF(TRIM(%1$s), '')) AS filled,
                           COUNT(DISTINCT NULLIF(TRIM(%1$s), '')) AS distinct_values
                    FROM member_info WHERE del_flag = '0'
                    """, col)));
        }
        printRow("注册门店 member_store", one("""
                SELECT COUNT(NULLIF(TRIM(member_store), '')) AS filled,
                       COUNT(DISTINCT NULLIF(TRIM(member_store), '')) AS distinct_stores,
                       COUNT(member_stationName) AS name_filled
                FROM member_info WHERE del_flag = '0'
                """));
        // 租户维度已于 2026-10-30 取消，此处仅留数据证据（真实库 member_tenant 全空）
        printRow("租户 member_tenant（能力已取消，仅留证据）", one("""
                SELECT COUNT(DISTINCT member_tenant) AS tenants FROM member_info WHERE del_flag = '0'
                """));
    }

    @Test
    @DisplayName("生日/年龄列：阴历生日空值率、异常年份与年龄分桶可行性（只看聚合，不输出生日原值）")
    void birthdayFeasibility() {
        // member_yin_birthday 是 varchar，必须先 STR_TO_DATE 再取年份；
        // 只输出年份区间，不输出 MIN/MAX 原值，避免生日进入控制台与日志。
        printRow("阴历生日填充情况（年份区间，不含原值）", one("""
                SELECT COUNT(NULLIF(TRIM(member_yin_birthday), ''))                                AS yin_filled,
                       SUM(CASE WHEN member_yin_birthday IS NULL OR TRIM(member_yin_birthday) = ''
                                THEN 1 ELSE 0 END)                                                 AS yin_blank,
                       SUM(CASE WHEN NULLIF(TRIM(member_yin_birthday), '') IS NOT NULL
                                 AND STR_TO_DATE(member_yin_birthday, '%Y-%m-%d') IS NULL
                                THEN 1 ELSE 0 END)                                                 AS unparsable,
                       MIN(YEAR(STR_TO_DATE(member_yin_birthday, '%Y-%m-%d')))                      AS min_year,
                       MAX(YEAR(STR_TO_DATE(member_yin_birthday, '%Y-%m-%d')))                      AS max_year,
                       SUM(CASE WHEN YEAR(STR_TO_DATE(member_yin_birthday, '%Y-%m-%d')) < 1900
                                THEN 1 ELSE 0 END)                                                 AS year_before_1900,
                       SUM(CASE WHEN STR_TO_DATE(member_yin_birthday, '%Y-%m-%d') > CURDATE()
                                THEN 1 ELSE 0 END)                                                 AS future_dated
                FROM member_info WHERE del_flag = '0'
                """));
        printRow("阳历生日填充情况（口径切换的事实依据）", one("""
                SELECT COUNT(NULLIF(TRIM(member_yang_birthday), '')) AS yang_filled,
                       COUNT(*)                                     AS total
                FROM member_info WHERE del_flag = '0'
                """));
        // AGE_DIST 的 CASE 分桶原型：与指标 expr 同写法（STR_TO_DATE + CURDATE），验证语法与分布合理性
        printRow("阴历生日分桶预览(相对 CURDATE)", one("""
                SELECT SUM(CASE WHEN member_yin_birthday IS NULL OR TRIM(member_yin_birthday) = ''
                                     OR STR_TO_DATE(member_yin_birthday, '%Y-%m-%d') IS NULL
                                     OR YEAR(STR_TO_DATE(member_yin_birthday, '%Y-%m-%d')) < 1900
                                THEN 1 ELSE 0 END) AS unknown_band,
                       SUM(CASE WHEN TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE()) <= 19
                                THEN 1 ELSE 0 END) AS le_19,
                       SUM(CASE WHEN TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE()) BETWEEN 20 AND 29
                                THEN 1 ELSE 0 END) AS b_20_29,
                       SUM(CASE WHEN TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE()) BETWEEN 30 AND 39
                                THEN 1 ELSE 0 END) AS b_30_39,
                       SUM(CASE WHEN TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE()) BETWEEN 40 AND 49
                                THEN 1 ELSE 0 END) AS b_40_49,
                       SUM(CASE WHEN TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE()) BETWEEN 50 AND 59
                                THEN 1 ELSE 0 END) AS b_50_59,
                       SUM(CASE WHEN TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE()) >= 60
                                THEN 1 ELSE 0 END) AS ge_60
                FROM member_info WHERE del_flag = '0'
                """));
        // 对照：age 列与阴历生日计算的一致性差距（印证"age 不准"的产品结论）
        printRow("age列 vs 阴历生日计算偏差", one("""
                SELECT COUNT(*) AS both_filled,
                       SUM(CASE WHEN ABS(COALESCE(age, 0)
                                    - COALESCE(TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE()), 0)) > 1
                           THEN 1 ELSE 0 END) AS mismatch_gt_1y
                FROM member_info
                WHERE del_flag = '0' AND age IS NOT NULL
                  AND NULLIF(TRIM(member_yin_birthday), '') IS NOT NULL
                  AND STR_TO_DATE(member_yin_birthday, '%Y-%m-%d') IS NOT NULL
                """));
    }

    @Test
    @DisplayName("member_created：新增会员指标的时间覆盖")
    void memberCreatedCoverage() {
        // 锚点用 CURDATE 派生，不写死“距今 18/3 个月”的字面日期，否则该探测会随日历漂移
        printRow("member_created 覆盖", one("""
                SELECT MIN(member_created) AS min_created, MAX(member_created) AS max_created,
                       SUM(CASE WHEN member_created >= DATE_SUB(CURDATE(), INTERVAL 18 MONTH) THEN 1 ELSE 0 END) AS last_18m,
                       SUM(CASE WHEN member_created >= DATE_SUB(CURDATE(), INTERVAL 3 MONTH) THEN 1 ELSE 0 END)   AS last_3m
                FROM member_info WHERE del_flag = '0'
                """));
    }

    @Test
    @DisplayName("订单表 expense_mobile：活跃/年均消费按手机号去重的数据可用性")
    void expenseMobileDedupUsability() {
        // 只输出计数，不回显任何手机号明文
        Map<String, Object> row = one("""
                SELECT COUNT(*) AS orders,
                       SUM(CASE WHEN expense_mobile IS NULL OR TRIM(expense_mobile) = '' THEN 1 ELSE 0 END) AS mobile_blank,
                       COUNT(DISTINCT NULLIF(TRIM(expense_mobile), '')) AS distinct_mobiles
                FROM expense_calendar_info WHERE expense_state = '10' AND del_flag = '0'
                """);
        printRow("订单头 mobile 概况", row);
        assertThat(((Number) row.get("mobile_blank")).longValue())
                .as("若存在 mobile 为空的订单，活跃会员口径需产品再确认；先如实记录").isZero();
        printRow("样本活跃区间内去重手机号（基线窗口 " + BaselineFixtures.windowStart()
                + "~" + BaselineFixtures.windowEnd() + "）", one("""
                SELECT COUNT(DISTINCT NULLIF(TRIM(expense_mobile), '')) AS distinct_mobiles
                FROM expense_calendar_info
                WHERE expense_state = '10' AND del_flag = '0'
                  AND expense_buyDate >= ? AND expense_buyDate < ?
                """, BaselineFixtures.windowStart(), BaselineFixtures.windowEndExclusive()));
    }
}

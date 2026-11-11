package com.github.yingli.mapper;

import com.github.yingli.metrics.BaselineFixtures;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName MemberInfoMapperIntegrationTest
 * @Description 连真实测试库只读执行 MemberInfoMapper 的自定义查询，验证注解 SQL 迁入
 *              resources/mapper/MemberInfoMapper.xml 后语句仍能正确绑定并执行。
 * <p>
 * 断言只做"结构正确 + 前缀/包含语义成立"级别校验，不硬编码业务数字：
 * 关键词取自库中真实门店名/手机号，库中没有可用样本时跳过而不是误报失败。
 * @Author crm-agent
 */
@SpringBootTest
class MemberInfoMapperIntegrationTest {

    /** 与 XML 中 LIMIT 20 保持一致 */
    private static final int LIMIT = 20;

    @Autowired
    private MemberInfoMapper memberInfoMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 连到其它库时跳过，避免把"没有样本"误报成语义错误 */
    @BeforeEach
    void requireBaselineDatabase() {
        BaselineFixtures.assumeMatchesCurrentDb(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class));
    }

    @Test
    @DisplayName("门店前缀匹配：XML 语句可执行，返回编码/名称/会员数且全部命中前缀")
    void guessStationByPrefix_shouldExecuteAndMatchPrefix() {
        String stationName = firstStationName();
        Assumptions.assumeTrue(stationName != null && stationName.length() >= 4,
                "测试库中没有长度足够的门店名样本，跳过前缀匹配校验");
        String prefix = stationName.substring(0, 4);

        List<Map<String, Object>> rows = memberInfoMapper.guessStationByPrefix(prefix);

        assertThat(rows).isNotEmpty();
        assertThat(rows.size()).isLessThanOrEqualTo(LIMIT);
        for (Map<String, Object> row : rows) {
            assertThat(row).containsKeys("stationCode", "stationName", "memberCount");
            assertThat(row.get("stationName").toString()).startsWith(prefix);
            assertThat(((Number) row.get("memberCount")).longValue()).isPositive();
        }
    }

    @Test
    @DisplayName("门店包含匹配：XML 语句可执行，返回结果均包含关键词")
    void guessStationByContains_shouldExecuteAndMatchKeyword() {
        String stationName = firstStationName();
        Assumptions.assumeTrue(stationName != null && stationName.length() >= 3,
                "测试库中没有门店名样本，跳过包含匹配校验");
        String keyword = stationName.substring(stationName.length() - 3);

        List<Map<String, Object>> rows = memberInfoMapper.guessStationByContains(keyword);

        assertThat(rows).isNotEmpty();
        assertThat(rows.size()).isLessThanOrEqualTo(LIMIT);
        for (Map<String, Object> row : rows) {
            assertThat(row).containsKeys("stationCode", "stationName", "memberCount");
            assertThat(row.get("stationName").toString()).contains(keyword);
            assertThat(((Number) row.get("memberCount")).longValue()).isPositive();
        }
    }

    @Test
    @DisplayName("会员手机号前缀匹配：XML 语句可执行，返回手机号均命中前缀")
    void guessMemberByMobile_shouldExecuteAndMatchPrefix() {
        String mobile = firstMobile();
        Assumptions.assumeTrue(mobile != null && mobile.length() >= 3,
                "测试库中没有手机号样本，跳过手机号前缀匹配校验");
        String prefix = mobile.substring(0, 5);

        List<Map<String, Object>> rows = memberInfoMapper.guessMemberByMobile(prefix);

        assertThat(rows).isNotEmpty();
        assertThat(rows.size()).isLessThanOrEqualTo(LIMIT);
        for (Map<String, Object> row : rows) {
            // MyBatis 的 Map 结果默认不写入 null 列；stationCode/stationName/memberName 可能为空，
            // 只有手机号本身经 WHERE 过滤必然非空
            assertThat(row).containsKey("memberMobile");
            assertThat(row.get("memberMobile").toString()).startsWith(prefix);
        }
    }

    /** 取一个有效门店名样本（只读） */
    private String firstStationName() {
        List<String> names = jdbcTemplate.queryForList(
                "SELECT member_allot_stationName FROM member_info_view "
                        + "WHERE del_flag = '0' AND member_allot_stationName IS NOT NULL LIMIT 1",
                String.class);
        return names.isEmpty() ? null : names.get(0);
    }

    /** 取一个有效手机号样本（只读） */
    private String firstMobile() {
        List<String> mobiles = jdbcTemplate.queryForList(
                "SELECT member_mobile FROM member_info_view "
                        + "WHERE del_flag = '0' AND member_mobile IS NOT NULL AND member_mobile <> '' LIMIT 1",
                String.class);
        return mobiles.isEmpty() ? null : mobiles.get(0);
    }
}

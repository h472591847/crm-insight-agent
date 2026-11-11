package com.github.yingli.mapper;

import com.github.yingli.adhoc.request.FilterSpec;
import com.github.yingli.metrics.BaselineFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** sys_office 固定 SQL 真库冒烟：验证 CRM 同口径条件可执行，不硬编码会随测试数据变化的数量。 */
@SpringBootTest
class OfficeMapperIntegrationTest {

    @Autowired
    private OfficeMapper mapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void requireBaselineDatabase() {
        BaselineFixtures.assumeMatchesCurrentDb(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class));
    }

    @Test
    @DisplayName("office.id=1：机构候选、门店集合与列表上限均可真实执行")
    void officeStoreResolutionExecutesAgainstRealDatabase() {
        Map<String, Object> office = mapper.findOfficeById("1");
        assertThat(office).isNotNull();
        assertThat(office.get("officeName")).isNotNull();

        long count = mapper.countOfficeStoreCodes("1");
        List<String> codes = mapper.listOfficeStoreCodes("1", FilterSpec.MAX_IN_VALUES);
        assertThat(count).isPositive();
        assertThat(codes).hasSize((int) Math.min(count, FilterSpec.MAX_IN_VALUES));
        assertThat(codes).doesNotHaveDuplicates().allMatch(code -> code != null && code.length() == 8);

        Long memberCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM member_info_view
                WHERE del_flag = '0' AND member_allot_store IN (
                    SELECT a.HD_ID FROM sys_office a LEFT JOIN sys_office o ON o.id = a.id
                    WHERE a.del_flag = '0' AND a.HD_ID IS NOT NULL AND a.HD_ID <> ''
                      AND (a.id = 1 OR o.parent_ids LIKE '%,1,%')
                      AND a.type IN ('6','11','22','28') AND LENGTH(a.HD_ID) = 8)
                """, Long.class);
        assertThat(memberCount).isNotNull().isNotNegative();
    }

    @Test
    @DisplayName("门店兜底口径：只暴露 6/11/22/28 的 8 位门店，渠道店/要客部/新媒体查不到")
    void fallbackStoreScopeExcludesNonRetailTypes() {
        List<Map<String, Object>> stores = mapper.findFallbackStoresByExactNames(
                List.of("哈尔滨尚志世纪店", "哈尔滨尚志世纪店店", "哈尔滨尚志世纪店门店"));
        assertThat(stores).hasSize(1);
        Map<String, Object> store = stores.get(0);
        assertThat(String.valueOf(store.get("stationName"))).isEqualTo("哈尔滨尚志世纪店");
        assertThat(String.valueOf(store.get("stationCode"))).hasSize(8);
        assertThat(String.valueOf(store.get("officeType"))).isIn("6", "11", "22", "28");

        // type=18 渠道店、type=13 要客部、type=14 新媒体都不在兜底口径内
        assertThat(mapper.findFallbackStoresByExactNames(List.of("竹叶青长江路店"))).isEmpty();
        assertThat(mapper.findFallbackStoresByExactNames(List.of("黑东二要客部"))).isEmpty();
        assertThat(mapper.findFallbackStoresByExactNames(List.of("新媒体"))).isEmpty();
    }

    @Test
    @DisplayName("订单存在性查询：返回 1/0 且对不存在的门店编码返回 0")
    void orderExistenceQueryExecutes() {
        long absent = mapper.countOrdersByStationCode("99999999");
        assertThat(absent).isZero();
        long existing = mapper.countOrdersByStationCode("51010211");
        assertThat(existing).isBetween(0L, 1L);
    }
}

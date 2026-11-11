package com.itzixi.mcp.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itzixi.mapper.MemberInfoMapper;
import com.itzixi.mapper.OfficeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @ClassName GuessToolTest
 * @Description guessStation 多门店解析的单元测试：每个关键词独立解析、精确优先、跨城市同名路段必须追问。
 * @Author crm-agent
 */
class GuessToolTest {

    private MemberInfoMapper mapper;
    private OfficeMapper officeMapper;
    private GuessTool tool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mapper = mock(MemberInfoMapper.class);
        officeMapper = mock(OfficeMapper.class);
        tool = new GuessTool(mapper, officeMapper, objectMapper);
        when(mapper.guessStationByExact(anyString())).thenReturn(List.of());
        when(mapper.guessStationByPrefix(anyString())).thenReturn(List.of());
        when(mapper.guessStationByContains(anyString())).thenReturn(List.of());
        when(officeMapper.findFallbackStoresByExactNames(anyList())).thenReturn(List.of());
        when(officeMapper.findFallbackStoresByName(anyString(), anyString())).thenReturn(List.of());
    }

    @Test
    @DisplayName("一句话两个门店：分别解析成各自编码，不丢店也不合并")
    void multipleKeywordsResolvedIndependently() throws Exception {
        when(mapper.guessStationByExact("哈市长江路"))
                .thenReturn(List.of(station("01010601", "哈市长江路店", 1248)));
        when(mapper.guessStationByExact("鸿翔路"))
                .thenReturn(List.of(station("01010609", "哈市鸿翔路店", 860)));

        JsonNode result = objectMapper.readTree(tool.guessStation(List.of("哈市长江路", "鸿翔路")));

        assertThat(result.get("keywordCount").asInt()).isEqualTo(2);
        JsonNode results = result.get("results");
        assertThat(results).hasSize(2);
        assertThat(results.get(0).get("keyword").asText()).isEqualTo("哈市长江路");
        assertThat(results.get(0).get("matchedBy").asText()).isEqualTo("exact");
        assertThat(results.get(0).get("candidates").get(0).get("stationCode").asText()).isEqualTo("01010601");
        assertThat(results.get(1).get("keyword").asText()).isEqualTo("鸿翔路");
        assertThat(results.get(1).get("candidates").get(0).get("stationCode").asText()).isEqualTo("01010609");
        assertThat(result.get("actionHint").asText()).startsWith("all_unique");
    }

    @Test
    @DisplayName("单元素含顿号时按分隔符拆成两个门店")
    void splitsSeparatorsInsideSingleElement() throws Exception {
        when(mapper.guessStationByExact("哈市长江路"))
                .thenReturn(List.of(station("01010601", "哈市长江路店", 1248)));
        when(mapper.guessStationByExact("鸿翔路"))
                .thenReturn(List.of(station("01010609", "哈市鸿翔路店", 860)));

        JsonNode result = objectMapper.readTree(tool.guessStation(List.of("哈市长江路、鸿翔路")));

        assertThat(result.get("keywordCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("精确命中后不再走前缀/包含，避免把其他城市同名路段带进来")
    void exactMatchStopsBeforeContains() {
        when(mapper.guessStationByExact("哈市长江路"))
                .thenReturn(List.of(station("01010601", "哈市长江路店", 1248)));

        tool.guessStation(List.of("哈市长江路"));

        verify(mapper).guessStationByExact("哈市长江路");
        verify(mapper, never()).guessStationByPrefix("哈市长江路");
        verify(mapper, never()).guessStationByContains("哈市长江路");
    }

    @Test
    @DisplayName("只给路段名且跨城市命中多家时必须返回 multiple_matches，由模型追问")
    void bareStreetNameAcrossCitiesNeedsClarify() throws Exception {
        when(mapper.guessStationByContains("长江路")).thenReturn(List.of(
                station("01010601", "哈市长江路店", 1248),
                station("21010601", "辽宁海城区长江路店", 892)));

        JsonNode result = objectMapper.readTree(tool.guessStation(List.of("长江路")));

        JsonNode item = result.get("results").get(0);
        assertThat(item.get("count").asInt()).isEqualTo(2);
        assertThat(item.get("matchedBy").asText()).isEqualTo("contains");
        assertThat(item.get("actionHint").asText()).startsWith("multiple_matches");
        assertThat(result.get("actionHint").asText()).startsWith("needs_clarify");
    }

    @Test
    @DisplayName("关键词全部过短时返回 invalid_input，不查库")
    void tooShortKeywordsRejected() throws Exception {
        JsonNode result = objectMapper.readTree(tool.guessStation(List.of("长", "")));

        assertThat(result.get("actionHint").asText()).startsWith("invalid_input");
        verify(mapper, never()).guessStationByExact(anyString());
    }

    @Test
    @DisplayName("会员表命中时不触发机构表兜底，避免多查一次")
    void memberTableHitSkipsOfficeFallback() {
        when(mapper.guessStationByExact("哈市长江路"))
                .thenReturn(List.of(station("01010601", "哈市长江路店", 1248)));

        tool.guessStation(List.of("哈市长江路"));

        verify(officeMapper, never()).findFallbackStoresByExactNames(anyList());
        verify(officeMapper, never()).findFallbackStoresByName(anyString(), anyString());
        verify(officeMapper, never()).countOrdersByStationCode(anyString());
    }

    @Test
    @DisplayName("会员表查不到但机构表有该门店：返回 matchedBy=office_only、0 会员，并标明有销售记录")
    void officeFallbackUniqueWithOrders() throws Exception {
        when(officeMapper.findFallbackStoresByExactNames(List.of("哈尔滨尚志世纪店", "哈尔滨尚志世纪店店", "哈尔滨尚志世纪店门店")))
                .thenReturn(List.of(office("51010211", "哈尔滨尚志世纪店", 11)));
        when(officeMapper.countOrdersByStationCode("51010211")).thenReturn(1L);

        JsonNode result = objectMapper.readTree(tool.guessStation(List.of("哈尔滨尚志世纪店")));

        JsonNode item = result.get("results").get(0);
        assertThat(item.get("matchedBy").asText()).isEqualTo("office_only");
        assertThat(item.get("count").asInt()).isEqualTo(1);
        assertThat(item.get("actionHint").asText()).startsWith("unique_match");
        JsonNode candidate = item.get("candidates").get(0);
        assertThat(candidate.get("stationCode").asText()).isEqualTo("51010211");
        assertThat(candidate.get("memberCount").asInt()).isZero();
        assertThat(candidate.get("hasOrders").asBoolean()).isTrue();
        assertThat(result.get("actionHint").asText()).startsWith("all_unique");
    }

    @Test
    @DisplayName("兜底命中但该门店没有订单：hasOrders=false，提示仍为门店存在但没有会员记录")
    void officeFallbackUniqueWithoutOrders() throws Exception {
        when(officeMapper.findFallbackStoresByExactNames(List.of("石家庄福强路店", "石家庄福强路店店", "石家庄福强路店门店")))
                .thenReturn(List.of(office("09010101", "石家庄福强路店", 22)));
        when(officeMapper.countOrdersByStationCode("09010101")).thenReturn(0L);

        JsonNode result = objectMapper.readTree(tool.guessStation(List.of("石家庄福强路店")));

        JsonNode candidate = result.get("results").get(0).get("candidates").get(0);
        assertThat(candidate.get("hasOrders").asBoolean()).isFalse();
        assertThat(candidate.get("memberCount").asInt()).isZero();
    }

    @Test
    @DisplayName("兜底多候选：返回 multiple_matches 要求追问，不给 hasOrders 逐家查订单")
    void officeFallbackMultipleCandidatesRequireClarify() throws Exception {
        when(officeMapper.findFallbackStoresByExactNames(anyList())).thenReturn(List.of());
        when(officeMapper.findFallbackStoresByName("尚志", "尚志%"))
                .thenReturn(List.of(office("51010203", "哈尔滨尚志店", 11),
                        office("51010219", "哈尔滨尚志双桥店", 11)));

        JsonNode result = objectMapper.readTree(tool.guessStation(List.of("尚志")));

        JsonNode item = result.get("results").get(0);
        assertThat(item.get("matchedBy").asText()).isEqualTo("office_only");
        assertThat(item.get("count").asInt()).isEqualTo(2);
        assertThat(item.get("actionHint").asText()).startsWith("multiple_matches");
        assertThat(item.get("candidates").get(0).get("memberCount").asInt()).isZero();
        assertThat(item.get("candidates").get(0).get("note").asText()).contains("无会员分配记录");
        assertThat(item.get("candidates").get(0).has("hasOrders")).isFalse();
        assertThat(result.get("actionHint").asText()).startsWith("needs_clarify");
        verify(officeMapper, never()).countOrdersByStationCode(anyString());
    }

    @Test
    @DisplayName("渠道店/要客部/新媒体不在兜底口径内：机构表也查不到时仍返回 no_match")
    void excludedOfficeTypesStayNoMatch() throws Exception {
        when(officeMapper.findFallbackStoresByExactNames(anyList())).thenReturn(List.of());
        when(officeMapper.findFallbackStoresByName("竹叶青长江路店", "竹叶青长江路店%")).thenReturn(List.of());
        when(officeMapper.findFallbackStoresByName("竹叶青长江路店", "%竹叶青长江路店%")).thenReturn(List.of());

        JsonNode result = objectMapper.readTree(tool.guessStation(List.of("竹叶青长江路店")));

        JsonNode item = result.get("results").get(0);
        assertThat(item.get("count").asInt()).isZero();
        assertThat(item.get("matchedBy").asText()).isEqualTo("none");
        assertThat(item.get("actionHint").asText()).startsWith("no_match");
        assertThat(result.get("actionHint").asText()).startsWith("partial_no_match");
    }

    private Map<String, Object> station(String code, String name, long memberCount) {
        return Map.of("stationCode", code, "stationName", name, "memberCount", memberCount);
    }

    private Map<String, Object> office(String code, String name, int type) {
        return Map.of("stationCode", code, "stationName", name, "officeType", type);
    }
}

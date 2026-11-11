package com.itzixi.mcp.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itzixi.mapper.OfficeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OfficeStoreToolTest {

    private OfficeMapper mapper;
    private ObjectMapper objectMapper;
    private OfficeStoreTool tool;

    @BeforeEach
    void setUp() {
        mapper = mock(OfficeMapper.class);
        objectMapper = new ObjectMapper();
        tool = new OfficeStoreTool(mapper, objectMapper);
    }

    @Test
    @DisplayName("officeId 与 officeName 必须且只能传一个")
    void exactlyOneSelectorRequired() throws Exception {
        JsonNode neither = read(tool.resolveOfficeStores(null, null));
        assertThat(neither.get("status").asText()).isEqualTo("invalid_input");

        JsonNode both = read(tool.resolveOfficeStores("1", "小酒喔总部"));
        assertThat(both.get("status").asText()).isEqualTo("invalid_input");
    }

    @Test
    @DisplayName("按 ID 解析成功：返回机构信息与可直接用于 member_allot_store IN 的 storeCodes")
    void resolveByIdReturnsInReadyStoreCodes() throws Exception {
        when(mapper.findOfficeById("10")).thenReturn(office("10", "测试门店", 6));
        when(mapper.countOfficeStoreCodes("10")).thenReturn(2L);
        when(mapper.listOfficeStoreCodes("10", OfficeStoreTool.MAX_STORE_CODES))
                .thenReturn(List.of("04010112", "04010113"));

        JsonNode result = read(tool.resolveOfficeStores("10", null));

        assertThat(result.get("status").asText()).isEqualTo("resolved");
        assertThat(result.get("officeId").asText()).isEqualTo("10");
        assertThat(result.get("officeName").asText()).isEqualTo("测试门店");
        assertThat(result.get("officeType").asInt()).isEqualTo(6);
        assertThat(result.get("storeCount").asInt()).isEqualTo(2);
        assertThat(result.get("storeCodes")).hasSize(2);
        assertThat(result.get("storeCodes").get(0).asText()).isEqualTo("04010112");
        assertThat(result.get("truncated").asBoolean()).isFalse();
        assertThat(result.get("actionHint").asText()).contains("member_allot_store", "IN", "storeCodes");
    }

    @Test
    @DisplayName("总部级 369 个可统计门店在 500 上限内：返回 resolved 且带回全部编码（排除渠道店/要客部/新媒体）")
    void headquarter375StoresResolved() throws Exception {
        List<String> codes = java.util.stream.IntStream.range(0, 369)
                .mapToObj(i -> String.format("010%05d", i)).toList();
        when(mapper.findOfficeById("1")).thenReturn(office("1", "小酒喔总部", 1));
        when(mapper.countOfficeStoreCodes("1")).thenReturn(369L);
        when(mapper.listOfficeStoreCodes("1", OfficeStoreTool.MAX_STORE_CODES)).thenReturn(codes);

        JsonNode result = read(tool.resolveOfficeStores("1", null));

        assertThat(result.get("status").asText()).isEqualTo("resolved");
        assertThat(result.get("storeCount").asInt()).isEqualTo(369);
        assertThat(result.get("maxStoreCodes").asInt()).isEqualTo(500);
        assertThat(result.get("storeCodes")).hasSize(369);
        assertThat(result.get("storeCodes").get(0).asText()).isEqualTo("01000000");
        assertThat(result.get("truncated").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("超过 IN 的 500 值上限：明确拒绝，不截断、不返回部分门店编码")
    void moreThanMaxStoresRejectedWithoutPartialCodes() throws Exception {
        when(mapper.findOfficeById("1")).thenReturn(office("1", "小酒喔总部", 1));
        when(mapper.countOfficeStoreCodes("1")).thenReturn(501L);

        JsonNode result = read(tool.resolveOfficeStores("1", null));

        assertThat(result.get("status").asText()).isEqualTo("too_many_stores");
        assertThat(result.get("storeCount").asInt()).isEqualTo(501);
        assertThat(result.get("maxStoreCodes").asInt()).isEqualTo(500);
        assertThat(result.get("storeCodes")).isEmpty();
        assertThat(result.get("truncated").asBoolean()).isFalse();
        assertThat(result.get("actionHint").asText()).contains("500", "缩小", "不得");
        verify(mapper, never()).listOfficeStoreCodes(anyString(), anyInt());
    }

    @Test
    @DisplayName("名称多候选：返回候选，不自行选择，也不查询门店编码")
    void multipleNameCandidatesRequireClarification() throws Exception {
        when(mapper.findOfficesByExactNames(List.of("小酒喔", "小酒喔店", "小酒喔门店")))
                .thenReturn(List.of());
        when(mapper.findOfficeCandidatesByName("小酒喔", "小酒喔%"))
                .thenReturn(List.of(office("1", "小酒喔总部", 1), office("2", "小酒喔分部", 1)));

        JsonNode result = read(tool.resolveOfficeStores(null, "小酒喔"));

        assertThat(result.get("status").asText()).isEqualTo("needs_clarification");
        assertThat(result.get("matchedBy").asText()).isEqualTo("prefix");
        assertThat(result.get("candidates")).hasSize(2);
        verify(mapper, never()).countOfficeStoreCodes(anyString());
    }

    @Test
    @DisplayName("机构全名精确命中且类型为总部（非门店）：直接解析出下级门店，不追问")
    void exactHeadquarterNameResolvesWithoutClarification() throws Exception {
        List<String> codes = java.util.stream.IntStream.range(0, 369)
                .mapToObj(i -> String.format("010%05d", i)).toList();
        when(mapper.findOfficesByExactNames(List.of("小酒喔总部", "小酒喔总部店", "小酒喔总部门店")))
                .thenReturn(List.of(office("1", "小酒喔总部", 2)));
        when(mapper.countOfficeStoreCodes("1")).thenReturn(369L);
        when(mapper.listOfficeStoreCodes("1", OfficeStoreTool.MAX_STORE_CODES)).thenReturn(codes);

        JsonNode result = read(tool.resolveOfficeStores(null, "小酒喔总部"));

        assertThat(result.get("status").asText()).isEqualTo("resolved");
        assertThat(result.get("officeType").asInt()).isEqualTo(2);
        assertThat(result.get("storeCount").asInt()).isEqualTo(369);
        assertThat(result.get("storeCodes")).hasSize(369);
        assertThat(result.has("candidates")).isFalse();
        verify(mapper, never()).findOfficeCandidatesByName(anyString(), anyString());
    }

    @Test
    @DisplayName("精确未命中才降级；加“店/门店”后缀的确定变形命中即用")
    void exactSuffixVariantResolves() throws Exception {
        when(mapper.findOfficesByExactNames(List.of("测试", "测试店", "测试门店")))
                .thenReturn(List.of(office("10", "测试店", 6)));
        when(mapper.countOfficeStoreCodes("10")).thenReturn(1L);
        when(mapper.listOfficeStoreCodes("10", OfficeStoreTool.MAX_STORE_CODES)).thenReturn(List.of("04010112"));

        JsonNode result = read(tool.resolveOfficeStores(null, "测试"));

        assertThat(result.get("status").asText()).isEqualTo("resolved");
        assertThat(result.get("officeName").asText()).isEqualTo("测试店");
        assertThat(result.get("matchedBy")).isNull();
        verify(mapper, never()).findOfficeCandidatesByName(anyString(), anyString());
    }

    @Test
    @DisplayName("包含匹配唯一命中：直接使用，不再要求确认")
    void containsUniqueMatchResolves() throws Exception {
        when(mapper.findOfficesByExactNames(List.of("长江路", "长江路店", "长江路门店")))
                .thenReturn(List.of());
        when(mapper.findOfficeCandidatesByName("长江路", "长江路%")).thenReturn(List.of());
        when(mapper.findOfficeCandidatesByName("长江路", "%长江路%"))
                .thenReturn(List.of(office("20", "哈市长江路店", 11)));
        when(mapper.countOfficeStoreCodes("20")).thenReturn(1L);
        when(mapper.listOfficeStoreCodes("20", OfficeStoreTool.MAX_STORE_CODES)).thenReturn(List.of("01010601"));

        JsonNode result = read(tool.resolveOfficeStores(null, "长江路"));

        assertThat(result.get("status").asText()).isEqualTo("resolved");
        assertThat(result.get("officeId").asText()).isEqualTo("20");
        assertThat(result.get("storeCodes").get(0).asText()).isEqualTo("01010601");
    }

    @Test
    @DisplayName("名称首尾空白与全角空格归一化后再精确匹配")
    void nameIsNormalizedBeforeExactMatch() throws Exception {
        when(mapper.findOfficesByExactNames(List.of("小酒喔总部", "小酒喔总部店", "小酒喔总部门店")))
                .thenReturn(List.of(office("1", "小酒喔总部", 2)));
        when(mapper.countOfficeStoreCodes("1")).thenReturn(1L);
        when(mapper.listOfficeStoreCodes("1", OfficeStoreTool.MAX_STORE_CODES)).thenReturn(List.of("01010601"));

        JsonNode result = read(tool.resolveOfficeStores(null, "\u3000小酒喔总部 \u3000"));

        assertThat(result.get("status").asText()).isEqualTo("resolved");
        assertThat(result.get("officeId").asText()).isEqualTo("1");
    }

    @Test
    @DisplayName("名称唯一命中门店类型机构：按精确名称解析并返回编码")
    void uniqueStoreNameResolves() throws Exception {
        when(mapper.findOfficesByExactNames(List.of("测试门店", "测试门店店", "测试门店门店")))
                .thenReturn(List.of(office("10", "测试门店", 6)));
        when(mapper.countOfficeStoreCodes("10")).thenReturn(1L);
        when(mapper.listOfficeStoreCodes("10", OfficeStoreTool.MAX_STORE_CODES)).thenReturn(List.of("04010112"));

        JsonNode result = read(tool.resolveOfficeStores(null, "测试门店"));

        assertThat(result.get("status").asText()).isEqualTo("resolved");
        assertThat(result.get("officeId").asText()).isEqualTo("10");
        assertThat(result.get("storeCount").asInt()).isEqualTo(1);
        assertThat(result.get("storeCodes").get(0).asText()).isEqualTo("04010112");
    }

    @Test
    @DisplayName("无匹配机构或机构下无有效门店：分别返回 no_match / no_stores")
    void noMatchAndNoStores() throws Exception {
        when(mapper.findOfficesByExactNames(List.of("不存在机构", "不存在机构店", "不存在机构门店")))
                .thenReturn(List.of());
        when(mapper.findOfficeCandidatesByName("不存在机构", "不存在机构%")).thenReturn(List.of());
        when(mapper.findOfficeCandidatesByName("不存在机构", "%不存在机构%")).thenReturn(List.of());
        JsonNode noMatch = read(tool.resolveOfficeStores(null, "不存在机构"));
        assertThat(noMatch.get("status").asText()).isEqualTo("no_match");

        when(mapper.findOfficeById("99")).thenReturn(office("99", "空机构", 1));
        when(mapper.countOfficeStoreCodes("99")).thenReturn(0L);
        JsonNode noStores = read(tool.resolveOfficeStores("99", null));
        assertThat(noStores.get("status").asText()).isEqualTo("no_stores");
        assertThat(noStores.get("storeCodes")).isEmpty();
    }

    @Test
    @DisplayName("no_stores 区分渠道类机构与普通机构，不再提示“请核对机构范围”")
    void noStoresHintDistinguishesChannelOffice() throws Exception {
        when(mapper.findOfficeById("3279")).thenReturn(office("3279", "竹叶青长江路店", 18));
        when(mapper.countOfficeStoreCodes("3279")).thenReturn(0L);
        JsonNode channel = read(tool.resolveOfficeStores("3279", null));
        assertThat(channel.get("status").asText()).isEqualTo("no_stores");
        assertThat(channel.get("actionHint").asText()).contains("渠道").doesNotContain("请核对机构范围");

        when(mapper.findOfficeById("99")).thenReturn(office("99", "空机构", 1));
        when(mapper.countOfficeStoreCodes("99")).thenReturn(0L);
        JsonNode normal = read(tool.resolveOfficeStores("99", null));
        assertThat(normal.get("actionHint").asText()).contains("没有可统计门店").doesNotContain("请核对机构范围");
    }

    private Map<String, Object> office(String id, String name, int type) {
        return Map.of("officeId", id, "officeName", name, "officeType", type);
    }

    private JsonNode read(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}

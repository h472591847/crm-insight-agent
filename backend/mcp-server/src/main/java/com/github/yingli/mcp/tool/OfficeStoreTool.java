package com.github.yingli.mcp.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.yingli.mapper.OfficeMapper;
import com.github.yingli.adhoc.request.FilterSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 机构到 CRM 分配门店编码的受控解析工具。
 * 只提供 resolveOfficeStores 一个入口，不提供机构树或 sys_office 自由查询。
 */
@Component
@Slf4j
public class OfficeStoreTool {

    static final int MAX_STORE_CODES = FilterSpec.MAX_IN_VALUES;
    private static final int MAX_CANDIDATES = 20;

    private final OfficeMapper mapper;
    private final ObjectMapper objectMapper;

    public OfficeStoreTool(OfficeMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "把机构 ID 或机构名称解析成 CRM 会员分配门店编码集合。口径与 CRM 会员列表页一致："
            + "本机构及 parent_ids 全部下级，仅取可统计门店类型 6/11/22/28 且 HD_ID 长度为 8；"
            + "渠道店（18）、要客部（13）、新媒体（14）不纳入统计。"
            + "officeId 与 officeName 必须且只能传一个。"
            + "名称查询至少 2 个字符，按“精确优先、逐级降级”匹配：先用 = 精确匹配原名/原名+店/原名+门店，"
            + "命中即直接用（唯一命中就 resolved，机构类型是总部/大区也照常解析下级门店，不得再追问）；"
            + "精确未命中才降级为前缀、最后包含匹配。"
            + "只有降级后仍有多候选时才返回 candidates，此时不得自行选择，需用户确认后用 officeId 重查。"
            + "status=resolved 时，把 storeCodes 原样传给 adhocQuery 的 filters[{entity:'member',"
            + "column:'member_allot_store',op:'IN',values:storeCodes}]，不要用 office_id 代替。"
            + "storeCount 超过 adhoc IN 去重后 500 值上限时返回 too_many_stores 且 storeCodes 为空："
            + "必须请用户缩小机构范围，不得截取部分门店，也不得分批求和；总部级（如 369 个门店）在上限内，直接返回全部编码。"
            + "status=no_stores 有两种语义：机构下没有可统计门店，或该机构本身是渠道/加盟、要客部、新媒体类不纳入统计；"
            + "此时如实说明，不得改用 office_id 过滤会员。")
    public String resolveOfficeStores(
            @ToolParam(description = "CRM 机构 ID，例如 '1'；与 officeName 二选一", required = false) String officeId,
            @ToolParam(description = "机构名称关键词（至少 2 个字符）；与 officeId 二选一", required = false) String officeName) {
        boolean hasId = officeId != null && !officeId.isBlank();
        boolean hasName = officeName != null && !officeName.isBlank();
        if (hasId == hasName) {
            return write(Map.of(
                    "status", "invalid_input",
                    "message", "officeId 与 officeName 必须且只能传一个"));
        }
        if (hasId) {
            Map<String, Object> office = mapper.findOfficeById(officeId.trim());
            if (office == null || office.isEmpty()) {
                return noMatch("未找到该机构 ID，请核对后重试");
            }
            return resolveOffice(office);
        }
        return resolveByName(officeName);
    }

    private String resolveByName(String rawName) {
        String officeName = normalizeName(rawName);
        if (officeName.length() < 2) {
            return write(Map.of(
                    "status", "invalid_input",
                    "message", "机构名称关键词至少 2 个字符"));
        }
        // 第一优先级：精确名称（=），含“原名 / 原名+店 / 原名+门店”三种确定变形；命中即用，不再追问。
        List<Map<String, Object>> candidates = mapper.findOfficesByExactNames(exactForms(officeName));
        String matchedBy = "exact";
        if (candidates.isEmpty()) {
            candidates = mapper.findOfficeCandidatesByName(officeName, officeName + "%");
            matchedBy = "prefix";
        }
        if (candidates.isEmpty()) {
            candidates = mapper.findOfficeCandidatesByName(officeName, "%" + officeName + "%");
            matchedBy = "contains";
        }
        if (candidates.isEmpty()) {
            return noMatch("未找到匹配机构，请提供更准确名称或机构 ID");
        }
        // 只有多候选才需要用户选择；唯一命中一律直接解析，机构类型是总部/大区也照常解析其下级门店。
        if (candidates.size() != 1) {
            return candidates(candidates, matchedBy, "multiple_matches",
                    "找到多个机构候选，请用户确认后用 officeId 重查，不得自行选择");
        }
        return resolveOffice(candidates.get(0));
    }

    /** 首尾空白与全角空格归一化；名称中间的多余空白压缩成一个半角空格。 */
    private String normalizeName(String name) {
        return name == null ? "" : name.replace('\u3000', ' ').trim().replaceAll("\\s+", " ");
    }

    /** 精确匹配的确定变形集合，按优先级去重。 */
    private List<String> exactForms(String officeName) {
        return java.util.stream.Stream.of(officeName, officeName + "店", officeName + "门店")
                .distinct()
                .toList();
    }

    private String resolveOffice(Map<String, Object> office) {
        String officeId = String.valueOf(office.get("officeId"));
        long storeCount = mapper.countOfficeStoreCodes(officeId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("officeId", officeId);
        result.put("officeName", office.get("officeName"));
        result.put("officeType", office.get("officeType"));
        result.put("storeCount", storeCount);
        result.put("maxStoreCodes", MAX_STORE_CODES);
        if (storeCount == 0) {
            result.put("status", "no_stores");
            result.put("storeCodes", List.of());
            result.put("truncated", false);
            result.put("actionHint", noStoresHint(office.get("officeType")));
            return write(result);
        }
        if (storeCount > MAX_STORE_CODES) {
            result.put("status", "too_many_stores");
            result.put("storeCodes", List.of());
            result.put("truncated", false);
            result.put("actionHint", "门店数超过 adhoc IN 的 " + MAX_STORE_CODES + " 值上限；请用户缩小机构范围，"
                    + "不得截取部分门店，也不得分批求和");
            return write(result);
        }
        List<String> storeCodes = mapper.listOfficeStoreCodes(officeId, MAX_STORE_CODES);
        result.put("status", "resolved");
        result.put("storeCodes", storeCodes);
        result.put("truncated", false);
        result.put("actionHint", "resolved — 将 storeCodes 原样传给 adhocQuery 的 filters[{entity:'member',"
                + "column:'member_allot_store',op:'IN',values:storeCodes}]；不要用 office_id 代替");
        log.info("机构门店解析成功 officeId={}, storeCount={}", officeId, storeCount);
        return write(result);
    }

    /** no_stores 提示：区分“机构下没有可统计门店”和“该机构类型本身不纳入统计”，不写容易被误读的“请核对机构范围”。 */
    private String noStoresHint(Object officeType) {
        String type = officeType == null ? "" : String.valueOf(officeType);
        if ("18".equals(type) || "17".equals(type)) {
            return "该机构属于渠道类机构（type=" + type + "），渠道店不纳入门店统计口径；"
                    + "如需统计请提供具体直营/加盟门店的名称或编码，不得改用 office_id 过滤会员";
        }
        if ("13".equals(type) || "14".equals(type)) {
            return "该机构属于要客部/新媒体类机构（type=" + type + "），不纳入门店统计口径；"
                    + "如需统计请提供具体直营/加盟门店的名称或编码，不得改用 office_id 过滤会员";
        }
        return "该机构下没有可统计门店（仅统计门店类型 6/11/22/28 且 HD_ID 为 8 位）；"
                + "不得改用 office_id 过滤会员";
    }

    private String candidates(List<Map<String, Object>> matches, String matchedBy, String reason, String actionHint) {
        List<Map<String, Object>> candidates = new ArrayList<>(matches.stream().limit(MAX_CANDIDATES).toList());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "needs_clarification");
        result.put("reason", reason);
        result.put("matchedBy", matchedBy);
        result.put("candidates", candidates);
        result.put("candidatesTruncated", matches.size() > MAX_CANDIDATES);
        result.put("actionHint", actionHint);
        return write(result);
    }

    private String noMatch(String actionHint) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "no_match");
        result.put("candidates", List.of());
        result.put("actionHint", actionHint);
        return write(result);
    }

    private String write(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("机构门店解析结果序列化失败", e);
        }
    }
}

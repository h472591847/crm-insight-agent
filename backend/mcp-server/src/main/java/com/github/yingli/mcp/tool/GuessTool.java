package com.github.yingli.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.yingli.mapper.MemberInfoMapper;
import com.github.yingli.mapper.OfficeMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 模糊查询 MCP 工具（辅助追问用）。
 * <p>
 * 业务场景：用户给出的门店名称不完整、只给了手机号前缀等情况下，
 * 模型先调用本工具拿到候选列表，再判断：
 * <ul>
 *     <li>恰好 1 个候选 → 直接用，告诉用户"实际匹配到的是 XX 门店"；</li>
 *     <li>多个候选 → 停下来追问，把候选列给用户让其明确；</li>
 *     <li>0 个候选 → 如实告知"没找到"。</li>
 * </ul>
 * 门店匹配策略：精确名称（含自动补"店"后缀）→ 前缀 → 包含，命中即停。
 * 会员表（member_info_view）三级匹配全空时，再用机构表（sys_office）同序兜底：
 * 门店存在但没有会员分配记录时返回 {@code matchedBy=office_only}、{@code memberCount=0}，
 * 而不是误报"门店不存在"；渠道店（type=18）、要客部（13）、新媒体（14）不纳入兜底。
 * 一句话多个门店时按关键词数组逐个独立解析、分别返回，避免漏店或跨城市同名路段混入。
 * 所有 SQL 只读、带 LIMIT 兜底。
 * </p>
 *
 * @author oliver
 * @since 1.0.0
 */
@Component
@Slf4j
public class GuessTool {

    private final MemberInfoMapper memberInfoMapper;
    private final OfficeMapper officeMapper;
    private final ObjectMapper objectMapper;

    /** keyword 最小长度——防止空字符串或单字符扫全表 */
    private static final int MIN_KEYWORD_LEN = 2;
    /** 手机号查询最小长度 */
    private static final int MIN_MOBILE_PREFIX_LEN = 3;
    /** 单次最多解析的门店关键词数：防止模型把整段问题拆成几十个词扫库 */
    private static final int MAX_KEYWORDS = 5;
    /** 关键词分隔符：顿号/逗号/分号/斜杠/换行。"和""与"不拆，避免误伤"和平路"这类店名 */
    private static final Pattern KEYWORD_SEPARATOR = Pattern.compile("[、,，;；/\\r\\n]+");

    public GuessTool(MemberInfoMapper memberInfoMapper, OfficeMapper officeMapper, ObjectMapper objectMapper) {
        this.memberInfoMapper = memberInfoMapper;
        this.officeMapper = officeMapper;
        this.objectMapper = objectMapper;
    }

    // ==================== 门店模糊查询 ====================

    /**
     * 根据门店名称关键词（支持一次多个）模糊匹配门店。
     * <p>
     * 匹配顺序：精确名称（含自动补"店"后缀）→ 前缀（keyword%）→ 包含（%keyword%），命中即停。
     * 精确优先的原因：用户关键词带城市/区前缀（"哈市长江路"）时应当唯一命中；
     * 若直接走包含匹配，会把其他城市的同路段门店（如"辽宁海城区长江路店"）混进候选。
     * </p>
     * <p>
     * 多门店：用户一句话提到多个门店时，模型应为每个门店各传一个元素，
     * 本工具逐个独立解析并分别返回候选，避免只解析其中一个、或把两个门店名拼成一个关键词。
     * </p>
     *
     * @param keywords 门店名称关键词列表（街道名/路名/商圈名/别名/部分名称，每个至少 2 字；最多 5 个）
     * @return JSON：{keywordCount, results:[{keyword, count, matchedBy, candidates, actionHint}], actionHint}
     */
    @Tool(name = "guessStation",
            description = "根据用户给出的门店名称关键词数组模糊匹配门店。"
                    + "用户一句话提到多个门店时，每个门店一个元素分别传入（如 [\"哈市长江路\", \"鸿翔路\"]）；"
                    + "关键词取用户原文，不要截短城市/区前缀（写了'哈市长江路'就传'哈市长江路'，不要只传'长江路'），"
                    + "也不得只取其中一个门店。"
                    + "返回 results[]，每个关键词独立给出 candidates（stationCode/stationName/memberCount）与 actionHint："
                    + "unique_match 可直接用；multiple_matches 必须停下来追问用户选哪一家；no_match 如实告知没找到。"
                    + "匹配顺序为精确名称（自动补'店'后缀）→ 前缀 → 包含，命中即停。"
                    + "会员表三级匹配全空时，会自动用机构表同序兜底门店：此时 matchedBy=office_only、memberCount=0，"
                    + "语义是“门店存在但当前没有会员分配记录”（会员/要客数为 0），不得说成门店不存在；"
                    + "候选里的 hasOrders=true 表示该门店有销售记录，可在回答中补充一句。"
                    + "渠道店（type=18）、要客部（13）、新媒体（14）不纳入门店统计，仍返回 no_match。"
    )
    public String guessStation(
            @ToolParam(description = "门店名称关键词数组；用户提到几个门店就传几个元素，每个元素保留原文（含城市/区前缀）")
            List<String> keywords) {
        log.info("调用 MCP 工具 guessStation, keywords={}", keywords);

        List<String> tokens = normalizeKeywords(keywords);
        if (tokens.isEmpty()) {
            return buildEmptyResult("门店名称关键词太短（至少 " + MIN_KEYWORD_LEN + " 字）");
        }

        List<Map<String, Object>> results = new ArrayList<>(tokens.size());
        boolean anyMultiple = false;
        boolean anyNoMatch = false;
        for (String keyword : tokens) {
            Map<String, Object> item = resolveStation(keyword);
            results.add(item);
            int count = (int) item.get("count");
            anyMultiple |= count > 1;
            anyNoMatch |= count == 0;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keywordCount", results.size());
        result.put("results", results);
        result.put("actionHint", aggregateHint(results.size(), anyMultiple, anyNoMatch));

        log.info("guessStation 结果: keywords={}, keywordCount={}, anyMultiple={}, anyNoMatch={}",
                tokens, results.size(), anyMultiple, anyNoMatch);
        return toJson(result);
    }

    /** 单个关键词解析：精确 → 前缀 → 包含，命中即停；会员表全空时用机构表同序兜底。 */
    private Map<String, Object> resolveStation(String keyword) {
        List<Map<String, Object>> candidates = memberInfoMapper.guessStationByExact(keyword);
        String matchedBy = "exact";

        if (candidates.isEmpty()) {
            candidates = memberInfoMapper.guessStationByPrefix(keyword);
            matchedBy = "prefix";
        }
        if (candidates.isEmpty()) {
            candidates = memberInfoMapper.guessStationByContains(keyword);
            matchedBy = candidates.isEmpty() ? "none" : "contains";
        }
        if (candidates.isEmpty()) {
            return resolveOfficeFallback(keyword);
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("keyword", keyword);
        item.put("count", candidates.size());
        item.put("matchedBy", matchedBy);
        item.put("candidates", candidates);
        if (candidates.size() == 1) {
            item.put("actionHint", "unique_match — 直接使用该门店编码");
        } else {
            item.put("actionHint", "multiple_matches — 停下来追问用户选哪一家，不得自行选择");
        }
        return item;
    }

    /**
     * 会员表（member_info_view）无会员记录时的机构表兜底：精确 → 前缀 → 包含。
     * 只认可统计门店（type 6/11/22/28、8 位 HD_ID），渠道店 18、要客部 13、新媒体 14 不纳入。
     */
    private Map<String, Object> resolveOfficeFallback(String keyword) {
        List<Map<String, Object>> offices = officeMapper.findFallbackStoresByExactNames(exactForms(keyword));
        if (offices.isEmpty()) {
            offices = officeMapper.findFallbackStoresByName(keyword, keyword + "%");
        }
        if (offices.isEmpty()) {
            offices = officeMapper.findFallbackStoresByName(keyword, "%" + keyword + "%");
        }
        if (offices.isEmpty()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("keyword", keyword);
            item.put("count", 0);
            item.put("matchedBy", "none");
            item.put("candidates", List.of());
            item.put("actionHint", "no_match — 如实告知用户该门店没找到");
            return item;
        }

        boolean unique = offices.size() == 1;
        List<Map<String, Object>> candidates = new ArrayList<>(offices.size());
        for (Map<String, Object> office : offices) {
            candidates.add(officeCandidate(office, unique));
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("keyword", keyword);
        item.put("count", candidates.size());
        item.put("matchedBy", "office_only");
        item.put("candidates", candidates);
        item.put("actionHint", unique
                ? "unique_match — 门店存在，但当前没有会员分配记录，会员/要客数为 0"
                    + "（matchedBy=office_only）；可结合 hasOrders 说明是否有销售记录"
                : "multiple_matches — 命中多家门店且当前均无会员分配记录，必须停下来追问用户选哪一家，不得自行选择");
        log.info("guessStation 机构表兜底命中: keyword={}, count={}", keyword, candidates.size());
        return item;
    }

    /** 机构表兜底候选：统一成 stationCode/stationName/memberCount 结构，会员数固定 0；唯一命中时补 hasOrders。 */
    private Map<String, Object> officeCandidate(Map<String, Object> office, boolean includeOrders) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        Object code = office.get("stationCode");
        candidate.put("stationCode", code);
        candidate.put("stationName", office.get("stationName"));
        candidate.put("officeType", office.get("officeType"));
        candidate.put("memberCount", 0);
        candidate.put("memberRecord", false);
        candidate.put("note", "无会员分配记录，会员/要客数为 0");
        if (includeOrders) {
            boolean hasOrders = code != null && officeMapper.countOrdersByStationCode(String.valueOf(code)) > 0;
            candidate.put("hasOrders", hasOrders);
        }
        return candidate;
    }

    /** 机构表精确匹配的确定变形集合：原名 / 原名+店 / 原名+门店。 */
    private List<String> exactForms(String keyword) {
        return java.util.stream.Stream.of(keyword, keyword + "店", keyword + "门店")
                .distinct()
                .toList();
    }

    /**
     * 关键词规范化：按顿号/逗号/分号/斜杠/换行拆分（"和""与"不拆，避免误伤"和平路"这类店名），
     * 去空白、去重、丢弃过短项、最多保留 {@value #MAX_KEYWORDS} 个。
     */
    private List<String> normalizeKeywords(List<String> keywords) {
        if (keywords == null) {
            return List.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String raw : keywords) {
            if (raw == null) {
                continue;
            }
            for (String part : KEYWORD_SEPARATOR.split(raw)) {
                String trimmed = StringUtils.trimToNull(part);
                if (trimmed != null && trimmed.length() >= MIN_KEYWORD_LEN) {
                    tokens.add(trimmed);
                }
            }
        }
        return tokens.stream().limit(MAX_KEYWORDS).toList();
    }

    /** 汇总提示：多命中→必须追问；部分没找到→如实说明并继续可解析的门店；全部唯一→分别查询。 */
    private String aggregateHint(int keywordCount, boolean anyMultiple, boolean anyNoMatch) {
        if (anyMultiple) {
            return "needs_clarify — 至少一个关键词命中多个门店，必须逐个追问用户选哪一家，不得自行挑选，"
                    + "也不得把多个门店合并成一个条件";
        }
        if (anyNoMatch) {
            return "partial_no_match — 部分关键词没找到，如实告知并对唯一命中的门店分别查询、分别输出结果";
        }
        return "all_unique — " + keywordCount + " 个门店均唯一命中，按各自编码分别查询并分别输出结果，"
                + "不得只回答其中一家";
    }

    // ==================== 会员手机号模糊查询 ====================

    /**
     * 根据手机号前缀模糊匹配会员。
     * <p>
     * 用于用户只给了手机号前几位（如"138"）时定位具体会员。
     * 仅做前缀匹配（手机号按前缀查效率高），不兜底包含匹配。
     * </p>
     *
     * @param keyword 手机号前缀（至少 3 位数字）
     * @return JSON：{count, candidates:[{memberMobile, memberName, stationCode, stationName}]}
     */
    @Tool(name = "guessMemberByMobile",
            description = "根据手机号前缀模糊匹配会员（仅前缀匹配，如 138/139/1357）。"
                    + "返回候选会员列表，每条含 memberMobile/memberName/stationCode/stationName。"
                    + "恰好 1 个候选可直接用；多个候选必须停下来追问用户确认。"
                    + "用户已给出完整手机号时无需调用本工具，直接走 adhocQuery 用手机号列等值过滤"
                    + "（member→member_mobile / order→expense_mobile / detail→expense_d_mobile）；"
                    + "手机号列只支持精确匹配，禁止 LIKE/前缀/包含等模糊匹配。"
    )
    public String guessMemberByMobile(String keyword) {
        log.info("调用 MCP 工具 guessMemberByMobile, keyword={}", keyword);

        String trimmed = StringUtils.trimToNull(keyword);
        if (trimmed == null || trimmed.length() < MIN_MOBILE_PREFIX_LEN || !trimmed.matches("\\d+")) {
            return buildEmptyResult("手机号前缀无效（至少 " + MIN_MOBILE_PREFIX_LEN + " 位纯数字）");
        }

        List<Map<String, Object>> candidates = memberInfoMapper.guessMemberByMobile(trimmed);
        return buildMemberResult(candidates, trimmed);
    }

    // ==================== 内部工具：结果构造 ====================

    private String buildMemberResult(List<Map<String, Object>> candidates, String keyword) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", candidates.size());
        result.put("keyword", keyword);
        result.put("candidates", candidates);

        if (candidates.isEmpty()) {
            result.put("actionHint", "no_match — 如实告知用户没找到匹配的会员");
        } else if (candidates.size() == 1) {
            result.put("actionHint", "unique_match — 直接使用该会员");
        } else {
            result.put("actionHint", "multiple_matches — 停下来追问用户确认身份");
        }

        return toJson(result);
    }

    private String buildEmptyResult(String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", 0);
        result.put("actionHint", "invalid_input — " + reason);
        result.put("candidates", Collections.emptyList());
        return toJson(result);
    }

    private String toJson(Map<String, Object> result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("GuessTool JSON 序列化失败: {}", e.getMessage());
            return "{\"count\":0,\"error\":\"序列化失败\"}";
        }
    }
}

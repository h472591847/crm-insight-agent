package com.itzixi.mcp.tool;

import com.itzixi.adhoc.exception.AdhocException;
import com.itzixi.adhoc.service.AdhocQueryService;
import com.itzixi.adhoc.service.SemanticCatalogService;
import com.itzixi.adhoc.request.AdhocBatchRequest;
import com.itzixi.adhoc.request.AdhocQueryRequest;
import com.itzixi.adhoc.response.AdhocResult;
import com.itzixi.prompt.ToolPromptSnippets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description Ad-hoc 自助分析 MCP 工具：模型按语义目录组合查询参数，服务端白名单裁决并编译执行。
 * 与固化指标互补：高频口径走 YAML 指标（口径有业务确认正文），探索性分析走本工具（口径由 caliber 行转述）。
 * @Author crm-agent
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AdhocTool {

    private final AdhocQueryService adhocQueryService;

    private final SemanticCatalogService semanticCatalogService;

    @Tool(name = "adhocQuery",
            description = "自助分析查询（探索性/一次性统计需求用；已固化口径请优先用 listMetrics 的指标工具）。"
                    + "先调 listSemanticCatalog 获取可用实体/维度/度量/条件模板/时间维度，参数只能引用目录中的名字。"
                    + "一次请求只能一条实体链、一种度量粒度（如 detail 粒度与 member 快照粒度不可同选）；"
                    + "问题包含多个独立组合时改用 adhocQueryBatch，不要为凑一个结果拼接无声明关系的表。"
                    + "门店过滤只用门店编码列等值（expense_stationCode/expense_d_stationCode，有索引）；"
                    + "门店名称列无索引禁止过滤。用户给的名称不完整/不确定时，先用 member 实体按 "
                    + "member_allot_stationName 模糊解析编码：恰好 1 个候选直接用并在回答中说明实际门店名，"
                    + "多个候选停下来把编码+名称列给用户确认是哪家店，0 个候选请用户给编码，不得猜"
                    + "（见目录 filterGuidance）。"
                    + ToolPromptSnippets.ERRORCODE_VERBOSE
                    + ToolPromptSnippets.EMPTY_RESULT_ZERO
                    + ToolPromptSnippets.ADHOC_TIMEOUT_HINT
                    + "带 queryGuard 的实体（如 detail 明细表）无论在主表还是 join 位置都必须满足守卫条件"
                    + "（时间范围/定向列之一，窗口按守卫上限），服务端强制裁决，违反直接拒绝。"
                    + "机构范围必须先调 resolveOfficeStores 拿门店编码，再用 filters[{entity:'member',"
                    + "column:'member_allot_store',op:'IN',values:storeCodes}]；不要用 office_id 代替。"
                    + ToolPromptSnippets.PHONE_DIRECT_QUERY
                    + ToolPromptSnippets.NO_PII + ToolPromptSnippets.CALIBER_FORBIDDEN
                    + ToolPromptSnippets.NO_INTERNAL_FILTER_EXPLANATION
                    + ToolPromptSnippets.AMBIGUOUS_MEMBER_LEVEL + ToolPromptSnippets.NO_SUGGESTION)
    public AdhocResult adhocQuery(AdhocQueryRequest request) {
        log.info("====== MCP工具 adhocQuery 参数：{} ======", request);
        return adhocQueryService.query(request);
    }

    @Tool(name = "adhocQueryBatch",
            description = "多块自助分析：一次提交多个独立查询块，按顺序返回等长结果。"
                    + "用于同一问题需要多个组合的场景（如“会员和订单按门店、会员和明细按商品”）："
                    + "每个块保持单实体链、单度量粒度，块之间不做 join，避免跨粒度指标被乘翻。"
                    + "每块都必须满足自身实体守卫；某块失败只影响该块，其余块照常返回。"
                    + "结果按 queries 顺序对应，errorCode 如实转述；"
                    + ToolPromptSnippets.CALIBER_FORBIDDEN
                    + ToolPromptSnippets.NO_INTERNAL_FILTER_EXPLANATION
                    + ToolPromptSnippets.AMBIGUOUS_MEMBER_LEVEL
                    + "不得把不同块的口径混在一起。")
    public List<AdhocResult> adhocQueryBatch(AdhocBatchRequest request) {
        log.info("====== MCP工具 adhocQueryBatch 参数：{} ======", request);
        return adhocQueryService.queryBatch(request == null ? null : request.getQueries());
    }

    @Tool(name = "listSemanticCatalog",
            description = "列出 Ad-hoc 可用的语义目录：每个实体的维度/度量/条件模板/时间维度/可过滤列/可 join 实体，"
                    + "维度/度量/可过滤列均带 pii 布尔标注（true=涉个人信息：维度/过滤列禁止原值输出；"
                    + "度量表示 PII 派生、仅聚合结果可输出但仍可正常使用）。"
                    + "可过滤列另带 indexed 标注（true=该列在表上有索引，过滤条件优先用这类列收敛扫描量；"
                    + "false=无索引，过滤可能扫全表，仅在有守卫/定向条件兜住时使用）。"
                    + "度量的 grain 是它所属实体的粒度；relationships 的 cardinality=to_many 表示会放大行数，"
                    + "一次 adhocQuery 只能选同一 grain 的度量，多个粒度请用 adhocQueryBatch 拆块。"
                    + "实体若带 queryGuard 为其硬查询条件（如 detail 明细：无论在主表还是 join 位置，都必带时间范围/手机号/流水号之一且窗口≤守卫上限），组参前必读。"
                    + "调用 adhocQuery 前必须先调用本工具确认可用名字，不得臆造列名或指标名。")
    public List<Map<String, Object>> listSemanticCatalog() {
        return semanticCatalogService.listCatalog();
    }

    @Tool(name = "explainAdhoc",
            description = "Ad-hoc 干跑（开发/联调工具）：校验并编译返回 SQL/参数/sqlHash，不执行查询。")
    public Map<String, Object> explainAdhoc(AdhocQueryRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            // 干跑复用服务层前置：校验在 AdhocQueryService 内与这里各跑一次代价极低，保持工具独立可测
            AdhocResult res = adhocQueryService.dryRun(request);
            return res.errorCode() != null ? Map.of("errorCode", res.errorCode(),
                    "message", res.errorMessage()) : res.rows().get(0);
        } catch (AdhocException e) {
            out.put("errorCode", e.code());
            out.put("message", e.getMessage());
            return out;
        }
    }
}

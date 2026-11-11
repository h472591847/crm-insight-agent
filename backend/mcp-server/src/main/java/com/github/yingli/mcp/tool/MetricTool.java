package com.github.yingli.mcp.tool;

import com.github.yingli.metrics.request.MetricDraftRequest;
import com.github.yingli.metrics.request.MetricQueryRequest;
import com.github.yingli.metrics.response.MetricResult;
import com.github.yingli.metrics.tooling.MetricDrafter;
import com.github.yingli.metrics.service.MetricCatalogService;
import com.github.yingli.metrics.service.MetricQueryService;
import com.github.yingli.prompt.ToolPromptSnippets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * @Description 指标即配置（Metrics-as-Code）的运行时 MCP 工具。
 * 与 Phase 1"一个指标一个工具"不同，这里按业务域收敛：域内所有指标共用一个查询工具，
 * 新增指标只需增加 YAML 配置，本工具无需改动。
 * <p>
 * 职责边界与 Phase 1 完全一致：模型只选择指标、组织参数并转述结果；
 * 参数校验、口径确认、SQL 生成、数据访问与确定性计算全部在服务端完成。
 * 所有查询均为只读聚合，不返回任何个人信息。
 *
 * @Author crm-agent
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MetricTool {

    private final MetricQueryService metricQueryService;

    private final MetricCatalogService metricCatalogService;

    private final MetricDrafter metricDrafter;

    @Tool(name = "queryMemberMetric",
            description = "查询会员域指标（编码与可用维度/过滤见 listMetrics(domain=member)）。"
                    + "查询会员指标前应先调 listMetrics 确认可用维度与口径；部分指标（如活跃会员）声明了自己的默认时间窗口，见口径正文。"
                    + ToolPromptSnippets.ERRORCODE_VERBOSE
                    + "结果 rows 按 columns 顺序排列。"
                    + ToolPromptSnippets.CALIBER_FORBIDDEN
                    + ToolPromptSnippets.NO_INTERNAL_FILTER_EXPLANATION
                    + ToolPromptSnippets.EMPTY_RESULT_ZERO + ToolPromptSnippets.NO_SUGGESTION)
    public MetricResult queryMemberMetric(MetricQueryRequest request) {
        log.info("====== MCP工具 queryMemberMetric 参数：{} ======", request);
        return metricQueryService.query("member", request);
    }

    @Tool(name = "queryOrderMetric",
            description = "查询订单/门店/商品域指标（如 STATION_SALES 门店销售业绩、PRODUCT_RANKING 商品销售排行；"
                    + "编码与可用维度/过滤见 listMetrics(domain=order)）。"
                    + "时间未提供时默认近一年半（2026-10-29 看板口径）；测试库历史数据建议显式传区间。"
                    + ToolPromptSnippets.ERRORCODE_VERBOSE
                    + ToolPromptSnippets.NO_INTERNAL_FILTER_EXPLANATION
                    + ToolPromptSnippets.EMPTY_RESULT_ZERO + ToolPromptSnippets.NO_SUGGESTION)
    public MetricResult queryOrderMetric(MetricQueryRequest request) {
        log.info("====== MCP工具 queryOrderMetric 参数：{} ======", request);
        return metricQueryService.query("order", request);
    }

    @Tool(name = "listMetrics",
            description = "列出已注册的指标目录：编码、中文名、口径正文、状态（CONFIRMED 才可查询）、"
                    + "可用维度/度量/过滤/排序编码与条数限制。查询指标前应先调用本工具确认可用能力。"
                    + "domain 可选：member|order|delivery|finance，不传返回全部。")
    public List<Map<String, Object>> listMetrics(
            @ToolParam(description = "业务域编码，可选；不传或为空返回全部域", required = false) String domain) {
        log.info("====== MCP工具 listMetrics 参数：domain={} ======", domain);
        try {
            return metricCatalogService.catalog(domain);
        } catch (com.github.yingli.metrics.exception.MetricException e) {
            // 目录查询失败对模型可见原因即可，目录本身没有专用错误结构
            return List.of(Map.of("errorCode", e.getCode().name(), "message", e.getMessage()));
        }
    }

    @Tool(name = "explainMetric",
            description = "指标干跑（开发/联调工具）：按请求编译并返回将执行的 SQL、参数、sqlHash 与输出列，"
                    + "不执行查询。includeExplain=true 时额外返回 MySQL EXPLAIN 摘要（仍不执行查询）。")
    public Map<String, Object> explainMetric(MetricQueryRequest request,
                                             @ToolParam(description = "是否附带 EXPLAIN 预演结果，默认 false",
                                                     required = false) Boolean includeExplain) {
        log.info("====== MCP工具 explainMetric 参数：{} includeExplain={} ======", request, includeExplain);
        // 干跑不做域归属断言：按注册表实际域审 SQL，避免开发期因选错工具被误判为跨域越权
        return metricQueryService.explain(null, request, Boolean.TRUE.equals(includeExplain));
    }

    @Tool(name = "draftMetric",
            description = "指标脚手架（开发/联调工具）：把结构化的指标意图确定性地组装成一段可直接粘贴进 metrics/*.yaml 的配置片段，"
                    + "并即时跑一遍加载期安全校验与干跑编译，返回 yaml + 样例 SQL + validationErrors。"
                    + "用于降低『指标即配置』的编写门槛，替代『写完要到重启才知道哪里错』。"
                    + "边界：本工具不连数据库、不落盘、不注册指标，也不生成运行期 SQL；"
                    + "caliber（业务口径正文）必须由人工提供，脚手架缺省把 status 标 UNCONFIRMED，绝不臆造口径。"
                    + "VARCHAR 金额列命中登记表时自动包 NUM()；join 场景的列引用需自行带限定符，ON 由工具按别名组装。"
                    + "当 validationErrors 非空时，必须如实转述每一条中文原因并修正入参后重试，不得声称草稿已可用。")
    public Map<String, Object> draftMetric(MetricDraftRequest request) {
        log.info("====== MCP工具 draftMetric 参数：{} ======", request);
        return metricDrafter.draft(request);
    }
}

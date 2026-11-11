package com.github.yingli.adhoc.request;

import lombok.Data;
import lombok.ToString;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * @Description 多块 Ad-hoc 请求：每个块是一条独立实体链，块之间不做 join。
 * 用于“会员和订单按门店、会员和明细按商品”这类同题多组合场景——
 * 各块保持单粒度，避免跨粒度 join 把指标乘翻。
 * @Author crm-agent
 */
@Data
@ToString
public class AdhocBatchRequest {

    @ToolParam(description = "多个独立查询块，按顺序执行并返回等长结果；每个块是一条实体链，"
            + "块之间不做 join。一次最多 5 个")
    private List<AdhocQueryRequest> queries;
}

package com.github.yingli.entity.request;

import lombok.Data;
import lombok.ToString;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * @Description CRM 会员总览查询入参。默认全局汇总，传入 station 时按该门店限定范围。
 * @Author crm-agent
 */
@Data
@ToString
public class MemberOverviewRequest {

    @ToolParam(description = "商户/租户编码：已不支持（2026-10-30 业务决策取消租户维度，统计聚焦门店），"
            + "字段仅为入参兼容保留，非空时工具直接返回不支持的错误", required = false)
    private String tenant;

    @ToolParam(description = "门店编码（会员分配门店 member_allot_store），可选。用于将会员总览限定到某个门店", required = false)
    private String station;
}

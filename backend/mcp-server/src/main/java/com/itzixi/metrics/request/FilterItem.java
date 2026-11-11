package com.itzixi.metrics.request;

import lombok.Data;
import lombok.ToString;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * @Description 指标查询的单个过滤条件。code 必须是指标声明的可选过滤编码，
 * value 在服务端参数化（column = ?）传入，绝不拼接进 SQL。
 * @Author crm-agent
 */
@Data
@ToString
public class FilterItem {

    @ToolParam(description = "过滤编码，取自指标声明的可选过滤（当前只有 STATION/SKU；租户维度已于 2026-10-30 取消，传入 TENANT 会被服务端拒绝）")
    private String code;

    @ToolParam(description = "过滤值，等值匹配；空值按不过滤处理")
    private String value;
}

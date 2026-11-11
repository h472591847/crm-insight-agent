package com.itzixi.adhoc.request;

import lombok.Data;
import lombok.ToString;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * @Description 过滤条件：preset（元数据条件模板）与 column/op/value(s)（结构化直传）二选一。
 * value 一律 ? 参数化；column 必须在实体 filterableColumns 白名单内。
 * @Author crm-agent
 */
@Data
@ToString
public class FilterSpec {

    /**
     * IN/NOT IN 去重后的最大值数量；resolveOfficeStores 复用同一上限。
     * 2026-11-10 由 50 提到 500：CRM 总部级 office.id=1 有 375 个门店编码且能正常查询，
     * 50 会让覆盖范围明显窄于 CRM。
     */
    public static final int MAX_IN_VALUES = 500;

    @ToolParam(description = "过滤列所属实体，缺省为 entities[0]", required = false)
    private String entity;

    @ToolParam(description = "引用条件模板 ID（与 column 二选一）", required = false)
    private String preset;

    @ToolParam(description = "结构化过滤列（白名单内）", required = false)
    private String column;

    @ToolParam(description = "操作符：= != > < >= <= LIKE IN NOT IN IS NULL IS NOT NULL", required = false)
    private String op;

    @ToolParam(description = "单值过滤值，等值/比较/LIKE 参数化传入；IN/NOT IN 与 IS NULL 类不需要", required = false)
    private String value;

    @ToolParam(description = "IN/NOT IN 的多值列表，去重后最多 500 个；仅 op=IN/NOT IN 时填写，value 必须留空；值含逗号也按一个完整值处理，不要拆分", required = false)
    private List<String> values;
}

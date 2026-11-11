package com.itzixi.metrics.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Description 指标结果列元数据：模型据此正确表述每个单元格（编码/别名/单位/是否派生）。
 * @Author crm-agent
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ColumnMeta {

    /** 指标内编码（维度/描述/度量/派生），rows 按此顺序排列 */
    private String code;
    /** SQL 列别名；派生列为 null */
    private String alias;
    /** 单位：CNY/COUNT/PCS 等，可空 */
    private String unit;
    /** 数据类型：NUMERIC / TEXT */
    private String dataType;
    /** 是否派生列（Java 计算，不在 SQL 中） */
    private boolean derived;
}

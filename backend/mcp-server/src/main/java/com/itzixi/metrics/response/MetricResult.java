package com.itzixi.metrics.response;

import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description 通用指标查询结果（替代"每个指标一个 Result DTO"）。
 * rows 按 columns 顺序排列；口径（caliber）与指纹（sqlHash）强制回显：
 * 任何数字都能反查到是哪条 SQL、按什么口径算出来的。
 * <p>
 * 空结果与失败必须区分：无数据时 errorCode=NO_DATA 并给出说明；
 * 参数非法/口径未确认/执行失败各用独立错误码，模型不得据此编造数字。
 * @Author crm-agent
 */
@Data
public class MetricResult {

    /** 指标编码 */
    private String metric;
    /** 中文指标名 */
    private String title;
    /** 列元数据（维度→描述→度量→派生，与 rows 单元格一一对应） */
    private List<ColumnMeta> columns;
    /** 数据行，按 columns 顺序 */
    private List<List<Object>> rows;
    /** 合计行（指标声明 withTotalRow 时非空；维度/描述单元格为 null） */
    private List<Object> totalRow;
    /** 统计范围回显（时间区间 + 过滤条件） */
    private String scope;
    /** 口径正文，强制回显 */
    private String caliber;
    /** 生成 SQL 的指纹，用于反查与审计 */
    private String sqlHash;
    /** 错误码：INVALID_PARAM | METRIC_NOT_FOUND | METRICS_NOT_CONFIRMED | NO_DATA | QUERY_FAILED；正常为 null */
    private String errorCode;
    /** 面向模型的可读说明；空结果与失败必须区分 */
    private String message;

    /**
     * 按编码取某一行单元格值（找不到返回 null，便于旧 DTO 适配层书写）。
     */
    public Object value(int rowIndex, String code) {
        Integer idx = columnIndex().get(code);
        if (idx == null || rows == null || rowIndex < 0 || rowIndex >= rows.size()) {
            return null;
        }
        List<Object> row = rows.get(rowIndex);
        return idx < row.size() ? row.get(idx) : null;
    }

    /**
     * 按编码取合计行单元格值。
     */
    public Object totalValue(String code) {
        Integer idx = columnIndex().get(code);
        if (idx == null || totalRow == null) {
            return null;
        }
        return idx < totalRow.size() ? totalRow.get(idx) : null;
    }

    public int rowCount() {
        return rows == null ? 0 : rows.size();
    }

    private Map<String, Integer> columnIndex() {
        Map<String, Integer> map = new HashMap<>();
        if (columns != null) {
            for (int i = 0; i < columns.size(); i++) {
                map.put(columns.get(i).getCode(), i);
            }
        }
        return map;
    }
}

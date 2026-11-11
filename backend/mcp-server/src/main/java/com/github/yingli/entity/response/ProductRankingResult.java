package com.github.yingli.entity.response;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * @Description 商品销售排行结果（基于消费明细表按 SKU 聚合）。
 * @Author crm-agent
 */
@Data
public class ProductRankingResult {

    /** 排行依据：sales 或 qty */
    private String rankBy;
    /** Top N 商品 */
    private List<ProductStat> items;
    /** 统计范围回显 */
    private String scope;
    /** 提示信息；正常为 null */
    private String message;

    @Data
    public static class ProductStat {
        /** SKU（商品代码 expense_d_gdcode） */
        private String sku;
        /** 商品名称 */
        private String productName;
        /** 商品类别名称（可能为空，类目口径待产品确认） */
        private String categoryName;
        /** 销售额（实付合计） */
        private BigDecimal salesAmount;
        /** 销量（数量合计） */
        private BigDecimal quantity;
    }
}

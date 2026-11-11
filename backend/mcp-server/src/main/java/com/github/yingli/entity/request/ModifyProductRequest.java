package com.github.yingli.entity.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * @author oliver
 * @Description: TODO
 * @Version 1.0
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class ModifyProductRequest {

    @ToolParam(description = "商品编号", required = false)
    private String productId;

    @ToolParam(description = "要更新成的【新】商品名称", required = false)
    private String productName;

    @ToolParam(description = "要更新成的【新】商品品牌", required = false)
    private String brand;

    @ToolParam(description = "要更新成的【新】商品价格", required = false)
    private Integer price;

    @ToolParam(description = "要更新成的【新】商品库存", required = false)
    private Integer stock;

    @ToolParam(description = "要更新成的【新】商品描述", required = false)
    private String description;

    @ToolParam(description = "要更新成的【新】商品状态(下架为0，上架为1，预售为2)", required = false)
    private Integer status;

}

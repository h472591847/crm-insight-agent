package com.github.yingli.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;

/**
 * 会员消费记录表
 * @TableName expense_calendar_info
 */
@TableName(value ="expense_calendar_info")
@Data
public class ExpenseCalendarInfo {
    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 消费用户手机号
     */
    @TableField(value = "expense_mobile")
    private String expense_mobile;

    /**
     * 消费商户
     */
    @TableField(value = "expense_tenant")
    private String expense_tenant;

    /**
     * 消费门店代码
     */
    @TableField(value = "expense_stationCode")
    private String expense_stationCode;

    /**
     * 消费门店名称
     */
    @TableField(value = "expense_stationName")
    private String expense_stationName;

    /**
     * 消费流水号
     */
    @TableField(value = "expense_orderId")
    private String expense_orderId;

    /**
     * 消费时间
     */
    @TableField(value = "expense_buyDate")
    private Date expense_buyDate;

    /**
     * 消费零售总金额
     */
    @TableField(value = "expense_stdtotal")
    private String expense_stdtotal;

    /**
     * 消费实付总金额
     */
    @TableField(value = "expense_realamt")
    private String expense_realamt;

    /**
     * 消费商品种类数量
     */
    @TableField(value = "expense_reccnt")
    private String expense_reccnt;

    /**
     * 机构id
     */
    @TableField(value = "office_id")
    private String office_id;

    /**
     * 消费类型1.线下2.线上
     */
    @TableField(value = "expense_type")
    private String expense_type;

    /**
     * 订单状态-10正常，20已退单
     */
    @TableField(value = "expense_state")
    private String expense_state;

    /**
     * 删除标记
     */
    @TableField(value = "del_flag")
    private String del_flag;

    /**
     * 创建时间
     */
    @TableField(value = "create_time")
    private Date create_time;

    /**
     * 修改时间
     */
    @TableField(value = "modify_time")
    private Date modify_time;

    /**
     * 预留1
     */
    @TableField(value = "reserved1")
    private String reserved1;

    /**
     * 预留2
     */
    @TableField(value = "reserved2")
    private String reserved2;

    /**
     * 预留3
     */
    @TableField(value = "reserved3")
    private String reserved3;

    /**
     * 预留4
     */
    @TableField(value = "reserved4")
    private String reserved4;

    /**
     * 预留5
     */
    @TableField(value = "reserved5")
    private String reserved5;

    /**
     * 预留6
     */
    @TableField(value = "reserved6")
    private String reserved6;

    /**
     * 预留7
     */
    @TableField(value = "reserved7")
    private String reserved7;

    /**
     * 预留8
     */
    @TableField(value = "reserved8")
    private String reserved8;

    /**
     * 预留9
     */
    @TableField(value = "reserved9")
    private String reserved9;

    /**
     * 预留10
     */
    @TableField(value = "reserved10")
    private String reserved10;

    /**
     * 营业员工号
     */
    @TableField(value = "userCode")
    private String userCode;

    /**
     * 营业员姓名
     */
    @TableField(value = "userName")
    private String userName;

    /**
     * 毛利
     */
    @TableField(value = "gross_margin")
    private String gross_margin;

    /**
     * 0.本部，1.饿了么，2.美团
     */
    @TableField(value = "platform_type")
    private String platform_type;
}
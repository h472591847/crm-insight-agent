package com.itzixi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;

/**
 * 会消费记录详情表
 * @TableName expense_calendar_details
 */
@TableName(value ="expense_calendar_details")
@Data
public class ExpenseCalendarDetails {
    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 消费用户手机号
     */
    @TableField(value = "expense_d_mobile")
    private String expense_d_mobile;

    /**
     * 消费门店代码
     */
    @TableField(value = "expense_d_stationCode")
    private String expense_d_stationCode;

    /**
     * 消费门店名称
     */
    @TableField(value = "expense_d_stationName")
    private String expense_d_stationName;

    /**
     * 消费流水号
     */
    @TableField(value = "expense_d_orderId")
    private String expense_d_orderId;

    /**
     * 消费时间
     */
    @TableField(value = "expense_d_buyDate")
    private Date expense_d_buyDate;

    /**
     * 每行消费零售总金额
     */
    @TableField(value = "expense_d_stdtotal")
    private String expense_d_stdtotal;

    /**
     * 每行消费实付总金额
     */
    @TableField(value = "expense_d_realamt")
    private String expense_d_realamt;

    /**
     * 商品行号
     */
    @TableField(value = "expense_d_itemno")
    private String expense_d_itemno;

    /**
     * 商品代码
     */
    @TableField(value = "expense_d_gdcode")
    private String expense_d_gdcode;

    /**
     * 商品名称
     */
    @TableField(value = "expense_d_commName")
    private String expense_d_commName;

    /**
     * 商品类别代码
     */
    @TableField(value = "expense_d_commCode")
    private String expense_d_commCode;

    /**
     * 商品类别名称
     */
    @TableField(value = "expense_d_commCodeName")
    private String expense_d_commCodeName;

    /**
     * 商品数量
     */
    @TableField(value = "expense_d_qty")
    private String expense_d_qty;

    /**
     * 消费时商品零售单价
     */
    @TableField(value = "expense_d_scrprice")
    private String expense_d_scrprice;

    /**
     * 机构id
     */
    @TableField(value = "office_id")
    private String office_id;

    /**
     * 消费类型1.线下2.线上
     */
    @TableField(value = "expense_d_type")
    private String expense_d_type;

    /**
     * 订单状态-10正常，20已退单
     */
    @TableField(value = "expense_d_state")
    private String expense_d_state;

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
     * 预留10
     */
    @TableField(value = "reserved10")
    private String reserved10;
}
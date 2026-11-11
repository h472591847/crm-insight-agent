package com.github.yingli.entity.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * @Description 会员总览结果。totalExpenseAmount 及计数字段由 Mapper 直接映射，
 *              scope / avgExpensePerActiveMember / message 由服务端计算或填充。
 * @Author crm-agent
 */
@Data
public class MemberOverviewResult {

    /** 有效会员总数（del_flag='0'） */
    private Long totalMembers;
    /** 男性会员数（member_sex=1） */
    private Long maleCount;
    /** 女性会员数（member_sex=2） */
    private Long femaleCount;
    /** 性别未知/为空数 */
    private Long unknownCount;
    /** 有消费记录的会员数（MEMBER_CONSUMPTION 的 BUYERS：有效订单 expense_state='10' AND del_flag='0' 下按 expense_mobile 去重），用于人均计算 */
    private Long expenseMemberCount;
    /** 会员累计消费总额（MEMBER_CONSUMPTION 的 TOTAL_EXPENSE：订单实付 expense_realamt 实时汇总，非 member_expense_totalsum 快照） */
    private BigDecimal totalExpenseAmount;

    /** 人均消费（= totalExpenseAmount / expenseMemberCount），服务端计算 */
    private BigDecimal avgExpensePerActiveMember;
    /** 统计范围回显（全局或按门店） */
    private String scope;
    /** 提示信息；正常为 null，参数非法或异常时说明原因 */
    private String message;
}

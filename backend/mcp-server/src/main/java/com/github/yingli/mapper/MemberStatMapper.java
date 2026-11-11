package com.github.yingli.mapper;

import com.github.yingli.entity.response.MemberOverviewResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * @Description 会员统计受控查询（只读聚合，不返回个人信息）。
 * <p>
 * 定位说明：同 {@link ExpenseStatMapper}，仅作为 Phase 1 手写 SQL 的对账基线保留；
 * 租户维度已于 2026-10-30 取消，tenant 入参始终传 null。
 * @Author crm-agent
 */
@Mapper
public interface MemberStatMapper {

    /**
     * 会员总览：总数 + 性别分布 + 累计消费汇总。
     * station 为空表示全局；传入则限定到该门店（不拆分返回多行）。
     *
     * @param tenant 已废弃（租户维度取消），调用方必须传 null
     */
    MemberOverviewResult memberOverview(@Param("tenant") String tenant,
                                        @Param("station") String station);
}

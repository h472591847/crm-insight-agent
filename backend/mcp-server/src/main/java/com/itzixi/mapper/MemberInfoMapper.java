package com.itzixi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itzixi.entity.MemberInfo;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * MemberInfoMapper — MyBatis Plus Mapper 接口 + 自定义模糊查询。
 * <p>
 * 额外提供两个门店查询方法，供 {@link com.itzixi.mcp.tool.GuessTool} 使用：
 * 前缀匹配优先、包含匹配兜底、GROUP BY 去重、LIMIT 20 兜底全表扫描。
 * 所有 SQL 统一走 member_info_view 视图，与项目其他统计口径（Adhoc/MemberOverview）保持一致。
 * </p>
 * <p>
 * 自定义 SQL 统一维护在 resources/mapper/MemberInfoMapper.xml，接口内不再保留注解 SQL。
 * </p>
 *
 * @author oliver
 */
public interface MemberInfoMapper extends BaseMapper<MemberInfo> {

    /**
     * 门店精确名称匹配（`= keyword` 或 `= keyword + '店'`）。
     * 用户给出的关键词已带城市/区前缀（如"哈市长江路"）时，精确命中可避免
     * 包含匹配把其他城市的同路段门店（如"辽宁海城区长江路店"）混进候选。
     *
     * @param keyword 门店名称关键词（不允许空字符串，由调用方校验）
     * @return 候选门店列表（最多 20 条），每条包含 stationCode / stationName / memberCount
     */
    List<Map<String, Object>> guessStationByExact(@Param("keyword") String keyword);

    /**
     * 门店前缀匹配（`keyword%`）。
     * 返回唯一门店编码 + 名称 + 会员数，按会员数倒序（大门店排前）。
     * 统一走 member_info_view，del_flag='0' 表示未删除的有效会员。
     *
     * @param keyword 门店名称关键词（不允许空字符串，由调用方校验）
     * @return 候选门店列表（最多 20 条），每条包含 stationCode / stationName / memberCount
     */
    List<Map<String, Object>> guessStationByPrefix(@Param("keyword") String keyword);

    /**
     * 门店包含匹配（`%keyword%`）—— 当前缀 0 条时兜底使用。
     * 统一走 member_info_view。
     *
     * @param keyword 门店名称关键词
     * @return 候选门店列表（最多 20 条）
     */
    List<Map<String, Object>> guessStationByContains(@Param("keyword") String keyword);

    /**
     * 会员手机号前缀匹配（`keyword%`）。
     * 统一走 member_info_view，del_flag='0' 表示未删除的有效会员。
     *
     * @param keyword 手机号前缀（至少 3 位，由调用方校验）
     * @return 候选会员列表（最多 20 条）
     */
    List<Map<String, Object>> guessMemberByMobile(@Param("keyword") String keyword);
}

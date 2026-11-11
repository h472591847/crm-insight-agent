package com.github.yingli.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** 机构到 CRM 分配门店编码的受控只读查询；不向模型开放 sys_office 自由查询。 */
@Mapper
public interface OfficeMapper {

    Map<String, Object> findOfficeById(@Param("officeId") String officeId);

    /** 精确名称匹配（=，不是 LIKE）：入参是服务端生成的确定变形，如 原名 / 原名+店 / 原名+门店。 */
    List<Map<String, Object>> findOfficesByExactNames(@Param("names") List<String> names);

    /** 候选名称匹配由服务端生成 pattern（前缀/包含），模型不能直接传 SQL 通配符。 */
    List<Map<String, Object>> findOfficeCandidatesByName(@Param("officeName") String officeName,
                                                         @Param("pattern") String pattern);

    /** CRM getHDStationIDs 同口径集合的基数；IN 是集合语义，因此按 HD_ID 去重。 */
    long countOfficeStoreCodes(@Param("officeId") String officeId);

    /** 仅在总数不超过 IN 上限时调用；limit 固定传上限值，避免大集合返回。 */
    List<String> listOfficeStoreCodes(@Param("officeId") String officeId, @Param("limit") int limit);

    /**
     * 会员表无匹配时的门店兜底：按可统计门店口径（type 6/11/22/28、8 位 HD_ID、未删除）
     * 精确匹配门店名称，入参是服务端生成的确定变形（原名 / 原名+店 / 原名+门店）。
     */
    List<Map<String, Object>> findFallbackStoresByExactNames(@Param("names") List<String> names);

    /** 会员表无匹配时的门店兜底：前缀/包含匹配，pattern 由服务端生成。 */
    List<Map<String, Object>> findFallbackStoresByName(@Param("officeName") String officeName,
                                                       @Param("pattern") String pattern);

    /** 该门店编码在有效订单（expense_state='10' AND del_flag='0'）中是否存在记录，返回 1/0。 */
    long countOrdersByStationCode(@Param("stationCode") String stationCode);
}

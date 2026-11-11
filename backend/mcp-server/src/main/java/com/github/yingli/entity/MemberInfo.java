package com.github.yingli.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;

/**
 * 会员信息表
 * @TableName member_info
 */
@TableName(value ="member_info")
@Data
public class MemberInfo {
    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 姓名
     */
    @TableField(value = "member_name")
    private String member_name;

    /**
     * 性别1男/2女
     */
    @TableField(value = "member_sex")
    private Integer member_sex;

    /**
     * 类别
     */
    @TableField(value = "member_sort")
    private String member_sort;

    /**
     * 地址
     */
    @TableField(value = "member_address")
    private String member_address;

    /**
     * 手机
     */
    @TableField(value = "member_mobile")
    private String member_mobile;

    /**
     * 会员创建日期
     */
    @TableField(value = "member_created")
    private Date member_created;

    /**
     * 微信卡号
     */
    @TableField(value = "member_cardno")
    private String member_cardno;

    /**
     * 注册门店代码
     */
    @TableField(value = "member_store")
    private String member_store;

    /**
     * 邀请人手机号
     */
    @TableField(value = "usermobile")
    private String usermobile;

    /**
     * 注册门店名称
     */
    @TableField(value = "member_stationName")
    private String member_stationName;

    /**
     * 分配门店代码（存 sys_office.HD_ID，8 位；与 office_id 不是同一字段）
     */
    @TableField(value = "member_allot_store")
    private String member_allot_store;

    /**
     * 分配门店名称
     */
    @TableField(value = "member_allot_stationName")
    private String member_allot_stationName;

    /**
     * 最近门店分配时间
     */
    @TableField(value = "member_allot_time")
    private Date member_allot_time;

    /**
     * 所属门店职员id
     */
    @TableField(value = "member_allot_staffId")
    private String member_allot_staffId;

    /**
     * 所属门店职员名称
     */
    @TableField(value = "member_allot_staffName")
    private String member_allot_staffName;

    /**
     * 最近分配给职员时间
     */
    @TableField(value = "member_allot_staffTime")
    private Date member_allot_staffTime;

    /**
     * 门店操作分配用户id
     */
    @TableField(value = "member_allot_storeUserId")
    private String member_allot_storeUserId;

    /**
     * 门店操作分配用户名称
     */
    @TableField(value = "member_allot_storeUserName")
    private String member_allot_storeUserName;

    /**
     * 是否分配到店员（1=已分配员工，2=未分配，空=不限）
     */
    @TableField(value = "isAllotStaff")
    private String isAllotStaff;

    /**
     * 是否分配门店（1=已分配门店，2=未分配）
     */
    @TableField(value = "member_is_allot_store")
    private String member_is_allot_store;

    /**
     * 商户
     */
    @TableField(value = "member_tenant")
    private String member_tenant;

    /**
     * 消费次数
     */
    @TableField(value = "member_expense_time")
    private Integer member_expense_time;

    /**
     * 消费总额（会员快照，非实时；金额统计请用订单实付实时汇总，勿与 CRM 18 个月快照混用）
     */
    @TableField(value = "member_expense_totalsum")
    private BigDecimal member_expense_totalsum;

    /**
     * 当前积分
     */
    @TableField(value = "member_now_integral")
    private Integer member_now_integral;

    /**
     * 累计积分
     */
    @TableField(value = "member_total_integral")
    private Integer member_total_integral;

    /**
     * 充值总额
     */
    @TableField(value = "member_changer_totalsum")
    private BigDecimal member_changer_totalsum;

    /**
     * 支付总额
     */
    @TableField(value = "member_ay_totalsum")
    private BigDecimal member_ay_totalsum;

    /**
     * 余额
     */
    @TableField(value = "member_banalce")
    private BigDecimal member_banalce;

    /**
     * 起始有效日期
     */
    @TableField(value = "member_start_time")
    private Date member_start_time;

    /**
     * 截止有效日期
     */
    @TableField(value = "member_end_time")
    private Date member_end_time;

    /**
     * 所属区域
     */
    @TableField(value = "member_area")
    private String member_area;

    /**
     * 工作单位
     */
    @TableField(value = "member_work_unit")
    private String member_work_unit;

    /**
     * 业余爱好
     */
    @TableField(value = "member_hobby")
    private String member_hobby;

    /**
     * 收入
     */
    @TableField(value = "member_income")
    private String member_income;

    /**
     * 喜爱品牌
     */
    @TableField(value = "member_love_brand")
    private String member_love_brand;

    /**
     * 阴历出生日期
     */
    @TableField(value = "member_yin_birthday")
    private String member_yin_birthday;

    /**
     * 阳历出生日期
     */
    @TableField(value = "member_yang_birthday")
    private Date member_yang_birthday;

    /**
     * 职务
     */
    @TableField(value = "member_duty")
    private String member_duty;

    /**
     * 机构id
     */
    @TableField(value = "office_id")
    private Long office_id;

    /**
     * 管理者id
     */
    @TableField(value = "user_id")
    private String user_id;

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
     * 是否删除1是2否
     */
    @TableField(value = "del_flag")
    private Integer del_flag;

    /**
     * 客户重要程度(维护级别)
     */
    @TableField(value = "member_importance")
    private String member_importance;

    /**
     * 粘合度
     */
    @TableField(value = "member_stickiness")
    private String member_stickiness;

    /**
     * 消费习惯
     */
    @TableField(value = "consumption_custom")
    private String consumption_custom;

    /**
     * 客户身份
     */
    @TableField(value = "member_nature")
    private String member_nature;

    /**
     * 客户级别
     */
    @TableField(value = "customer_level")
    private String customer_level;

    /**
     * 微信号
     */
    @TableField(value = "is_wechat_friend")
    private String is_wechat_friend;

    /**
     * 客户性质
     */
    @TableField(value = "member_quality")
    private String member_quality;

    /**
     * 消费习惯
     */
    @TableField(value = "expense_calendar")
    private String expense_calendar;

    /**
     * 置顶
     */
    @TableField(value = "up")
    private String up;

    /**
     * 参加活动
     */
    @TableField(value = "HD_totalsum")
    private String HD_totalsum;

    /**
     * 备注
     */
    @TableField(value = "remarks")
    private String remarks;

    /**
     * 最近消费门店
     */
    @TableField(value = "reserved1")
    private String reserved1;

    /**
     * 最近一次有效消费时间（取 expense_calendar_info 中 expense_state='10' 的最近 expense_buyDate）
     */
    @TableField(value = "reserved2")
    private String reserved2;

    /**
     * null,线下，1线上
     */
    @TableField(value = "reserved3")
    private String reserved3;

    /**
     * 最近被访问时间
     */
    @TableField(value = "reserved4")
    private String reserved4;

    /**
     * 最近被访问门店id
     */
    @TableField(value = "reserved5")
    private String reserved5;

    /**
     * 最近被访问门店名称
     */
    @TableField(value = "reserved6")
    private String reserved6;

    /**
     * 最近被访问的人
     */
    @TableField(value = "reserved7")
    private String reserved7;

    /**
     * 被回访总次数
     */
    @TableField(value = "reserved8")
    private String reserved8;

    /**
     * 最近访问人id
     */
    @TableField(value = "reserved9")
    private String reserved9;

    /**
     *  最后修改(机构)门店id
     */
    @TableField(value = "reserved10")
    private String reserved10;

    /**
     * 最后修改机构名称
     */
    @TableField(value = "reserved11")
    private String reserved11;

    /**
     * 最后修改人名称
     */
    @TableField(value = "reserved12")
    private String reserved12;

    /**
     * 最近消费门店id
     */
    @TableField(value = "reserved13")
    private String reserved13;

    /**
     * 预留14
     */
    @TableField(value = "reserved14")
    private String reserved14;

    /**
     * (该字段废弃，直接在海鼎接口中查询)
     */
    @TableField(value = "reserved15")
    private String reserved15;

    /**
     * 之前修改json
     */
    @TableField(value = "beforeJson")
    private String beforeJson;

    /**
     * 海鼎会员id
     */
    @TableField(value = "hd_member_id")
    private String hd_member_id;

    /**
     * 年龄
     */
    @TableField(value = "age")
    private Long age;

    /**
     * 积分
     */
    @TableField(value = "integral")
    private Long integral;

    /**
     * 储值金额
     */
    @TableField(value = "stored_amt")
    private BigDecimal stored_amt;

    /**
     * 联系地址类型
     */
    @TableField(value = "contact_address_type")
    private String contact_address_type;

    /**
     * 联系地址
     */
    @TableField(value = "contact_address")
    private String contact_address;

    /**
     * 受教育程度
     */
    @TableField(value = "schooling")
    private String schooling;

    /**
     * 顾客来源
     */
    @TableField(value = "customer_source")
    private String customer_source;

    /**
     * 单位采购/自用
     */
    @TableField(value = "company_purchase")
    private String company_purchase;

    /**
     * 用酒用途
     */
    @TableField(value = "alcohol_use")
    private String alcohol_use;

    /**
     * 行业类型
     */
    @TableField(value = "industry_type")
    private String industry_type;

    /**
     * 公司规模
     */
    @TableField(value = "company_scale")
    private String company_scale;

    /**
     * 消费总额层级
     */
    @TableField(value = "consume_hierarchy")
    private String consume_hierarchy;

    /**
     * 消费R值
     */
    @TableField(value = "consume_r")
    private String consume_r;

    /**
     * 首次消费门店
     */
    @TableField(value = "consume_store")
    private String consume_store;

    /**
     * 首次消费时间
     */
    @TableField(value = "consume_time")
    private Date consume_time;

    /**
     * 主要消费商品
     */
    @TableField(value = "consume_commodity")
    private String consume_commodity;

    /**
     * 公司级别（业务称毛利级别，18 个月；set_level 按 level_code=companyLevelA~G 分组，真库实测 21 个编码：
     * AA~AC、BA~BC、CA~CC、DA~DC、EA~EC、FA~FC、GA~GC；无中文名称映射）
     */
    @TableField(value = "company_level")
    private String company_level;

    /**
     * 城市级别
     */
    @TableField(value = "city_level")
    private String city_level;

    /**
     * 门店级别
     */
    @TableField(value = "store_level")
    private String store_level;

    /**
     * 个人级别
     */
    @TableField(value = "personal_level")
    private String personal_level;

    /**
     * 顾客价值（库内存中文标签，固定 8 值：一般保护/重要价值/一般挽留/一般发展/重要保护/一般价值/重要挽留/重要发展用户）
     */
    @TableField(value = "customer_cost")
    private String customer_cost;

    /**
     * 最近一个月消费次数
     */
    @TableField(value = "member_expense_time_lately")
    private Integer member_expense_time_lately;

    /**
     * 近一年半消费次数
     */
    @TableField(value = "member_expense_time_year")
    private Integer member_expense_time_year;

    /**
     * 近一年半消费金额（18 个月快照，由订单 expense_realamt 回写、非实时；快照子查询未加 expense_state='10'，可能含已退单金额）
     */
    @TableField(value = "member_expense_totalsum_year")
    private BigDecimal member_expense_totalsum_year;

    /**
     * 大客户经理手机号
     */
    @TableField(value = "account_manager_phone")
    private String account_manager_phone;

    /**
     * 大客户经理创建时间
     */
    @TableField(value = "account_manager_phone_time")
    private Date account_manager_phone_time;

    /**
     * 一年半销售级别（销售等级，18 个月；set_level 真库实测 17 个：AA~AC、BA~BC、CA~CC、DA~DC、EA~EC、FA~FB）
     */
    @TableField(value = "half_year_level")
    private String half_year_level;

    /**
     * 总销售级别（销售等级，全部时间；set_level 真库实测 24 个：AA~AC 至 HA~HC）
     */
    @TableField(value = "total_year_level")
    private String total_year_level;

    /**
     * 近一年半消费毛利
     */
    @TableField(value = "gross_margin_year")
    private Long gross_margin_year;

    /**
     * 开单人ID
     */
    @TableField(value = "billing_people_id")
    private String billing_people_id;

    /**
     * 开单时间
     */
    @TableField(value = "billing_date")
    private Date billing_date;

    /**
     * plus 会员标识（1=PLUS；其余取值含 NULL 一律为非 PLUS，页面“否”实现为 plus_type IS NULL，不是 =0）
     */
    @TableField(value = "plus_type")
    private String plus_type;

    /**
     * 跟踪状态：CRM 页面筛选 0=否 / 1=是（要客/被跟踪）；取消要客流程会出现 2，NULL 也可能存在
     */
    @TableField(value = "track")
    private Integer track;

    /**
     * 跟踪人ID
     */
    @TableField(value = "track_user_id")
    private String track_user_id;

    /**
     * 跟踪取消原因
     */
    @TableField(value = "track_reason")
    private String track_reason;

    /**
     * 追踪时间
     */
    @TableField(value = "track_time")
    private Date track_time;

    /**
     * 未跟踪原因
     */
    @TableField(value = "track_cancel_reason")
    private String track_cancel_reason;

    /**
     * 跟踪类型（字典）
     */
    @TableField(value = "track_cancel")
    private String track_cancel;

    /**
     * 未跟踪时间
     */
    @TableField(value = "track_cancel_time")
    private Date track_cancel_time;

    /**
     * 联系地址1
     */
    @TableField(value = "contact_address1")
    private String contact_address1;

    /**
     * 联系地址2
     */
    @TableField(value = "contact_address2")
    private String contact_address2;

    /**
     * 发票路径集合
     */
    @TableField(value = "invoice_url")
    private String invoice_url;

    /**
     * 微信ID
     */
    @TableField(value = "wechat_id")
    private String wechat_id;

    /**
     * 会员注册来源渠道（只支持 1 饿百 / 2 美团 / 3 京东到家；真库另有 1 条 '4'，
     * 不做映射、不纳入字典，按未知/空值处理）
     */
    @TableField(value = "channel_type")
    private String channel_type;

    /**
     * 0正常1池中
     */
    @TableField(value = "importan_pond_user_type")
    private Integer importan_pond_user_type;

    /**
     * 入池时间
     */
    @TableField(value = "importan_pond_user_date")
    private Date importan_pond_user_date;

    /**
     * 系统入池说明
     */
    @TableField(value = "importan_pond_user_val")
    private String importan_pond_user_val;

    /**
     * 是否废弃会员1废弃
     */
    @TableField(value = "discard_type")
    private Integer discard_type;

    /**
     * 1区域
     */
    @TableField(value = "is_region")
    private String is_region;

    /**
     * 配偶生日
     */
    @TableField(value = "spouse_birthday")
    private Date spouse_birthday;

    /**
     * 配偶单位
     */
    @TableField(value = "spouse_company")
    private String spouse_company;

    /**
     * 孩子生日
     */
    @TableField(value = "children_birthday")
    private Date children_birthday;

    /**
     * 孩子学校
     */
    @TableField(value = "children_school")
    private String children_school;

    /**
     * puls到期时间
     */
    @TableField(value = "plus_expire_time")
    private Date plus_expire_time;

    /**
     * 1加盟
     */
    @TableField(value = "is_franchise")
    private Integer is_franchise;

    /**
     * 车辆品牌KEY
     */
    @TableField(value = "car_brand_key")
    private Integer car_brand_key;

    /**
     * 车辆品牌名称
     */
    @TableField(value = "car_brand_value")
    private String car_brand_value;

    /**
     * 车辆等级KEY
     */
    @TableField(value = "car_level_key")
    private Integer car_level_key;

    /**
     * 车辆等级名称
     */
    @TableField(value = "car_level_value")
    private String car_level_value;

    /**
     * 是否拓客,1拓客
     */
    @TableField(value = "is_toker")
    private Integer is_toker;

    /**
     * 0先会员，1后车辆
     */
    @TableField(value = "member_is_car")
    private Integer member_is_car;

    /**
     * plus会员类型0:198,5:99,7:298
     */
    @TableField(value = "plus_vip_type")
    private Integer plus_vip_type;

    /**
     * 1过期PLUS
     */
    @TableField(value = "expired_plus")
    private Integer expired_plus;

    /**
     * 年度采购量
     */
    @TableField(value = "year_procurement")
    private String year_procurement;

    /**
     * 新会员等级名称
     */
    @TableField(value = "new_member_level_value")
    private String new_member_level_value;

    /**
     * 新会员等级标记
     */
    @TableField(value = "new_member_level_key")
    private String new_member_level_key;
}
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CRM Mock 数据生成器（完全虚构，无任何真实个人信息）

生成与真实 CRM 表结构对齐的建表语句 + 脱敏演示数据，写入 mock-data/init/：
  01_schema.sql     建表 DDL（member_info / member_info_view / expense_calendar_info /
                    expense_calendar_details / sys_office，字段类型与真实 DDL 对齐）
  02_mock_data.sql  虚构数据 INSERT

数据特征与真实库一致，保证演示与对账场景成立：
  - 金额列为 VARCHAR（真实库即如此，编译层自动 NUM() 转换）
  - 订单状态 expense_state：10 正常 / 20 已退单（退单行金额为负，自然抵消）
  - del_flag：0 有效 / 1 删除
  - member_allot_store 存门店 8 位 HD_ID（对应 sys_office），少量为空
  - 要客 track：0/1，少量 2（取消流程）；track_cancel_time 非空
  - plus_type 仅 NULL/'1'；channel_type 仅 NULL/'1'/'2'/'3'
  - 会员快照统计字段（member_expense_time / member_expense_totalsum / ..._year / reserved2）
    由订单数据回写计算，与订单表对账一致
  - 手机号/姓名/地址全部虚构

用法：
  python generate_mock_data.py                # 默认规模：3000 会员 / 12000 订单
  python generate_mock_data.py --scale small  # 小规模：500 会员 / 2000 订单
  python generate_mock_data.py --members 8000 --orders 30000
"""

import argparse
import os
import random
from datetime import datetime, timedelta

SEED = 42
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "init")

# ---------------------------------------------------------------- 基础词库
SURNAMES = ["王", "李", "张", "刘", "陈", "杨", "赵", "黄", "周", "吴",
            "徐", "孙", "胡", "朱", "高", "林", "何", "郭", "马", "罗"]
GIVEN = ["伟", "芳", "娜", "敏", "静", "磊", "军", "洋", "勇", "艳",
         "杰", "涛", "明", "超", "秀英", "霞", "平", "刚", "桂英", "文轩",
         "雨欣", "志强", "晓东", "雪梅", "建国", "丽华", "晨曦", "子涵"]

STAFF_NAMES = ["张店长", "李导购", "王导购", "刘导购", "陈导购", "杨导购",
               "赵导购", "周导购", "吴导购", "孙导购"]

# 门店机构树：总部 -> 大区 -> 门店（type 6/11/22/28 直营/加盟），另含渠道店/要客部/新媒体（不入统计）
OFFICES = [
    # (id, parent_id, parent_ids, name, type, HD_ID)
    (1, 0, "", "演示总部", "1", ""),
    (10, 1, ",1,", "华东大区", "2", ""),
    (11, 1, ",1,", "华南大区", "2", ""),
    (13, 1, ",1,", "要客部", "13", ""),
    (14, 1, ",1,", "新媒体部", "14", ""),
    (18, 1, ",1,", "外卖渠道店", "18", ""),
    (100, 10, ",1,10,", "人民路店", "6", "81000101"),
    (101, 10, ",1,10,", "长江路店", "6", "81000102"),
    (102, 10, ",1,10,", "解放路店", "11", "81000103"),
    (103, 10, ",1,10,", "文化路店", "22", "81000104"),
    (104, 11, ",1,11,", "中山大道店", "6", "81000201"),
    (105, 11, ",1,11,", "滨江路店", "11", "81000202"),
    (106, 11, ",1,11,", "步行街店", "22", "81000203"),
    (107, 11, ",1,11,", "科技园店", "28", "81000204"),
]
STORES = [o for o in OFFICES if o[4] in ("6", "11", "22", "28")]  # 可统计门店
STORE_TYPES = {6: "直营", 11: "直营", 22: "加盟", 28: "加盟"}

PRODUCTS = [
    # (gdcode, commName, commCode, commCodeName, price)
    ("G001", "赤霞珠干红葡萄酒 750ml", "C01", "葡萄酒", "128.00"),
    ("G002", "长相思白葡萄酒 750ml", "C01", "葡萄酒", "98.00"),
    ("G003", "起泡白葡萄酒 750ml", "C01", "葡萄酒", "158.00"),
    ("G004", "珍藏干红 750ml", "C01", "葡萄酒", "268.00"),
    ("G005", "精酿啤酒 500ml", "C02", "啤酒", "12.00"),
    ("G006", "小麦白啤 450ml", "C02", "啤酒", "15.00"),
    ("G007", "果味啤酒 330ml", "C02", "啤酒", "9.90"),
    ("G008", "酱香型白酒 500ml", "C03", "白酒", "328.00"),
    ("G009", "浓香型白酒 500ml", "C03", "白酒", "188.00"),
    ("G010", "清酒 720ml", "C04", "洋酒", "198.00"),
    ("G011", "威士忌 700ml", "C04", "洋酒", "458.00"),
    ("G012", "白兰地 700ml", "C04", "洋酒", "388.00"),
    ("G013", "梅子酒 350ml", "C05", "果酒", "68.00"),
    ("G014", "柚子酒 350ml", "C05", "果酒", "58.00"),
    ("G015", "气泡水 330ml", "C06", "饮料", "5.50"),
    ("G016", "鲜榨橙汁 300ml", "C06", "饮料", "12.00"),
    ("G017", "苏打水 500ml", "C06", "饮料", "6.00"),
    ("G018", "坚果礼盒 400g", "C07", "零食", "78.00"),
    ("G019", "牛肉干 200g", "C07", "零食", "45.00"),
    ("G020", "巧克力 120g", "C07", "零食", "32.00"),
]
HAIDING_LEVELS = ["高级会员", "VIP", "至尊VIP", "PLUS会员", "V级企业VIP",
                  "白金VIP", "VV级企业VIP", "企业VIP", "喔淘会员"]
NEW_MEMBER_LEVELS = ["初享", "悦享", "优享", "荣享", "臻享", "尊享", "鼎享"]
CUSTOMER_COSTS = ["一般保护", "重要价值", "一般挽留", "一般发展",
                  "重要保护", "一般价值", "重要挽留", "重要发展用户"]
COMPANY_LEVELS = [p + s for p in "ABCDEFG" for s in "ABC"]
HALF_YEAR_LEVELS = [p + s for p in "ABCDEF" for s in "ABC"][:17]
TOTAL_YEAR_LEVELS = [p + s for p in "ABCDEFGH" for s in "ABC"][:24]


def make_phone(rng, used):
    while True:
        p = "13%08d" % rng.randrange(0, 99999999)
        if p not in used:
            used.add(p)
            return p


def fmt_dt(dt):
    return dt.strftime("%Y-%m-%d %H:%M:%S")


def fmt_d(dt):
    return dt.strftime("%Y-%m-%d")


def batch_inserts(rows, cols, table, batch=500):
    """多行 INSERT，值全部内联（生成物为本地演示 SQL，导入端受 docker init 流程信任边界保护）"""
    out = []
    for i in range(0, len(rows), batch):
        chunk = rows[i:i + batch]
        values = ",\n".join("(" + ",".join(str(v) if v is not None else "NULL" for v in r) + ")"
                            for r in chunk)
        out.append(f"INSERT INTO `{table}` ({','.join('`%s`' % c for c in cols)}) VALUES\n{values};")
    return "\n\n".join(out)


def sq(s):
    """字符串转 SQL 字面量"""
    if s is None:
        return None
    return "'" + str(s).replace("\\", "\\\\").replace("'", "''") + "'"


def num(s):
    """VARCHAR 金额列写入"""
    return sq(s)


def build_schema_sql():
    """建表 DDL：与真实 CRM DDL 对齐（金额 VARCHAR / 状态 varchar / del_flag），含常用索引"""
    return """-- ============================================================
-- CRM Mock 数据库 Schema（结构对齐真实 DDL，数据为虚构）
-- 由 mock-data/generate_mock_data.py 生成，请勿手工编辑
-- ============================================================

CREATE DATABASE IF NOT EXISTS `crmtest` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
USE `crmtest`;

-- 门店/机构字典（映射 sys_office 关键列：HD_ID 8位、type 门店口径 6/11/22/28，排除 13 要客部/14 新媒体/18 渠道店）
CREATE TABLE IF NOT EXISTS `sys_office` (
  `id` bigint NOT NULL COMMENT '机构ID',
  `parent_id` bigint DEFAULT '0' COMMENT '父级ID',
  `parent_ids` varchar(1000) DEFAULT NULL COMMENT '所有父级编号（逗号包裹，如 ,1,10,）',
  `name` varchar(100) DEFAULT NULL COMMENT '机构名称',
  `type` varchar(10) DEFAULT NULL COMMENT '类型：1总部 2大区 6/11/22/28门店 13要客部 14新媒体 18渠道店',
  `HD_ID` varchar(10) DEFAULT NULL COMMENT '海鼎门店编码（8位）',
  `del_flag` char(1) DEFAULT '0' COMMENT '删除标记',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `index_hd_id` (`HD_ID`),
  KEY `index_parent_ids` (`parent_ids`(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='机构字典';

-- 会员信息表（列对齐真实 DDL；此处仅保留业务引用列 + 常用列，其余真实列不建）
CREATE TABLE IF NOT EXISTS `member_info` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_name` varchar(500) DEFAULT NULL COMMENT '姓名（虚构）',
  `member_sex` tinyint DEFAULT NULL COMMENT '性别1男/2女',
  `member_mobile` varchar(100) DEFAULT NULL COMMENT '手机（虚构号段）',
  `member_created` datetime DEFAULT NULL COMMENT '会员创建日期',
  `member_cardno` varchar(200) DEFAULT NULL COMMENT '会员卡号',
  `member_store` varchar(200) DEFAULT NULL COMMENT '注册门店代码(HD_ID)',
  `member_stationName` varchar(200) DEFAULT NULL COMMENT '注册门店名称',
  `member_allot_store` varchar(255) DEFAULT NULL COMMENT '分配门店代码(HD_ID)',
  `member_allot_stationName` varchar(255) DEFAULT NULL COMMENT '分配门店名称',
  `member_allot_time` datetime DEFAULT NULL COMMENT '最近门店分配时间',
  `member_allot_staffId` varchar(255) DEFAULT NULL COMMENT '所属门店职员id',
  `member_allot_staffName` varchar(255) DEFAULT NULL COMMENT '所属门店职员名称',
  `member_allot_staffTime` datetime DEFAULT NULL COMMENT '最近分配给职员时间',
  `isAllotStaff` varchar(255) DEFAULT '2' COMMENT '是否分配到店员(1是2否)',
  `member_is_allot_store` varchar(255) DEFAULT NULL COMMENT '是否分配门店(1是2否)',
  `member_tenant` varchar(200) DEFAULT NULL COMMENT '商户（真实库为空）',
  `member_expense_time` int DEFAULT NULL COMMENT '消费次数（快照）',
  `member_expense_totalsum` decimal(20,2) DEFAULT NULL COMMENT '消费总额（快照）',
  `member_expense_time_lately` int DEFAULT NULL COMMENT '最近一个月消费次数',
  `member_expense_time_year` int DEFAULT NULL COMMENT '近一年半消费次数',
  `member_expense_totalsum_year` decimal(20,2) DEFAULT NULL COMMENT '近一年半消费金额（快照）',
  `member_yin_birthday` varchar(200) DEFAULT NULL COMMENT '阴历出生日期(YYYY-M-D)',
  `office_id` bigint DEFAULT '0' COMMENT '机构id',
  `del_flag` tinyint DEFAULT '0' COMMENT '0有效/1删除',
  `member_importance` varchar(255) DEFAULT NULL COMMENT '客户重要程度',
  `consume_hierarchy` varchar(100) DEFAULT NULL COMMENT '消费总额层级（真实库为空）',
  `customer_level` varchar(255) DEFAULT NULL COMMENT '客户级别（真实库为空）',
  `company_level` varchar(10) DEFAULT NULL COMMENT '公司级别（编码 AA~GC）',
  `city_level` varchar(10) DEFAULT NULL COMMENT '城市级别（A~H）',
  `store_level` varchar(10) DEFAULT NULL COMMENT '门店级别（A~H）',
  `personal_level` varchar(10) DEFAULT NULL COMMENT '个人级别（a~f）',
  `half_year_level` varchar(10) DEFAULT NULL COMMENT '一年半销售级别',
  `total_year_level` varchar(10) DEFAULT NULL COMMENT '总销售级别',
  `customer_cost` varchar(10) DEFAULT NULL COMMENT '顾客价值（中文标签）',
  `new_member_level_value` varchar(50) DEFAULT NULL COMMENT '新会员等级名称',
  `new_member_level_key` varchar(50) DEFAULT NULL COMMENT '新会员等级标记',
  `reserved15` varchar(300) DEFAULT NULL COMMENT '海鼎级别（中文标签）',
  `reserved2` varchar(300) DEFAULT NULL COMMENT '最近一次有效消费时间',
  `track` int(1) unsigned zerofill DEFAULT '0' COMMENT '跟踪 0未跟踪 1跟踪 2取消流程',
  `track_user_id` varchar(200) DEFAULT NULL COMMENT '跟踪人ID',
  `track_time` datetime DEFAULT NULL COMMENT '追踪时间',
  `track_cancel_time` datetime DEFAULT NULL COMMENT '未跟踪时间',
  `track_cancel_reason` varchar(255) DEFAULT NULL COMMENT '未跟踪原因',
  `plus_type` varchar(10) DEFAULT NULL COMMENT 'PLUS标识(仅1/NULL)',
  `channel_type` varchar(10) DEFAULT NULL COMMENT '渠道(1饿百/2美团/3京东到家)',
  `importan_pond_user_type` int(1) unsigned zerofill DEFAULT '0' COMMENT '0正常1池中',
  `age` bigint DEFAULT NULL COMMENT '年龄（真实库基本为空）',
  PRIMARY KEY (`id`),
  KEY `index_mobile` (`member_mobile`),
  KEY `index_name` (`member_name`(191)),
  KEY `index_sex` (`member_sex`),
  KEY `index_store` (`member_store`),
  KEY `index_created` (`member_created`),
  KEY `index_allot_store` (`del_flag`,`member_allot_store`),
  KEY `index_track` (`track`),
  KEY `index_reserved2` (`reserved2`(191)),
  KEY `index_company_level` (`company_level`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='会员信息表';

-- 会员信息视图（真实库为触发器同步的物理表；Mock 场景由 02_mock_data.sql 直接从 member_info 复制）
CREATE TABLE IF NOT EXISTS `member_info_view` LIKE `member_info`;

-- 会员消费记录表（订单头；金额 VARCHAR 对齐真实库）
CREATE TABLE IF NOT EXISTS `expense_calendar_info` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `expense_mobile` varchar(255) DEFAULT NULL COMMENT '消费用户手机号',
  `expense_tenant` varchar(255) DEFAULT NULL COMMENT '消费商户',
  `expense_stationCode` varchar(500) DEFAULT NULL COMMENT '消费门店代码(HD_ID)',
  `expense_stationName` varchar(255) DEFAULT NULL COMMENT '消费门店名称',
  `expense_orderId` varchar(500) DEFAULT NULL COMMENT '消费流水号',
  `expense_buyDate` datetime DEFAULT NULL COMMENT '消费时间',
  `expense_stdtotal` varchar(255) DEFAULT NULL COMMENT '消费零售总金额(VARCHAR)',
  `expense_realamt` varchar(500) DEFAULT NULL COMMENT '消费实付总金额(VARCHAR,退单为负)',
  `expense_reccnt` varchar(255) DEFAULT NULL COMMENT '消费商品种类数量',
  `office_id` varchar(255) DEFAULT NULL COMMENT '机构id',
  `expense_type` varchar(100) DEFAULT NULL COMMENT '消费类型1线下2线上',
  `expense_state` varchar(100) DEFAULT '10' COMMENT '订单状态-10正常，20已退单',
  `del_flag` char(1) DEFAULT '0' COMMENT '删除标记',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '创建时间',
  `modify_time` datetime DEFAULT NULL COMMENT '修改时间',
  `userCode` varchar(255) DEFAULT NULL COMMENT '营业员工号',
  `userName` varchar(255) DEFAULT NULL COMMENT '营业员姓名',
  `gross_margin` varchar(255) DEFAULT NULL COMMENT '毛利',
  `platform_type` varchar(10) DEFAULT '0' COMMENT '0本部，1饿了么，2美团',
  PRIMARY KEY (`id`),
  KEY `index_mobile` (`expense_mobile`),
  KEY `index_stationCode` (`expense_stationCode`(191)),
  KEY `index_orderId` (`expense_orderId`(191)),
  KEY `index_buyDate` (`expense_buyDate`),
  KEY `index_state` (`expense_state`),
  KEY `index_buyDate_id_mobile` (`expense_mobile`,`expense_buyDate`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='会员消费记录表';

-- 会员消费记录详情表（SKU 行；金额 VARCHAR 对齐真实库）
CREATE TABLE IF NOT EXISTS `expense_calendar_details` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `expense_d_mobile` varchar(255) DEFAULT NULL COMMENT '消费用户手机号',
  `expense_d_stationCode` varchar(500) DEFAULT NULL COMMENT '消费门店代码(HD_ID)',
  `expense_d_stationName` varchar(255) DEFAULT NULL COMMENT '消费门店名称',
  `expense_d_orderId` varchar(500) DEFAULT NULL COMMENT '订单流水号',
  `expense_d_buyDate` datetime DEFAULT NULL COMMENT '消费时间',
  `expense_d_stdtotal` varchar(255) DEFAULT NULL COMMENT '每行零售金额(VARCHAR)',
  `expense_d_realamt` varchar(500) DEFAULT NULL COMMENT '每行实付金额(VARCHAR)',
  `expense_d_itemno` varchar(255) DEFAULT NULL COMMENT '商品行号',
  `expense_d_gdcode` varchar(255) DEFAULT NULL COMMENT '商品代码',
  `expense_d_commName` varchar(255) DEFAULT NULL COMMENT '商品名称',
  `expense_d_commCode` varchar(255) DEFAULT NULL COMMENT '商品类别代码',
  `expense_d_commCodeName` varchar(255) DEFAULT NULL COMMENT '商品类别名称',
  `expense_d_qty` varchar(255) DEFAULT NULL COMMENT '商品数量(VARCHAR)',
  `expense_d_scrprice` varchar(255) DEFAULT NULL COMMENT '商品零售单价(VARCHAR)',
  `office_id` varchar(255) DEFAULT NULL COMMENT '机构id',
  `expense_d_type` varchar(100) DEFAULT NULL COMMENT '消费类型1线下2线上',
  `expense_d_state` varchar(100) DEFAULT '10' COMMENT '订单状态-10正常，20已退单',
  `del_flag` char(1) DEFAULT '0' COMMENT '删除标记',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '创建时间',
  `userCode` varchar(255) DEFAULT NULL COMMENT '营业员工号',
  `userName` varchar(255) DEFAULT NULL COMMENT '营业员姓名',
  PRIMARY KEY (`id`),
  KEY `index_mobile` (`expense_d_mobile`),
  KEY `index_stationCode` (`expense_d_stationCode`(191)),
  KEY `index_orderId` (`expense_d_orderId`(191)),
  KEY `index_buyDate` (`expense_d_buyDate`),
  KEY `index_state` (`expense_d_state`),
  KEY `index_gdcode` (`expense_d_gdcode`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='会员消费记录详情表';
"""


def generate(members_n, orders_n):
    rng = random.Random(SEED)
    now = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)

    store_by_hd = {o[5]: o for o in STORES}
    hd_ids = [o[5] for o in STORES]
    staff_by_store = {o[5]: [("SF%03d" % (o[0] * 10 + i + 1), STAFF_NAMES[(o[0] + i) % len(STAFF_NAMES)])
                             for i in range(2)] for o in STORES}

    # ---------------- 会员 ----------------
    used_phones = set()
    members = []  # (dict)
    for i in range(1, members_n + 1):
        phone = make_phone(rng, used_phones)
        reg_store = rng.choice(STORES)
        # 分配门店：88% 同注册门店，10% 其他门店，2% 为空（演示空值语义）
        r = rng.random()
        if r < 0.88:
            allot = reg_store
        elif r < 0.98:
            allot = rng.choice(STORES)
        else:
            allot = None
        created = now - timedelta(days=rng.randrange(30, 1100), hours=rng.randrange(0, 24))
        sex = rng.choice([1, 1, 2, 2, 2, None])          # 少量未知性别
        track = rng.choice([0] * 12 + [1] * 3 + [2])      # ~20% 要客，少量取消
        staff = None
        if allot:
            staff = rng.choice(staff_by_store[allot[5]])
        track_user = staff[0] if (track == 1 and staff) else None
        track_time = now - timedelta(days=rng.randrange(1, 400)) if track == 1 else None
        track_cancel = now - timedelta(days=rng.randrange(1, 200)) if track == 2 else None
        plus = "1" if rng.random() < 0.18 else None
        channel = rng.choice([None] * 20 + ["1", "2", "3"])
        yin_birthday = "%d-%d-%d" % (rng.randrange(1965, 2005), rng.randrange(1, 13), rng.randrange(1, 29))
        staff_name = staff[1] if staff else None
        members.append({
            "_raw_mobile": phone,  # 未转义原始值，订单生成时用（避免二次 sq 导致手机号含多余单引号）
            "member_name": sq(rng.choice(SURNAMES) + rng.choice(GIVEN) + (str(i) if rng.random() < 0.3 else "")),
            "member_sex": sex,
            "member_mobile": sq(phone),
            "member_created": sq(fmt_dt(created)),
            "member_cardno": sq("CARD%08d" % i),
            "member_store": sq(reg_store[5]),
            "member_stationName": sq(reg_store[3]),
            "member_allot_store": sq(allot[5]) if allot else None,
            "member_allot_stationName": sq(allot[3]) if allot else None,
            "member_allot_time": sq(fmt_dt(created + timedelta(days=5))) if allot else None,
            "member_allot_staffId": sq(staff[0]) if staff else None,
            "member_allot_staffName": sq(staff_name) if staff else None,
            "member_allot_staffTime": sq(fmt_dt(created + timedelta(days=5))) if staff else None,
            "isAllotStaff": sq("1") if staff else sq("2"),
            "member_is_allot_store": sq("1") if allot else sq("2"),
            "member_tenant": None,
            "member_expense_time": 0,           # 回写阶段填充
            "member_expense_totalsum": None,
            "member_expense_time_lately": 0,
            "member_expense_time_year": 0,
            "member_expense_totalsum_year": None,
            "member_yin_birthday": sq(yin_birthday),
            "office_id": reg_store[0],
            "del_flag": 1 if rng.random() < 0.03 else 0,   # ~3% 删除
            "member_importance": sq(str(rng.randrange(1, 10))) if rng.random() < 0.2 else None,
            "consume_hierarchy": None,
            "customer_level": None,
            "company_level": sq(rng.choice(COMPANY_LEVELS)) if rng.random() < 0.8 else None,
            "city_level": sq(rng.choice("ABCDEFGH")) if rng.random() < 0.7 else None,
            "store_level": sq(rng.choice("ABCDEFGH")) if rng.random() < 0.6 else None,
            "personal_level": sq(rng.choice("abcdef")) if rng.random() < 0.1 else None,
            "half_year_level": sq(rng.choice(HALF_YEAR_LEVELS)) if rng.random() < 0.75 else None,
            "total_year_level": sq(rng.choice(TOTAL_YEAR_LEVELS)) if rng.random() < 0.8 else None,
            "customer_cost": sq(rng.choice(CUSTOMER_COSTS)) if rng.random() < 0.7 else None,
            "new_member_level_value": sq(rng.choice(NEW_MEMBER_LEVELS)) if rng.random() < 0.85 else None,
            "new_member_level_key": sq("L%d" % rng.randrange(1, 8)) if rng.random() < 0.85 else None,
            "reserved15": sq(rng.choice(HAIDING_LEVELS)) if rng.random() < 0.4 else None,
            "reserved2": None,                  # 回写阶段填充
            "track": track,
            "track_user_id": sq(track_user) if track_user else None,
            "track_time": sq(fmt_dt(track_time)) if track_time else None,
            "track_cancel_time": sq(fmt_dt(track_cancel)) if track_cancel else None,
            "track_cancel_reason": sq("370天未出单" if track_cancel else None),
            "plus_type": plus,
            "channel_type": channel,
            "importan_pond_user_type": 1 if rng.random() < 0.1 else 0,
            "age": None,
        })
    member_by_phone = {m["member_mobile"]: m for m in members}

    # ---------------- 订单 + 明细 ----------------
    orders = []
    details = []
    order_seq = 0
    detail_seq = 0
    valid_orders_by_phone = {}   # 快照回写：手机号 -> [(date, realamt)]
    unknown_rate = 0.15          # 未注册会员手机号占比（演示"仅关联有效会员"口径）

    for _ in range(orders_n):
        order_seq += 1
        # 85% 订单落在近 24 个月，其中 40% 集中在近 3 个月（活跃口径演示）
        if rng.random() < 0.40:
            buy_date = now - timedelta(days=rng.randrange(0, 90), minutes=rng.randrange(0, 1440))
        else:
            buy_date = now - timedelta(days=rng.randrange(90, 730), minutes=rng.randrange(0, 1440))
        store = rng.choice(STORES)
        staff = rng.choice(staff_by_store[store[5]])
        platform = rng.choice(["0"] * 14 + ["1", "2"])
        exp_type = "2" if platform != "0" else ("1" if rng.random() < 0.85 else "2")

        if rng.random() < unknown_rate:
            mobile = make_phone(rng, used_phones)           # 未注册手机号：用原始值，orders tuple 里统一 sq()
        else:
            m = rng.choice(members)
            mobile = m["_raw_mobile"]                       # 已注册会员：取原始值（避免对已 sq'd 的 member_mobile 二次转义）

        # 7% 退单：成对生成原单（正常）+ 退单（负金额）
        is_refund = rng.random() < 0.07
        state = "20" if is_refund else "10"

        n_kinds = rng.randrange(1, 5)
        picked = rng.sample(PRODUCTS, n_kinds)
        std_total, real_total, reccnt = 0.0, 0.0, 0
        oid = "MOCK%s%06d" % (buy_date.strftime("%Y%m%d"), order_seq)

        for line_no, p in enumerate(picked, start=1):
            qty = rng.randrange(1, 4)
            line_real = float(p[4]) * qty * (1 if not is_refund else -1)
            line_std = float(p[4]) * qty
            detail_seq += 1
            details.append((
                sq(mobile), sq(store[5]), sq(store[3]), sq(oid), sq(fmt_dt(buy_date)),
                num("%.2f" % line_std), num("%.2f" % line_real), sq(str(line_no)),
                sq(p[0]), sq(p[1]), sq(p[2]), sq(p[3]), sq(str(qty)), sq(p[4]),
                sq(str(store[0])), sq(exp_type), sq(state), sq("0"),
                sq(fmt_dt(buy_date)), sq(staff[0]), sq(staff[1]),
            ))
            std_total += line_std
            real_total += line_real
            reccnt += 1

        orders.append((
            sq(mobile), None, sq(store[5]), sq(store[3]), sq(oid), sq(fmt_dt(buy_date)),
            num("%.2f" % std_total), num("%.2f" % real_total), sq(str(reccnt)),
            sq(str(store[0])), sq(exp_type), sq(state), sq("0"),
            sq(fmt_dt(buy_date)), sq(fmt_dt(buy_date)), sq(staff[0]), sq(staff[1]),
            num("0.18"), sq(platform),
        ))
        if state == "10":
            valid_orders_by_phone.setdefault(mobile, []).append((buy_date, real_total))

    # ---------------- 会员快照回写（与订单对账一致）----------------
    year18_ago = now - timedelta(days=548)
    month1_ago = now - timedelta(days=30)
    for m in members:
        recs = valid_orders_by_phone.get(m["_raw_mobile"], [])
        if not recs:
            continue
        m["member_expense_time"] = len(recs)
        m["member_expense_totalsum"] = round(sum(r[1] for r in recs), 2)
        y = [r for r in recs if r[0] >= year18_ago]
        m["member_expense_time_year"] = len(y)
        m["member_expense_totalsum_year"] = round(sum(r[1] for r in y), 2)
        m["member_expense_time_lately"] = len([r for r in recs if r[0] >= month1_ago])
        last = max(r[0] for r in recs)
        m["reserved2"] = sq(fmt_dt(last))

    # ---------------- 输出 ----------------
    os.makedirs(OUT_DIR, exist_ok=True)

    # 02_mock_data.sql
    office_rows = [
        (str(o[0]), str(o[1]), sq(o[2]) if o[2] else None, sq(o[3]), sq(o[4]),
         sq(o[5]) if o[5] else None, sq("0"), sq(fmt_dt(now)))
        for o in OFFICES
    ]
    member_cols = [k for k in members[0].keys() if not k.startswith("_")]
    member_rows = [tuple(m[c] for c in member_cols) for m in members]
    # 删除会员不进视图（模拟触发器同步范围差异）
    view_rows = [r for r, m in zip(member_rows, members) if m["del_flag"] == 0]

    sql2 = []
    sql2.append("-- 自动生成：mock-data/generate_mock_data.py（完全虚构数据）")
    sql2.append("USE `crmtest`;\n")
    sql2.append("-- ---------- 门店/机构 ----------")
    sql2.append(batch_inserts(office_rows, ["id", "parent_id", "parent_ids", "name", "type", "HD_ID",
                                            "del_flag", "create_time"], "sys_office"))
    sql2.append("\n-- ---------- 会员 member_info ----------")
    sql2.append(batch_inserts(member_rows, member_cols, "member_info"))
    sql2.append("\n-- ---------- 会员视图 member_info_view（模拟触发器同步：仅 del_flag=0）----------")
    sql2.append(batch_inserts(view_rows, member_cols, "member_info_view"))
    sql2.append("\n-- ---------- 订单头 expense_calendar_info ----------")
    sql2.append(batch_inserts(orders, ["expense_mobile", "expense_tenant", "expense_stationCode",
                                       "expense_stationName", "expense_orderId", "expense_buyDate",
                                       "expense_stdtotal", "expense_realamt", "expense_reccnt",
                                       "office_id", "expense_type", "expense_state", "del_flag",
                                       "create_time", "modify_time", "userCode", "userName",
                                       "gross_margin", "platform_type"], "expense_calendar_info"))
    sql2.append("\n-- ---------- 订单明细 expense_calendar_details ----------")
    sql2.append(batch_inserts(details, ["expense_d_mobile", "expense_d_stationCode", "expense_d_stationName",
                                        "expense_d_orderId", "expense_d_buyDate", "expense_d_stdtotal",
                                        "expense_d_realamt", "expense_d_itemno", "expense_d_gdcode",
                                        "expense_d_commName", "expense_d_commCode", "expense_d_commCodeName",
                                        "expense_d_qty", "expense_d_scrprice", "office_id",
                                        "expense_d_type", "expense_d_state", "del_flag",
                                        "create_time", "userCode", "userName"], "expense_calendar_details"))
    sql2.append("\n-- 生成完成：会员 %d（视图 %d）/ 订单 %d / 明细 %d" %
                (len(member_rows), len(view_rows), len(orders), len(details)))

    with open(os.path.join(OUT_DIR, "01_schema.sql"), "w", encoding="utf-8", newline="\n") as f:
        f.write(build_schema_sql())
    with open(os.path.join(OUT_DIR, "02_mock_data.sql"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n\n".join(sql2) + "\n")

    print("生成完成 -> %s" % OUT_DIR)
    print("  会员 %d 行（视图 %d 行）/ 订单 %d 行 / 明细 %d 行 / 机构 %d 行"
          % (len(member_rows), len(view_rows), len(orders), len(details), len(office_rows)))
    print("  导入：docker compose up -d 会自动执行；或手动执行这两个 SQL。")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="CRM Mock 数据生成器（完全虚构）")
    ap.add_argument("--members", type=int, default=3000, help="会员数量")
    ap.add_argument("--orders", type=int, default=12000, help="订单数量")
    ap.add_argument("--scale", choices=["small", "default"], default="default",
                    help="small: 500会员/2000订单（快速演示）")
    args = ap.parse_args()
    if args.scale == "small":
        args.members, args.orders = 500, 2000
    generate(args.members, args.orders)

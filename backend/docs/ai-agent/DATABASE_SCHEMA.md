# CRM MySQL 数据库结构与数据风险

## 一、用途和边界

本文件记录 `SpringAi-MCP-RAG-Dev` 所连接的外部 CRM MySQL 数据源的结构说明。

CRM 是外部业务系统。本项目原则上通过测试环境和只读账号查询数据，不修改 CRM 的表结构、触发器或业务数据。

数据库：MySQL 8（根据当前已提供的数据库信息；连接前仍应核实具体版本）。

## 二、使用规则

- 原始 DDL 是字段类型、索引、主键、默认值和约束的主要依据。
- 字段注释是业务理解线索，不一定等于正式业务口径。
- 不得将真实数据库密码、连接串凭证或客户数据放入本文件。
- 业务 SQL 应先在测试环境执行，并与标准答案对账。
- 本文件中的字段摘要不代替完整 DDL。

## 三、核心表说明

### 3.1 `expense_calendar_info`：消费记录表

已知重要字段：
- `expense_mobile`：消费用户手机号
- `expense_tenant`：商户/租户相关字段
- `expense_stationCode`、`expense_stationName`：门店编码和名称
- `expense_orderId`：消费流水号
- `expense_buyDate`：消费时间
- `expense_stdtotal`：消费零售总金额，VARCHAR
- `expense_realamt`：消费实付总金额，VARCHAR
- `expense_reccnt`：商品种类数量，VARCHAR
- `office_id`：机构相关字段
- `expense_type`：注释为 1 线下、2 线上
- `expense_state`：注释为 10 正常、20 已退单
- `del_flag`：删除标记
- `gross_margin`：毛利相关字段，VARCHAR
- `platform_type`：注释为 0 本部、1 饿了么、2 美团

已知存在多种索引，包括手机号、门店编码、订单号、租户、消费时间，以及 `(expense_mobile, expense_buyDate)` 复合索引。索引细节以原始 DDL 为准。

注意：
1. 金额字段是 VARCHAR，使用 SUM 或数值比较前必须检查格式并制定异常值处理方式。
2. 不得未经确认直接认定 `expense_state='10'` 是所有业务统计的正确过滤条件。
3. 不得未经确认假定 `expense_orderId` 全局唯一。
4. 删除标记、租户范围、门店范围和平台范围必须根据业务确认。
5. 不要将订单表的订单级金额与明细表金额直接联查后重复汇总。

### 3.2 `expense_calendar_details`：消费记录详情表

已知重要字段：
- `expense_d_mobile`：消费用户手机号
- `expense_d_stationCode`、`expense_d_stationName`：门店编码和名称
- `expense_d_orderId`：订单或消费流水号
- `expense_d_buyDate`：消费时间
- `expense_d_stdtotal`、`expense_d_realamt`：商品零售金额、实付金额，VARCHAR
- `expense_d_qty`：商品数量，VARCHAR
- `expense_d_itemno`：商品货号
- `expense_d_gdcode`：商品编码
- `expense_d_commName`：商品名称
- `expense_d_commCode`、`expense_d_commCodeName`：商品相关编码和名称
- `expense_d_state`：注释为 10 正常、20 已退单

表中存在普通索引和商品名称、商品编码相关的全文索引，具体定义以原始 DDL 为准。

注意：
1. 必须确认订单表与明细表的关联键以及关联键唯一性范围。
2. 商品排行必须明确使用商品数量、实付金额还是零售金额。
3. 明细退单状态与订单级状态的关系需要验证。
4. 金额、数量字段必须检查空值和非数字内容。

### 3.3 `member_info`：会员信息表

已知重要字段：
- `member_name`：会员姓名
- `member_mobile`：会员手机号
- `member_created`：会员创建时间
- `member_cardno`：会员卡号
- `member_store`、`member_tenant`：会员所属门店/商户相关字段
- `member_expense_time`：消费次数相关统计
- `member_expense_totalsum`：消费总额相关统计
- `member_changer_totalsum`、`member_ay_totalsum`、`member_banalce` 等其他统计或余额字段
- `member_expense_time_lately`：近期消费时间相关字段
- `member_expense_time_year`：周期消费次数相关字段
- `member_expense_totalsum_year`：字段注释描述为近一年半消费金额
- `gross_margin_year`：周期毛利相关字段
- `member_allot_store`：分配门店相关字段
- `del_flag`：删除标记

该表包含较多个人信息字段，例如地址、生日及家庭成员信息。默认仅查询完成业务任务必需的字段。

注意：
1. `del_flag` 的默认值与注释（注释描述 1 是、2 否，但默认值为 0）存在歧义，必须向业务方或现有代码确认。
2. `member_expense_totalsum_year` 不能直接当作自然年度消费金额。
3. 累计统计字段的刷新时机、计算规则和准确性需要验证。
4. 不能仅凭手机号就假定会员与消费记录是一对一关系。

### 3.4 `member_info_view`：会员信息视图（由触发器同步）

该表由触发器 `ff_update_member_info_view` 在 `member_info` 更新时同步，字段结构与 `member_info` 基本一致。查询会员信息时优先使用此视图。

#### 要客（跟踪会员）相关字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `track` | int(1) unsigned zerofill | 跟踪状态：CRM 页面筛选 0=否 / 1=是（要客/被跟踪）；取消要客流程会出现 `2`，NULL 也可能存在 |
| `track_user_id` | varchar(200) | 跟踪人ID（负责该要客的导购/店员） |
| `track_time` | datetime | 追踪时间（最近一次标记为要客的时间） |
| `track_reason` | varchar(255) | 跟踪取消原因 |
| `track_cancel` | varchar(5) | 跟踪类型（字典值） |
| `track_cancel_time` | datetime | 未跟踪时间（被踢出要客的时间） |
| `track_cancel_reason` | varchar(255) | 未跟踪原因 |

#### 会员池相关字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `importan_pond_user_type` | int(1) unsigned zerofill | 0=正常，1=池中（在获客池中） |
| `importan_pond_user_date` | datetime | 入池时间 |
| `importan_pond_user_val` | text | 系统入池说明 |
| `discard_type` | int | 是否废弃会员，1=废弃 |
| `is_region` | char(1) | 1=区域（区域池标记） |

#### 会员归属与分配字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `member_allot_store` | varchar(255) | 分配门店代码，存 `sys_office.HD_ID`（8 位），与 `office_id` 不是同一字段；CRM 按分配门店做机构数据权限隔离，该列为空的会员在设置机构范围时会被完全排除 |
| `member_allot_stationName` | varchar(255) | 分配门店名称 |
| `member_allot_time` | datetime | 最近门店分配时间 |
| `member_allot_staffId` | varchar(255) | 所属门店职员ID |
| `member_allot_staffName` | varchar(255) | 所属门店职员名称 |
| `member_allot_staffTime` | datetime | 最近分配给职员时间 |
| `isAllotStaff` | varchar(255) | 是否分配到店员（1=已分配员工，2=未分配，空=不限；列名即驼峰） |
| `member_is_allot_store` | varchar(255) | 是否分配门店（1是/2否） |

#### 消费统计字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `member_expense_time` | int | 消费次数（累计） |
| `member_expense_totalsum` | decimal(20,2) | 消费总额（累计） |
| `member_expense_time_lately` | int | 最近一个月消费次数 |
| `member_expense_time_year` | int | 近一年半消费次数 |
| `member_expense_totalsum_year` | decimal(20,2) | 近一年半消费金额 |
| `gross_margin_year` | decimal(20,0) | 近一年半消费毛利 |
| `half_year_level` | varchar(10) | 一年半销售级别 |
| `total_year_level` | varchar(10) | 全年总销售级别 |

#### 回访记录字段（reserved 系列）

| 字段 | 类型 | 说明 |
|------|------|------|
| `reserved1` | varchar(255) | 最近消费门店 |
| `reserved2` | varchar(300) | 最近消费时间 |
| `reserved3` | varchar(300) | NULL=线下，1=线上 |
| `reserved4` | varchar(300) | 最近被访问时间 |
| `reserved5` | varchar(300) | 最近被访问门店ID |
| `reserved6` | varchar(100) | 最近被访问门店名称 |
| `reserved7` | varchar(300) | 最近被访问的人 |
| `reserved8` | varchar(300) | 被回访总次数 |
| `reserved9` | varchar(100) | 最近访问人ID |

注意：
1. `member_info_view` 是 `member_info` 行更新后由触发器 `ff_update_member_info_view` 同步的**物理表**（不是 SQL 视图），结构一致；查询时使用该表，它就是 CRM 会员信息列表页的数据源。
2. `track` 字段是判断要客的核心字段，`track=1` 表示该会员当前为要客；`track=2`（取消要客流程）与 NULL 不等于“否”。
3. `track_cancel_time` 记录被踢出要客的时间，结合踢要客规则（370天/35天/50天）可用于分析踢出原因。
4. `importan_pond_user_type` 注意拼写为 CRM 原始命名（importan 而非 important）。
5. **CRM 源码语义校对（2026-11-10）**：会员信息列表页链路为 `MemberInfoController → MemberInfoService.findPage → MemberInfoDao.findList`，`findList` 实际 `FROM member_info_view a`，固定条件仅 `a.del_flag='0'`，**与本项目 `MEMBER_OVERVIEW` 等会员统计的取数来源和过滤完全一致**，人数可直接与 CRM 列表页对齐。`member_info_view` 是 `member_info` 行更新后由触发器 `ff_update_member_info_view` 同步的**物理表**（`TABLE_TYPE=BASE TABLE`，不是 SQL 视图）。背景：`member_info` 中 `del_flag='0'` 有 628,527 行、`member_info_view` 有 428,930 行，差异来自触发器同步范围，属已知背景，不构成口径风险。
6. `plus_type`：CRM 语义 `1`=PLUS；页面筛选“否”实现为 `plus_type IS NULL`（不是 `=0`）。DDL 注释“1.全年会员”与 CRM 源码不符，以 CRM 为准。真库 `member_info` 仅出现 `NULL` 与 `'1'`，无 `'0'`。
7. `channel_type`：**会员注册来源渠道**，只支持 `1` 饿百 / `2` 美团 / `3` 京东到家（DDL 注释里的 0 本部 / 4 抖音不作为字典）。真库另有 1 条 `'4'`，不做映射、按未知/空值处理。
8. `customer_cost`：库内直接存中文标签，固定 8 值（一般保护/重要价值/一般挽留/一般发展/重要保护/一般价值/重要挽留/重要发展用户），不是编码。
9. `company_level`=公司级别（业务称毛利级别，18 个月）、`half_year_level`=一年半销售级别（18 个月）、`total_year_level`=总销售级别（全部时间）。三组取值已按 CRM `SetLevelDao.getGrade()` 原样查询真库核实：`company_level` 21 个（AA~AC、BA~BC、CA~CC、DA~DC、EA~EC、FA~FC、GA~GC）、`half_year_level` 17 个（AA~AC、BA~BC、CA~CC、DA~DC、EA~EC、FA~FB）、`total_year_level` 24 个（AA~AC 至 HA~HC）；`set_level` 只有编码 `level` 与分组 `level_code`，**没有中文名称列**。原文档的 `GA~FC / AA~FC / AA~HC` 已作废（`half_year_level` 实际不存在 `FC`）。真库 `member_info.company_level` 另有 1 条游离值 `'A'`：对应 `set_level` 中 `level_code='companyLevel'`、`level='A'` 的**父级行**（ranking 0.2，CRM `getGrade()` 明确排除该父级组，故不出现在页面下拉），属父级码/数据异常，`member_info_view` 中不存在该记录；原样输出，不映射等级含义。
10. 时间字段：会员列表页 `beginTime/endTime` 绑定 `member_created`（左闭右开，`endTime` 当天不含）；“消费时间”绑定 `reserved2`（最近一次有效消费时间，取 `expense_calendar_info` 中 `expense_state='10'` 的最近 `expense_buyDate`，右开）。
11. 回访：明细在 `access_record_info`（`access_form`：1 电话 / 2 店内 / 3 上门 / 4 即时通讯）；页面 `answerVisit=1` 真实语义是“本月 1 号以来有电话回访记录”，且该子查询未加 `del_flag` 过滤；`answerVisitBegin/End` 在 CRM 页面 SQL 中完全未使用，属无效参数。
12. 金额快照：`member_expense_totalsum_year` 由 `expense_calendar_info.expense_realamt` 按“当前往前 18 个月”求和回写、非实时，其快照子查询未加 `expense_state='10'`（可能含已退单金额），与 `member_expense_totalsum`（带状态过滤）口径不一致；CRM 列表页金额区间用前者。

### 3.5 DDL 原文注释与 CRM 实际语义逐字段核对（2026-11-10 更新）

DDL 注释是数据库里的事实，以下保留原文不改；凡与 CRM 源码/真库不一致的，按“DDL 注释如此，实际语义为 X”记录。

| 字段 | DDL 原文注释 | CRM 实际语义 | 结论 |
|---|---|---|---|
| `plus_type` | plus会员标识（1.全年会员） | `1`=PLUS；其余（含 NULL）均非 PLUS；页面“否”=`plus_type IS NULL`；真库仅 `NULL`/`'1'` | 注释矛盾：不是“全年会员” |
| `channel_type` | 0默认本部，1饿了么，2美团,3京东到家，4抖音 | 会员注册来源渠道，只支持 `1` 饿百 / `2` 美团 / `3` 京东到家；`4` 不映射，按未知/空值处理 | 注释部分矛盾 |
| `track` | 跟踪 0未跟踪 1跟踪（默认 0） | 页面 `0` 否 / `1` 是（要客/被跟踪）；取消要客流程会出现 `2`，`NULL` 也可能存在；真库当前仅 0/1 | 不精确，需补充 `2`/`NULL` |
| `customer_cost` | 顾客价值 | 直接存中文标签，固定 8 值（一般保护/重要价值/一般挽留/一般发展/重要保护/一般价值/重要挽留/重要发展用户） | 不精确 |
| `member_allot_store` | 分配门店代码 | 存 `sys_office.HD_ID`（8 位），与 `office_id` 不是同一字段；CRM 按此列做机构数据权限隔离，空值会员在设置机构范围时被完全排除 | 基本正确，需补充 |
| `office_id` | 机构id | 内部机构主键（bigint），真库大量为 0；不能代替 `member_allot_store` 做门店统计 | 不精确 |
| `isAllotStaff` | 是否分配到店员（1是2否，默认 2） | `1`=已分配员工，`2`=未分配，空=不限 | 基本正确，需补充“空=不限” |
| `member_is_allot_store` | 是否分配门店（1是2否） | `1`=已分配门店，`2`=未分配 | 正确 |
| `member_expense_totalsum` | 消费总额 | 会员累计消费快照，非实时；金额统计应使用订单实付实时汇总 | 不精确（不可当实时值） |
| `member_expense_totalsum_year` | 近一年半消费金额 | 近 18 个月滚动快照，非实时；回写子查询未加 `expense_state='10'`，可能含已退单 | 不精确且有口径缺陷 |
| `reserved2` | 最近消费时间 | 最近一次有效消费时间（`expense_calendar_info` 中 `expense_state='10'` 的最近 `expense_buyDate`）；CRM 消费时间筛选绑定此列，右开 | 基本正确 |
| `expense_d_commName` | 商品名称 | 商品名称（CRM 列表页 `MATCH ... AGAINST` 全文检索，其他报表 `LIKE`） | 正确 |
| `expense_d_commCodeName` | 商品类别名称 | 商品类别名称，与商品名称不是同一列 | 正确 |



## 四、触发器

名称：`ff_update_member_info_view`

已知行为：`member_info` 更新后，将部分字段同步到 `member_info_view` 中对应记录。

开发前需核实：
- 完整触发器定义和同步字段。
- `member_info_view` 的实际对象类型和结构。
- 是否存在其他同步任务。
- 本项目是否应查询基础表、视图或已有数据服务。

本项目不应未经授权修改或删除该触发器。

## 五、原始 DDL（必须补全）

重要说明：本文件当前包含结构摘要和风险说明。请将此前从数据库或项目资料取得的**完整原始 DDL**粘贴到以下各节。不要用字段摘要替代完整的 `CREATE TABLE` / `CREATE TRIGGER` 语句。当前文档生成环境没有完整的原始 SQL 正文，因此不能安全地伪造或补写未核实的字段、索引和约束。

### 5.1 `expense_calendar_info`

```sql


CREATE TABLE `expense_calendar_info` (
`id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
`expense_mobile` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '消费用户手机号',
`expense_tenant` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '消费商户',
`expense_stationCode` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '消费门店代码',
`expense_stationName` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '消费门店名称',
`expense_orderId` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '消费流水号',
`expense_buyDate` datetime DEFAULT NULL COMMENT '消费时间',
`expense_stdtotal` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '消费零售总金额',
`expense_realamt` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '消费实付总金额',
`expense_reccnt` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '消费商品种类数量',
`office_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '机构id',
`expense_type` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '消费类型1.线下2.线上',
`expense_state` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '10' COMMENT '订单状态-10正常，20已退单',
`del_flag` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0' COMMENT '删除标记',
`create_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '创建时间',
`modify_time` datetime DEFAULT NULL COMMENT '修改时间',
`reserved1` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '预留1',
`reserved2` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '预留2',
`reserved3` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '预留3',
`reserved4` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '预留4',
`reserved5` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '预留5',
`reserved6` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '预留6',
`reserved7` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '预留7',
`reserved8` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '预留8',
`reserved9` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '预留9',
`reserved10` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '预留10',
`userCode` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '营业员工号',
`userName` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '营业员姓名',
`gross_margin` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '毛利',
`platform_type` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0' COMMENT '0.本部，1.饿了么，2.美团',
PRIMARY KEY (`id`) USING BTREE,
KEY `index_id` (`id`) USING BTREE,
KEY `index_mobile` (`expense_mobile`) USING BTREE,
KEY `index_stationCode` (`expense_stationCode`) USING BTREE,
KEY `index_orderId` (`expense_orderId`) USING BTREE,
KEY `index_tenant` (`expense_tenant`) USING BTREE,
KEY `index_buyDate` (`expense_buyDate`) USING BTREE,
KEY `index_stdtotal` (`expense_stdtotal`) USING BTREE,
KEY `index_realamt` (`expense_realamt`) USING BTREE,
KEY `index_reccnt` (`expense_reccnt`) USING BTREE,
KEY `index_office_id` (`office_id`) USING BTREE,
KEY `index_state` (`expense_state`) USING BTREE,
KEY `index_reserved1` (`reserved1`) USING BTREE,
KEY `index_reserved2` (`reserved2`) USING BTREE,
KEY `index_userCode` (`userCode`) USING BTREE,
KEY `index_userName` (`userName`) USING BTREE,
KEY `index_buyDate_id_mobile` (`expense_mobile`,`expense_buyDate`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=5801684 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin ROW_FORMAT=DYNAMIC COMMENT='会员消费记录表';


```

### 5.2 `expense_calendar_details`

```sql

CREATE TABLE `expense_calendar_details` (
                                            `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
                                            `expense_d_mobile` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '消费用户手机号',
                                            `expense_d_stationCode` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '消费门店代码',
                                            `expense_d_stationName` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '消费门店名称',
                                            `expense_d_orderId` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '消费流水号',
                                            `expense_d_buyDate` datetime DEFAULT NULL COMMENT '消费时间',
                                            `expense_d_stdtotal` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '每行消费零售总金额',
                                            `expense_d_realamt` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '每行消费实付总金额',
                                            `expense_d_itemno` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '商品行号',
                                            `expense_d_gdcode` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '商品代码',
                                            `expense_d_commName` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '商品名称',
                                            `expense_d_commCode` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '商品类别代码',
                                            `expense_d_commCodeName` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '商品类别名称',
                                            `expense_d_qty` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '商品数量',
                                            `expense_d_scrprice` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '消费时商品零售单价',
                                            `office_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '机构id',
                                            `expense_d_type` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '消费类型1.线下2.线上',
                                            `expense_d_state` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '10' COMMENT '订单状态-10正常，20已退单',
                                            `del_flag` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0' COMMENT '删除标记',
                                            `create_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '创建时间',
                                            `modify_time` datetime DEFAULT NULL COMMENT '修改时间',
                                            `reserved1` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '预留1',
                                            `reserved2` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '预留2',
                                            `reserved3` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '预留3',
                                            `reserved4` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '预留4',
                                            `reserved5` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '预留5',
                                            `reserved6` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '预留6',
                                            `reserved7` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '预留7',
                                            `reserved8` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '预留8',
                                            `reserved9` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '预留9',
                                            `userCode` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '营业员工号',
                                            `userName` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '营业员姓名',
                                            `reserved10` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '预留10',
                                            PRIMARY KEY (`id`) USING BTREE,
                                            KEY `index_id` (`id`) USING BTREE,
                                            KEY `index_mobile` (`expense_d_mobile`) USING BTREE,
                                            KEY `index_stationCode` (`expense_d_stationCode`) USING BTREE,
                                            KEY `index_orderId` (`expense_d_orderId`) USING BTREE,
                                            KEY `index_buyDate` (`expense_d_buyDate`) USING BTREE,
                                            KEY `index_stdtotal` (`expense_d_stdtotal`) USING BTREE,
                                            KEY `index_realamt` (`expense_d_realamt`) USING BTREE,
                                            KEY `index_office_id` (`office_id`) USING BTREE,
                                            KEY `index_state` (`expense_d_state`) USING BTREE,
                                            KEY `index_reserved1` (`reserved1`) USING BTREE,
                                            KEY `index_reserved2` (`reserved2`) USING BTREE,
                                            KEY `index_gdcode` (`expense_d_gdcode`) USING BTREE,
                                            KEY `index_commName` (`expense_d_commName`) USING BTREE,
                                            KEY `index_commCode` (`expense_d_commCode`) USING BTREE,
                                            KEY `index_scrprice` (`expense_d_scrprice`) USING BTREE,
                                            KEY `index_userCode` (`userCode`) USING BTREE,
                                            KEY `index_userName` (`userName`) USING BTREE,
                                            FULLTEXT KEY `ft_commName` (`expense_d_commName`) /*!50100 WITH PARSER `ngram` */ ,
                                            FULLTEXT KEY `idx_gdcode` (`expense_d_gdcode`)
) ENGINE=InnoDB AUTO_INCREMENT=7409695 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin ROW_FORMAT=DYNAMIC COMMENT='会消费记录详情表';
```

### 5.3 `member_info`

```sql

CREATE TABLE `member_info` (
                               `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
                               `member_name` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '姓名',
                               `member_sex` tinyint DEFAULT NULL COMMENT '性别1男/2女',
                               `member_sort` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '类别',
                               `member_address` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '地址',
                               `member_mobile` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '手机',
                               `member_created` datetime DEFAULT NULL COMMENT '会员创建日期',
                               `member_cardno` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '微信卡号',
                               `member_store` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '注册门店代码',
                               `usermobile` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '邀请人手机号',
                               `member_stationName` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '注册门店名称',
                               `member_allot_store` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '分配门店代码',
                               `member_allot_stationName` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '分配门店名称',
                               `member_allot_time` datetime DEFAULT NULL COMMENT '最近门店分配时间',
                               `member_allot_staffId` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '所属门店职员id',
                               `member_allot_staffName` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '所属门店职员名称',
                               `member_allot_staffTime` datetime DEFAULT NULL COMMENT '最近分配给职员时间',
                               `member_allot_storeUserId` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '门店操作分配用户id',
                               `member_allot_storeUserName` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '门店操作分配用户名称',
                               `isAllotStaff` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '2' COMMENT '是否分配到店员（1是2否）',
                               `member_is_allot_store` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '是否分配门店1是2否',
                               `member_tenant` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '商户',
                               `member_expense_time` int DEFAULT NULL COMMENT '消费次数',
                               `member_expense_totalsum` decimal(20,2) DEFAULT NULL COMMENT '消费总额',
                               `member_now_integral` int DEFAULT NULL COMMENT '当前积分',
                               `member_total_integral` int DEFAULT NULL COMMENT '累计积分',
                               `member_changer_totalsum` decimal(20,2) DEFAULT NULL COMMENT '充值总额',
                               `member_ay_totalsum` decimal(20,2) DEFAULT NULL COMMENT '支付总额',
                               `member_banalce` decimal(20,2) DEFAULT NULL COMMENT '余额',
                               `member_start_time` datetime DEFAULT NULL COMMENT '起始有效日期',
                               `member_end_time` datetime DEFAULT NULL COMMENT '截止有效日期',
                               `member_area` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '所属区域',
                               `member_work_unit` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '工作单位',
                               `member_hobby` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '业余爱好',
                               `member_income` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '收入',
                               `member_love_brand` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '喜爱品牌',
                               `member_yin_birthday` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '阴历出生日期',
                               `member_yang_birthday` datetime DEFAULT NULL COMMENT '阳历出生日期',
                               `member_duty` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '职务',
                               `office_id` bigint DEFAULT '0' COMMENT '机构id',
                               `user_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '管理者id',
                               `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                               `modify_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
                               `del_flag` tinyint DEFAULT '0' COMMENT '是否删除1是2否',
                               `member_importance` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '客户重要程度(维护级别)',
                               `member_stickiness` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '粘合度',
                               `consumption_custom` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '消费习惯',
                               `member_nature` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '客户身份',
                               `customer_level` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '客户级别',
                               `is_wechat_friend` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '微信号',
                               `member_quality` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '客户性质',
                               `expense_calendar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '消费习惯',
                               `up` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0' COMMENT '置顶',
                               `HD_totalsum` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '参加活动',
                               `remarks` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '备注',
                               `reserved1` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '最近消费门店',
                               `reserved2` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最近消费时间',
                               `reserved3` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'null,线下，1线上',
                               `reserved4` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最近被访问时间',
                               `reserved5` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最近被访问门店id',
                               `reserved6` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最近被访问门店名称',
                               `reserved7` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最近被访问的人',
                               `reserved8` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '被回访总次数',
                               `reserved9` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最近访问人id',
                               `reserved10` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT ' 最后修改(机构)门店id',
                               `reserved11` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最后修改机构名称',
                               `reserved12` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最后修改人名称',
                               `reserved13` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最近消费门店id',
                               `reserved14` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '预留14',
                               `reserved15` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '(该字段废弃，直接在CRM接口中查询)',
                               `beforeJson` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '之前修改json',
                               `hd_member_id` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'CRM会员id',
                               `age` bigint DEFAULT NULL COMMENT '年龄',
                               `integral` decimal(16,0) DEFAULT NULL COMMENT '积分',
                               `stored_amt` decimal(16,2) DEFAULT NULL COMMENT '储值金额',
                               `contact_address_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '联系地址类型',
                               `contact_address` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '联系地址',
                               `schooling` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '受教育程度',
                               `customer_source` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '顾客来源',
                               `company_purchase` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '单位采购/自用',
                               `alcohol_use` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '用酒用途',
                               `industry_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '行业类型',
                               `company_scale` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '公司规模',
                               `consume_hierarchy` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '消费总额层级',
                               `consume_r` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '消费R值',
                               `consume_store` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '首次消费门店',
                               `consume_time` datetime DEFAULT NULL COMMENT '首次消费时间',
                               `consume_commodity` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '主要消费商品',
                               `company_level` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '公司级别',
                               `city_level` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '城市级别',
                               `store_level` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '门店级别',
                               `personal_level` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '个人级别',
                               `customer_cost` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '顾客价值',
                               `member_expense_time_lately` int DEFAULT NULL COMMENT '最近一个月消费次数',
                               `member_expense_time_year` int DEFAULT NULL COMMENT '近一年半消费次数',
                               `member_expense_totalsum_year` decimal(20,2) DEFAULT NULL COMMENT '近一年半消费金额',
                               `account_manager_phone` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '大客户经理手机号',
                               `account_manager_phone_time` datetime DEFAULT NULL COMMENT '大客户经理创建时间',
                               `half_year_level` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '一年半销售级别',
                               `total_year_level` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '全年总销售级别',
                               `gross_margin_year` decimal(20,0) DEFAULT NULL COMMENT '近一年半消费毛利',
                               `billing_people_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '开单人ID',
                               `billing_date` datetime DEFAULT NULL COMMENT '开单时间',
                               `plus_type` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'plus会员标识（1.全年会员）',
                               `track` int(1) unsigned zerofill DEFAULT '0' COMMENT '跟踪 0未跟踪  1跟踪',
                               `track_user_id` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '跟踪人ID',
                               `track_reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '跟踪取消原因',
                               `track_time` datetime DEFAULT NULL COMMENT '追踪时间',
                               `track_cancel_reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '未跟踪原因',
                               `track_cancel` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '跟踪类型（字典）',
                               `track_cancel_time` datetime DEFAULT NULL COMMENT '未跟踪时间',
                               `contact_address1` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '联系地址1',
                               `contact_address2` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '联系地址2',
                               `invoice_url` text CHARACTER SET ucs2 COLLATE ucs2_general_ci COMMENT '发票路径集合',
                               `wechat_id` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '微信ID',
                               `channel_type` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '0默认本部，1饿了么，2美团,3京东到家，4抖音',
                               `importan_pond_user_type` int(1) unsigned zerofill DEFAULT '0' COMMENT '0正常1池中',
                               `importan_pond_user_date` datetime DEFAULT NULL COMMENT '入池时间',
                               `importan_pond_user_val` text CHARACTER SET utf8mb4 COLLATE utf8mb4_bin COMMENT '系统入池说明',
                               `discard_type` int DEFAULT NULL COMMENT '是否废弃会员1废弃',
                               `is_region` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '1区域',
                               `spouse_birthday` date DEFAULT NULL COMMENT '配偶生日',
                               `spouse_company` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '配偶单位',
                               `children_birthday` date DEFAULT NULL COMMENT '孩子生日',
                               `children_school` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '孩子学校',
                               `plus_expire_time` datetime DEFAULT NULL COMMENT 'puls到期时间',
                               `is_franchise` int DEFAULT NULL COMMENT '1加盟',
                               `car_brand_key` int DEFAULT NULL COMMENT '车辆品牌KEY',
                               `car_brand_value` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '车辆品牌名称',
                               `car_level_key` int DEFAULT NULL COMMENT '车辆等级KEY',
                               `car_level_value` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '车辆等级名称',
                               `is_toker` int DEFAULT NULL COMMENT '是否拓客,1拓客',
                               `member_is_car` int DEFAULT NULL COMMENT '0先会员，1后车辆',
                               `plus_vip_type` int DEFAULT NULL COMMENT 'plus会员类型0:198,5:99,7:298',
                               `expired_plus` int DEFAULT NULL COMMENT '1过期PLUS',
                               `year_procurement` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '年度采购量',
                               `new_member_level_value` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '新会员等级名称',
                               `new_member_level_key` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '新会员等级标记',
                               PRIMARY KEY (`id`) USING BTREE,
                               KEY `index_mobile` (`member_mobile`) USING BTREE,
                               KEY `index_name` (`member_name`) USING BTREE,
                               KEY `index_sex` (`member_sex`) USING BTREE,
                               KEY `index_store` (`member_store`) USING BTREE,
                               KEY `index_created` (`member_created`) USING BTREE,
                               KEY `index_cardno` (`member_cardno`) USING BTREE,
                               KEY `index_stationName` (`member_stationName`) USING BTREE,
                               KEY `index_allot_stationName` (`member_allot_stationName`) USING BTREE,
                               KEY `index_allot_time` (`member_allot_time`) USING BTREE,
                               KEY `index_allot_staffId` (`member_allot_staffId`) USING BTREE,
                               KEY `index_allot_staffName` (`member_allot_staffName`) USING BTREE,
                               KEY `index_allot_staffTime` (`member_allot_staffTime`) USING BTREE,
                               KEY `index_allot_storeUserId` (`member_allot_storeUserId`) USING BTREE,
                               KEY `index_allot_storeUserName` (`member_allot_storeUserName`) USING BTREE,
                               KEY `index_office_id` (`office_id`) USING BTREE,
                               KEY `index_user_id` (`user_id`) USING BTREE,
                               KEY `index_is_allot_store` (`member_is_allot_store`) USING BTREE,
                               KEY `index_usermobile` (`usermobile`) USING BTREE,
                               KEY `index_isAllotStaff` (`isAllotStaff`) USING BTREE,
                               KEY `index_reserved13` (`reserved13`) USING BTREE,
                               KEY `index_member_expense_totalsum` (`member_expense_totalsum`) USING BTREE,
                               KEY `index_member_expense_time` (`member_expense_time`) USING BTREE,
                               KEY `index_member_now_integral` (`member_now_integral`) USING BTREE,
                               KEY `index_allot_store` (`del_flag`,`member_allot_store`) USING BTREE,
                               KEY `index_member_expense_totalsum_year` (`member_expense_totalsum_year`) USING BTREE,
                               KEY `index_billing_date` (`billing_date`) USING BTREE,
                               KEY `index_billing_people_id` (`billing_people_id`) USING BTREE,
                               KEY `index_company_level` (`company_level`) USING BTREE,
                               KEY `index_track` (`track`) USING BTREE,
                               KEY `index_t_m` (`track`,`member_allot_store`) USING BTREE,
                               KEY `intdex_track_time` (`track`,`track_time`) USING BTREE,
                               KEY `idx_wechat_id` (`wechat_id`) USING BTREE,
                               KEY `member_allot_store` (`member_allot_store`) USING BTREE,
                               KEY `index_track_id` (`track_user_id`) USING BTREE,
                               KEY `idx_membermobile_memberallotstore` (`member_mobile`,`member_allot_store`) USING BTREE,
                               KEY `index_reserved2` (`reserved2`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=3262182 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin ROW_FORMAT=DYNAMIC COMMENT='会员信息表';
```

### 5.4 触发器 `ff_update_member_info_view`

```sql

CREATE DEFINER=`crm_dev`@`%` TRIGGER `ff_update_member_info_view` AFTER UPDATE ON `member_info` FOR EACH ROW BEGIN
UPDATE member_info_view SET
                            member_name = NEW.member_name,
                            member_sex = NEW.member_sex,
                            member_sort = NEW.member_sort,
                            member_address = NEW.member_address,
                            member_mobile = NEW.member_mobile,
                            member_created = NEW.member_created,
                            member_cardno = NEW.member_cardno,
                            member_store = NEW.member_store,
                            usermobile = NEW.usermobile,
                            member_stationName = NEW.member_stationName,
                            member_allot_store = NEW.member_allot_store,
                            member_allot_stationName = NEW.member_allot_stationName,
                            member_allot_time = NEW.member_allot_time,
                            member_allot_staffId = NEW.member_allot_staffId,
                            member_allot_staffName = NEW.member_allot_staffName,
                            member_allot_staffTime = NEW.member_allot_staffTime,
                            member_allot_storeUserId = NEW.member_allot_storeUserId,
                            member_allot_storeUserName = NEW.member_allot_storeUserName,
                            isAllotStaff = NEW.isAllotStaff,
                            member_is_allot_store = NEW.member_is_allot_store,
                            member_tenant = NEW.member_tenant,
                            member_expense_time = NEW.member_expense_time,
                            member_expense_totalsum = NEW.member_expense_totalsum,
                            member_now_integral = NEW.member_now_integral,
                            member_total_integral = NEW.member_total_integral,
                            member_changer_totalsum = NEW.member_changer_totalsum,
                            member_ay_totalsum = NEW.member_ay_totalsum,
                            member_banalce = NEW.member_banalce,
                            member_start_time = NEW.member_start_time,
                            member_end_time = NEW.member_end_time,
                            member_area = NEW.member_area,
                            member_work_unit = NEW.member_work_unit,
                            member_hobby = NEW.member_hobby,
                            member_income = NEW.member_income,
                            member_love_brand = NEW.member_love_brand,
                            member_yin_birthday = NEW.member_yin_birthday,
                            member_yang_birthday = NEW.member_yang_birthday,
                            member_duty = NEW.member_duty,
                            office_id = NEW.office_id,
                            user_id = NEW.user_id,
                            create_time = NEW.create_time,
                            modify_time = NEW.modify_time,
                            del_flag = NEW.del_flag,
                            member_importance = NEW.member_importance,
                            member_stickiness = NEW.member_stickiness,
                            consumption_custom = NEW.consumption_custom,
                            member_nature = NEW.member_nature,
                            customer_level = NEW.customer_level,
                            is_wechat_friend = NEW.is_wechat_friend,
                            member_quality = NEW.member_quality,
                            expense_calendar = NEW.expense_calendar,
                            up = NEW.up,
                            HD_totalsum = NEW.HD_totalsum,
                            remarks = NEW.remarks,
                            reserved1 = NEW.reserved1,
                            reserved2 = NEW.reserved2,
                            reserved3 = NEW.reserved3,
                            reserved4 = NEW.reserved4,
                            reserved5 = NEW.reserved5,
                            reserved6 = NEW.reserved6,
                            reserved7 = NEW.reserved7,
                            reserved8 = NEW.reserved8,
                            reserved9 = NEW.reserved9,
                            reserved10 = NEW.reserved10,
                            reserved11 = NEW.reserved11,
                            reserved12 = NEW.reserved12,
                            reserved13 = NEW.reserved13,
                            reserved14 = NEW.reserved14,
                            reserved15 = NEW.reserved15,
                            beforeJson = NEW.beforeJson,
                            hd_member_id = NEW.hd_member_id,
                            age = NEW.age,
                            integral = NEW.integral,
                            stored_amt = NEW.stored_amt,
                            contact_address_type = NEW.contact_address_type,
                            contact_address = NEW.contact_address,
                            schooling = NEW.schooling,
                            customer_source = NEW.customer_source,
                            company_purchase = NEW.company_purchase,
                            alcohol_use = NEW.alcohol_use,
                            industry_type = NEW.industry_type,
                            company_scale = NEW.company_scale,
                            consume_hierarchy = NEW.consume_hierarchy,
                            consume_r = NEW.consume_r,
                            consume_store = NEW.consume_store,
                            consume_time = NEW.consume_time,
                            consume_commodity = NEW.consume_commodity,
                            company_level = NEW.company_level,
                            city_level = NEW.city_level,
                            store_level = NEW.store_level,
                            personal_level = NEW.personal_level,
                            customer_cost = NEW.customer_cost,
                            member_expense_time_lately = NEW.member_expense_time_lately,
                            member_expense_time_year = NEW.member_expense_time_year,
                            member_expense_totalsum_year = NEW.member_expense_totalsum_year,
                            account_manager_phone = NEW.account_manager_phone,
                            account_manager_phone_time = NEW.account_manager_phone_time,
                            half_year_level = NEW.half_year_level,
                            total_year_level = NEW.total_year_level,
                            gross_margin_year = NEW.gross_margin_year,
                            billing_people_id = NEW.billing_people_id,
                            billing_date = NEW.billing_date,
                            plus_type = NEW.plus_type,
                            track = NEW.track,
                            track_user_id = NEW.track_user_id,
                            track_reason = NEW.track_reason,
                            track_time = NEW.track_time,
                            track_cancel_reason = NEW.track_cancel_reason,
                            track_cancel = NEW.track_cancel,
                            track_cancel_time = NEW.track_cancel_time,
                            contact_address1 = NEW.contact_address1,
                            contact_address2 = NEW.contact_address2,
                            invoice_url = NEW.invoice_url,
                            wechat_id = NEW.wechat_id,
                            channel_type = NEW.channel_type,
                            importan_pond_user_type = NEW.importan_pond_user_type,
                            importan_pond_user_date = NEW.importan_pond_user_date,
                            importan_pond_user_val = NEW.importan_pond_user_val,
                            is_region = NEW.is_region,
                            spouse_birthday = NEW.spouse_birthday,
                            spouse_company = NEW.spouse_company,
                            plus_expire_time = NEW.plus_expire_time,
                            children_birthday = NEW.children_birthday,
                            children_school = NEW.children_school,
                            car_brand_key = NEW.car_brand_key,
                            car_brand_value = NEW.car_brand_value,
                            car_level_key = NEW.car_level_key,
                            car_level_value = NEW.car_level_value,
                            is_toker = NEW.is_toker,
                            member_is_car = NEW.member_is_car,
                            plus_vip_type = NEW.plus_vip_type,
                            expired_plus = NEW.expired_plus,
                            new_member_level_value = NEW.new_member_level_value,
                            new_member_level_key = NEW.new_member_level_key
WHERE id = NEW.id;
END;
```

## 六、建议的数据验证清单

所有验证优先在测试库执行，并避免输出个人信息。

- [ ] 确认测试库版本和 schema。
- [ ] 确认数据库只读账号及其授权范围。
- [ ] 确认订单号唯一性范围。
- [ ] 检查金额字段的空值、空字符串、非数字内容和异常负数。
- [ ] 检查数量字段的异常格式。
- [ ] 确认订单与明细表关联键。
- [ ] 确认正常、退单、退款和删除记录的业务规则。
- [ ] 确认会员表删除标记的真实含义。
- [ ] 对账会员累计统计与订单数据汇总。
- [ ] 确认租户、门店和平台数据权限。
- [ ] 使用脱敏数据编写测试用例。

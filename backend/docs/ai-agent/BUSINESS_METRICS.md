# CRM AI 数据分析业务指标定义

## 一、目的

本文件统一 `SpringAi-MCP-RAG-Dev` 对 CRM 数据进行分析时使用的指标口径。所有指标必须有明确的数据来源、计算方法、统计范围和验证方式。

本文档中的内容目前是待确认清单，不代表业务方已经批准。未确认的规则不得直接用于正式回答。部分指标的确认结论已记入第五节（2026-10-29 Phase 2 口径答复），以下检查项中与第五节重复的以第五节状态为准。

## 二、每个指标必须记录的内容

- 指标名称和业务解释
- 计算公式
- 数据来源表和字段
- 使用的时间字段
- 统计时间边界
- 订单状态和删除记录过滤规则
- 退款、退单及部分退款处理方式
- 门店、平台等数据范围（2026-10-30 业务决策：**租户/商户不再作为统计与筛选维度**）
- 空值、异常值和重复数据处理方式
- 验证方法与标准答案
- 业务确认依据和确认日期

## 三、待确认指标

### 3.1 消费金额 / 营业额

必须确认：
- 使用 `expense_realamt`（实付金额）还是 `expense_stdtotal`（零售金额），或采用其他口径。
- 是否扣除退款、退单或部分退款。
- 是否过滤删除记录。
- 以消费时间、下单时间还是支付时间作为统计时间。
- 是否区分线上、线下、平台和门店（租户维度已于 2026-10-30 取消）。
- VARCHAR 金额字段异常时如何处理。
- 订单级金额和明细级金额的口径如何对账。

在业务确认前，不要把某一个字段直接定义为统一营业额。

### 3.2 有效订单数

必须确认：
- 一条订单记录是否对应一笔业务订单。
- 订单号唯一性范围。
- 有效状态集合。
- 退单、退款和部分退款如何计算。
- 是否排除删除记录。
- 线上订单与线下流水是否采用相同规则。

### 3.3 会员消费次数

必须确认：
- 消费次数是订单数、交易笔数还是按会员定义的消费频次。
- 同一会员同一天多笔消费如何统计。
- 退单是否计入。
- 是使用 `member_expense_time` 还是根据订单数据重新计算。
- 累计字段与明细汇总不一致时如何处理。

### 3.4 会员新增数

必须确认：
- 是否以 `member_created` 作为新增时间。
- 按自然日、周、月还是其他周期统计。
- 删除会员是否排除。
- 同一手机号对应多条会员记录如何处理。
- 是否按门店或会员来源拆分（租户维度已取消）。

### 3.5 商品销售排行

必须确认：
- 排名依据是商品数量、实付金额还是零售金额。
- 退单和退款如何处理。
- 按商品编码、货号还是名称聚合。
- 同名不同编码的商品是否合并。
- 是否按门店、平台和时间范围筛选（租户维度已取消）。

### 3.6 毛利

必须确认：
- `gross_margin` 与 `gross_margin_year` 的计算规则。
- 毛利是订单级还是商品明细级。
- 退货如何影响毛利。
- 时间周期和统计范围。
- 会员周期毛利与订单数据计算结果是否一致。

## 四、统一查询原则

1. 所有统计都要明确时间范围及时间字段。
2. 所有查询都必须执行服务端权限校验。
3. 订单级统计与商品明细级统计分开计算，避免重复累计。
4. 金额和数量字段必须经过数据质量检查。
5. 同一指标应使用统一的业务服务或受控查询实现。
6. 模型不能自行改变指标口径。
7. 默认时间范围由业务方确认，不允许凭习惯设定。
8. 查询结果应保留足够的计算依据，便于测试和审计。
9. 对口径未确认、数据异常或查询失败的情况，应明确说明。

## 五、业务确认记录

| 指标 | 状态 | 确认依据 | 备注 |
|---|---|---|---|
| 消费金额 / 营业额（实付口径） | 已确认 + Phase1 已实现 | 产品确认取 `expense_realamt`；退单为负数原行抵消 | 门店销售额/商品销售额已真库验证 |
| 有效订单数 | 已确认 + Phase1 已实现 | `expense_state='10' AND del_flag='0'`，1 单 1 行 | 门店订单数已真库验证 |
| 会员总数 / 性别固定列 | 已确认 + Phase1 已实现 | `member_info_view`，`del_flag='0'`；性别三分区求和=总数 | 已真库验证且口径自洽；注：此处性别是**三个固定度量**，不是可分组/排序的维度（见下一行）；累计消费已从会员总览移出，改由 `MEMBER_CONSUMPTION`（订单实付实时汇总）提供 |
| 商品销售排行 | 已确认 + Phase1 已实现（**2026-10-30 两项遗留问题已答复**） | SKU=`expense_d_gdcode`；按销售额/销量 TopN | ① **异常 SKU 按真实商品统计**：不过滤、不合并、允许进榜（`198.0`「198plus」在 2026-06 窗口排第 5、238 万 / 12,021 行，属正常结果）；② 默认 18 个月窗口 75.8s 超时已用**指标级跨度上限**收敛：`PRODUCT_RANKING` 声明 `maxRangeDays: 92` + `defaultWindowMonths: 3`，越界拒绝且不触库 |
| 新增会员 | 已确认 + Phase2 已实现（2026-10-29） | 时间=`member_created`；归属＝**注册门店** `member_store` | `MEMBER_NEW` 已接入并真库逐值对账；默认区间近一年半 |
| 活跃会员 | 已确认 + Phase2 已实现（2026-10-29） | **近 3 个月（90 天）内有 ≥1 次有效消费** | 不取 CRM 业务状态字段；`ACTIVE_MEMBER` 按 `expense_mobile` 去重、指标级窗口 3 个月，已真库对账 |
| 流失会员 / 沉睡名单 / 最后消费时间 | ⛔ 不做（2026-10-29） | 业务层面定义暂不考虑 | 名单类个人级输出同步移出范围 |
| 复购率 / 复购周期 / 新客首购 | ⛔ 先不做（2026-10-29） | 产品确认移出 Phase 2 | 后续重提需重新定分子分母 |
| 地域分布 | ⛔ 不做（2026-10-29） | 需 TMS 数据支撑，现有三表不够 | — |
| 会员层级分布 | 已确认 + Phase2 已实现（2026-10-29） | `customer_level`/`new_member_level_key`/`member_importance`/`consume_hierarchy`/`half_year_level`/`total_year_level` **全部统计**，各按自身维度 | Step 0 实测：`customer_level`/`consume_hierarchy` 测试库全空；`MEMBER_LEVEL_DIST` 六维度已接入并对账 |
| 会员性别分布 | ⏸ 能力缺口（2026-10-30）→ 🧩 框架已具备 join 能力（路线 C 同日升级） | 现有 `MEMBER_OVERVIEW` 只有男/女/未知**三个固定度量**，不能按性别分组/排序/交叉 | 路线 C 已让 `SqlCompiler` 支持受控 LEFT JOIN，因此“把性别当作可分组维度”技术上已可行；但“会员数按性别分组”仍需在 `member.yaml` 新增一条带性别维度的指标（会员表单表即可，不依赖 join），本轮只交付了下述消费侧对比指标。真实库枚举：1=592,032、2=29,969、NULL=6,524、**0=2**（DDL 仅定义 1男/2女，0 含义待确认） |
| 男女消费对比（男的买的多还是女的买的多） | 🟡 已实现、分组枚举已定、三度量口径待业务复核（2026-10-30 路线 C） | 订单头表受控 LEFT JOIN 会员表（ON `expense_mobile=member_mobile`），按 `member_sex` 分男/女/未知性别，`SUM(实付)/COUNT(*)/COUNT(DISTINCT mobile)` | `GENDER_CONSUMPTION_COMPARE` 为**首个 join 指标**。关联键 `member_mobile` 实测全局唯一（重复组=0）、LEFT JOIN 零行放大；真库固定窗口（06-01~06-27）三组求和与门店合计逐值相符。**分组枚举 2026-10-30 已定**：保留第三桶且不拆，未关联会员 + 匹配会员但 sex 非 1/2 合并第三桶（依 §1.5 #9 所有消费均计会员消费）；**第三桶展示名 2026-11-03 由‘会员性别缺失’改为‘未知性别’**（仅改标签，分组语义不变）；**数据质量前提**：该桶销售额占比 46%（52.4M/113.9M），回答“男的买的多”必须先声明；三度量口径（实付/订单数/去重买家）仍由开发侧代拟，业务复核前不作正式统计 |
| 年龄分布 | 已确认 + **已于 2026-10-30 落地** | 由**阴历生日 `member_yin_birthday`** 计算周岁，不用 `age` 列；分桶 `≤19/20-29/30-39/40-49/50-59/≥60/未知` | 阳历列 `member_yang_birthday` 在 628,527 行上 **100% 为空**（已固定为断言防回退）。阴历列为 varchar(200)，非空 2,766 行、STR_TO_DATE 零解析失败，但**覆盖率仅 0.44%**→“未知”占 99.56%，属数据覆盖限制而非口径错误。业务已接受“阴历日期与阳历 CURDATE 直比的 ±1 月周岁偏差”。实现：`AGE_DIST` 的 `expr` 已改阴历 + `STR_TO_DATE`，`member_yin_birthday` 已加入 `piiComputeAllowedColumns`（只允许作计算输入，禁止输出原值）；`age` 列仅 32 人有值且 30 人与生日计算偏差 >1 岁，进一证实不可用 |
| 单客年均消费 | 已确认 + Phase2 已实现（2026-10-29） | 口径＝**近一年半**总消费 / 同期有消费会员数 | `ANNUAL_AVG_CONSUMPTION` 按 `expense_buyDate` 重算（不直取 `_year` 快照），已真库对账；CRM 列表页金额区间用 `member_expense_totalsum_year` 快照（非实时、子查询未加 `expense_state='10'`），本指标与 CRM 列表页数字可能不同 |
| 历史消费金额（会员累计类） | 已确认（2026-10-29） | **去掉负数**：退单负行不参与会员累计统计 | 与全局“退单抵消”口径并存，仅限会员累计消费类 |
| 看板默认时间范围 | 已确认 + 已生效（2026-10-29） | **近一年半** | 已取代 Phase 1 “当前自然月”旧口径（含旧工具）；跨度上限 366→550 天 |
| 会员销售额占比（会员 vs 散客） | ⛔ 不实现（2026-10-29 对齐） | 小酒喔所有消费均计为会员消费，占比恒≈100% | 活跃/年均消费类指标直接按 `expense_mobile` 统计，不验证会员身份 |
| 会员消费次数 | ⏸ 未答复（2026-10-29） | 待补充 | 随复购族缓做 |
| 毛利 | 待确认 | 待补充 | 仅总额/订单级，无明细；脏值需清洗；10-29 未答复 |

## 六、会员定义体系（2026-11-02 同步自 CRM 会员池脑图）

### 6.1 会员类型

| 会员类型 | 定义 | 判定条件 | 数据来源表/字段 |
|----------|------|----------|-----------------|
| **普通会员** | 没有消费或消费不够29.9元，系统记录但不显示在手机中 | 历史消费 < 29.9元 或 无消费记录 | `member_info_view.member_expense_totalsum` |
| **有效会员/非要客** | 1年半内消费29.9元以上 | 近一年半消费 ≥ 29.9元 | `member_info_view.member_expense_totalsum_year >= 29.9` |
| **要客** | 由店员或店长在个人池中主动点击跟踪变成要客，每人有数量上限（运营部后台配置） | `track = 1` | `member_info_view.track = 1` |

> 快照口径说明（2026-11-09 按 CRM 源码对齐）：会员池判定沿用上述两个快照字段（业务规则，不改实现）。`member_expense_totalsum_year` 由订单 `expense_realamt` 按“当前往前 18 个月”求和后回写、非实时，且其快照子查询**未加** `expense_state='10'`，可能把已退单金额计入；`member_expense_totalsum` 那层带状态过滤，两者口径本身不一致。统计助手的消费金额统一用订单实付实时汇总（`MEMBER_CONSUMPTION`），**不得**把两者说成等价，也不得用快照字段回答累计消费金额。

### 6.2 要客踢出规则（满足任意一条即踢出）

| 优先级 | 规则 | 判定逻辑 |
|--------|------|----------|
| P1 | 同一导购名下 **370天内未出单** 的要客 | `track=1` 且该导购名下最近370天无有效消费订单 |
| P2 | **35天内**没有30秒以上电话沟通或无微信有效沟通 | `track=1` 且最近35天内无30秒以上通话且无微信有效沟通 |
| P3 | **50天内**没有30秒以上电话沟通或无微信**3次以上**有效沟通 | `track=1` 且最近50天内无30秒以上通话且微信有效沟通 < 3次 |

注意：以上规则中 P2/P3 涉及的"电话沟通"和"微信有效沟通"记录可能不在 `member_info_view` 表中，需确认 CRM 系统是否有独立的沟通记录表。

### 6.3 要客关键字段映射

| 业务概念 | 数据库字段 | 说明 |
|----------|-----------|------|
| 是否要客 | `track` | 0=未跟踪（非要客），1=跟踪（要客） |
| 跟踪人（负责导购） | `track_user_id` | 要客所属导购/店员的ID |
| 标记要客时间 | `track_time` | 最近一次被标记为要客的时间 |
| 踢出时间 | `track_cancel_time` | 被踢出要客的时间 |
| 踢出原因 | `track_cancel_reason` | 未跟踪原因 |
| 踢出类型 | `track_cancel` | 跟踪类型（字典值） |
| 要客数量上限 | 运营部后台配置 | 每个导购/店员的要客数量上限，非数据库字段 |

### 6.4 会员数据池及流转规则

```
┌─────────────────────────────────────────────────────────────────┐
│                        会员数据池流转                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  门店池 ←── 有效会员（1年半内消费 ≥ 29.9元）                      │
│    │                                                            │
│    └──→ 个人池 ←── 店长从门店池分配至个人                          │
│              │                                                  │
│              ├──→ 要客 ←── 店员/店长主动点击跟踪                   │
│              │         （track=1，有数量上限）                     │
│              │                                                  │
│              └──→ 废弃池 ←── 店员/店长主动废弃 + 填入原因           │
│                                                                 │
│  区域池 ←── 其他门店规定时间内未回访的会员                          │
│            条件：非要客 + 20天内无20秒以上呼出 + 历史消费 > 500      │
│                  + 注册 > 7天 + 7天以上未被跟踪                    │
│                                                                 │
│  获客池 ←── 非有效会员/新用户，按渠道先后顺序进入                   │
│            代驾池 / 外卖池 / 地推池 / 车辆采集池                   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 6.5 获客池定义

| 获客池 | 优先级 | 数据来源 | 营销动作 |
|--------|--------|----------|----------|
| 代驾池 | P1 | 所有叫过代驾的非有效会员 | 发券、发短信、店员电话回访 |
| 外卖池 | P2 | 美团/饿百/京东/抖音小时达/抖音团购（海鼎系统订单中核销方式=抖音团购） | 发券、发短信、店员电话回访 |
| 地推池 | P3 | 待定 | — |
| 车辆采集池 | P4 | 店员通过抄车牌获取的手机号与车辆信息，审核通过后进入 | 发券、发短信、店员电话回访 |

获客池要求：
- 各渠道会员展示相互不能重复，只能在一个获客池中显示
- 一个月内不能重复回访

### 6.6 会员周期营销方案

**未消费/消费不满29.9元：**

| 阶段 | 营销动作 |
|------|----------|
| 新人（进各渠道获客池） | 根据各获客池规则发放不同优惠券并短信通知 |
| 第二轮未消费池 | 各渠道获客池优惠券过期未消费 → 发放新一轮优惠券并短信提醒 → 店员手动电话回访 |
| 第三轮未消费池 | 连续半年每个月发放一次优惠券并短信提醒，无需店员电话回访 |
| 无效会员 | 终止营销 |

**消费满29.9元以上：**

| 阶段 | 营销动作 |
|------|----------|
| 消费后 | 变成有效会员 |
| 3个月未消费 | 发放优惠券并短信提醒 + 店员手动电话回访 |
| 18个月未消费 | 连续半年每个月发放一次优惠券并短信提醒，无需店员电话回访 |
| 无效会员 | 终止营销 |

**通用规则**：任何时期消费满29.9以上都变成有效会员。

### 6.7 已实现的要客统计指标（2026-11-02 Phase 3）

以下指标已配置在 `resources/metrics/member.yaml`，状态为 `CONFIRMED`（已通过真库验证）。

| 指标编码 | 指标名称 | 类型 | 说明 |
|----------|----------|------|------|
| `VIP_OVERVIEW` | 要客总览 | 快照 | 当前要客总数（消费金额改由 `VIP_CONSUMPTION_BY_STORE` 提供，来源订单实付 `expense_realamt`） |
| `VIP_NEW` | 新增要客数 | 时间窗口 | 按 `track_time` 统计指定时间范围内的新增要客 |
| `VIP_CANCELLED` | 踢出要客数 | 时间窗口 | 按 `track_cancel_time` 统计指定时间范围内的踢出要客 |
| `VIP_BY_STAFF` | 按导购统计要客数 | 快照+分组 | 按 `track_user_id` 分组统计当前要客数 |
| `VIP_BY_STORE` | 按门店统计要客数 | 快照+分组 | 按 `member_allot_store` 分组统计当前要客数 |
| `VIP_CONSUMPTION_BY_STORE` | 按门店统计要客消费 | 分组（订单+会员） | 按分配门店分组统计要客累计消费、有消费要客数、订单数、人均消费；金额取 `expense_calendar_info.expense_realamt`（有效订单 `expense_state='10' AND del_flag='0'`），按 `expense_mobile = member_mobile` 关联会员（1:1 不重复累计）并限定 `track='1'`。此处的“要客数”为窗口内有消费的要客数，非当前要客总数。与 CRM 会员列表页金额口径不等价（CRM 用 18 个月快照 `member_expense_totalsum_year`，非实时、子查询未加 `expense_state='10'`） |

**查询方式**：通过 `queryMemberMetric` 工具，传入 `metric` 参数（如 `VIP_OVERVIEW`）。

**口径说明**：
- 要客判定：`track = 1`（主动标记，非消费金额自动判定）
- 数据来源：`member_info_view`（由触发器从 `member_info` 同步的物理表，非 SQL 视图；即 CRM 会员信息列表页 `MemberInfoDao.findList` 的数据源）
- 有效会员限定：`del_flag = '0'`
- 快照指标不注入时间条件，总数不随时间窗口变化
- 时间窗口指标（VIP_NEW/VIP_CANCELLED）缺省窗口为近一年半

**框架限制说明**：
- 每个指标仅支持一个 `timeColumn`
- 因此要客总数（快照）、新增（按 track_time）、踢出（按 track_cancel_time）拆分为独立指标
- 如需同时查看总数+新增+踢出，需分别调用三个指标

**真库验证结果（2026-11-02）**：
- 测试库：crmtest
- 测试用例：12 个对账测试全部通过（VipMetricsReconciliationTest）
- 实测数据：
  - 要客总数：10,779（track=1 且 del_flag='0'）
  - 累计消费总额：98,485,021.43 元
  - 近一年半消费总额：66,848,372.22 元
  - 人均消费：9,136.75 元
  - 人均年消费：6,201.72 元
  - 新增要客（2025-01-01 ~ 2026-06-28）：10,777
  - 踢出要客（同期）：12,873
  - 涉及导购数：484 人
  - 涉及门店数：192 家
- 数据质量：
  - track_time 填充率：99.98%（10,777/10,779）
  - track_cancel_time 有 12,873 条记录
  - member_info_view 总行数：428,930（del_flag='0': 428,929）
  - member_info 总行数：628,528（del_flag='0': 628,527）
  - 背景：`member_info_view` 与 `member_info` 行数差异约 20 万，来自触发器同步范围，属已知背景；`member_info_view` 就是 CRM 会员信息列表页口径，不影响与其对齐

## 七、Phase 1 实现与真库验证状态

三个只读聚合指标均已通过 `@SpringBootTest` 集成测试在**真库**逐值验证。但验证所用数据集已变：2026-10-30 数据源从 5000 行样本库（`springai-items-mcp`）切为**真实测试库 `crmtest`**，旧基线数字全部失效。

### 历史基线（5000 行样本库，窗口 2026-06-20 ~ 06-27）—— 已失效，仅作变更记录
- 会员总览：总数 5000｜男 4880 / 女 120 / 未知 0｜有消费会员 3993｜累计消费 2,660,605.52。
- 门店业绩：228 组，合计销售额 2,445,299.92，Top1 哈市鸿翔路旗舰店 117,906.50。
- 商品排行：Top1 = 53 度飞天茅台 890,272.00 / 销量 532。

### 真实库 crmtest 实测（2026-10-30 Step 0 只读探测，建议固定区间 2026-06-01 ~ 06-27）
量级：`expense_calendar_info` 391,093 / `expense_calendar_details` 15,294,611 / `member_info` 628,528（`del_flag='0'` 628,527）。
- 会员总览（全局无过滤）：总 **628,527**｜男 **592,032** / 女 **29,969** / 未知 **6,526**（三分区求和=总数 ✓）｜有消费会员 **577,042**｜累计消费 **2,102,473,940.12**。
- 门店业绩（上述窗口）：**314 组**｜合计 **113,923,128.62**｜订单 **185,228**｜Top1 `01010102` 哈市鸿翔路旗舰店 **4,960,148.38 / 3,134 单**；空门店编码 398 行（其中 372 行连门店名也为空）归为 1 组。
- 商品排行（同窗口）：Top1 53度飞天茅台 **41,084,915.90 / 24,198**；Top5 = SKU `198.0`「198plus」2,380,158.00 / 12,021。
- 交叉校验：同窗口订单头合计 113,923,128.62 vs 明细合计 113,892,639.62，差 30,489 元（**0.027%**），可作“不重复累计”一致性阈值。
- 以上数字已启用为对账基线 `mcp-server/src/test/resources/baseline/crmtest-baseline.yaml`（**随仓库提交**，2026-10-30 确认：真库对账测试需要它，不入库会导致新人克隆后报错、CI 无法复现；文件内只允许聚合数值与门店/商品名，禁止个人级字段），真库测试通过 `BaselineFixtures` 读取，重新体检 = 重跑 `python scripts/probe/step0_baseline_full.py`（脚本固定保留在仓库），测试代码不动。

已确定的口径补充：
- **空门店编码**：归为“未知门店”，仍单独成行（现由指标配置 `nullDimensionLabel` 声明，编译层统一实现）。
- **异常 SKU**（如 `198.0`）：已确认按**真实商品**统计，不过滤、不合并、允许进榜（2026-10-30）。
- **默认时间范围**：已由 2026-10-29 产品确认改为**近一年半**（取代旧“当前自然月”，含 Phase 1 旧工具），全局跨度上限 366→550 天；2026-10-30 新增**指标级上限**（`maxRangeDays`）：全局 550 天作为缺省，指标可收紧（如 `PRODUCT_RANKING` 92 天），缺省窗口越界自动收敛、显式越界直接拒绝；因测试库为离散月份块且商品排行长窗口超时，**验证与对账必须显式传历史区间**。
- **`tenant` 维度已全面取消**（2026-10-30 业务决策，不是“不可用”而是“不再支持”）：取消依据是 `member_tenant` 100% 为空、`expense_tenant` 61.6% 为空（非空唯一值仅 `小酒喔`），统计焦点改为**门店**。三层落地：指标配置不再声明任何 tenant 维度/过滤；`QueryPlanValidator` 对未声明的 `TENANT` 过滤编码抛 `INVALID_PARAM`（**显式拒绝而非静默忽略**）；工具层对 `tenant` 入参直接返回“已不支持”错误且不触库。Request DTO 保留 `tenant` 字段仅作入参兼容。

### 实现形态（2026-10-29 更新）
- 三指标已由手写 Mapper/XML 迁移为**指标即配置（Metrics-as-Code）**：口径定义在 `resources/metrics/{member,order}.yaml`，SQL 由 `SqlCompiler` 确定性编译生成，口径说明即 YAML 的 `caliber` 字段，可通过 `explainMetric` 工具查看实际生成的 SQL 与指纹。
- 迁移验证：`mvn -o -pl mcp-server test` → **BUILD SUCCESS，Tests run: 46, Failures: 0, Errors: 0**；其中 `MetricsAsCodeMigrationReconciliationTest` 对真库将编译层结果与旧手写 SQL **逐行逐值对账一致**（上述基线全部命中），口径未因迁移发生任何变化。
- 过渡期旧 `@Tool`（queryMemberOverview/queryStationPerformance/queryProductRanking）签名与返回结构保持不变；同时新增域工具 `queryMemberMetric`/`queryOrderMetric`/`listMetrics`/`explainMetric`。新指标只需新增 YAML，Java 零改动。

### Phase 2 新指标接入（2026-10-29）
- `resources/metrics/member.yaml` 新增 5 个指标（全部 `CONFIRMED`）：`MEMBER_NEW`、`ACTIVE_MEMBER`（指标级窗口 3 个月）、`MEMBER_LEVEL_DIST`（六层级维度）、`AGE_DIST`（生日 CASE 分桶）、`ANNUAL_AVG_CONSUMPTION`（窗口 18 个月）；框架侧新增 `defaultWindowMonths` 与 PII“计算输入白名单例外”机制（`piiComputeAllowedColumns`，裸列输出仍一律拒绝）。
- 真库逐值对账（区间原为 2026-06-20~27，2026-10-30 已统一换为基线区间 **2026-06-01 ~ 06-27**，vs 手写 oracle SQL，`Phase2MetricsReconciliationTest`）全部一致；Step 0 实测事实（阳历生日全空、层级字段填充不均、`member_tenant` 全空）已写入各指标 `caliber`。
- 全量回归：`mvn -o -pl mcp-server test` → **BUILD SUCCESS，Tests run: 68, Failures: 0, Errors: 0**。详见 DEVELOPMENT_LOG 2026-10-29（续三）。

## 八、2026-10-30 四项口径决策落地

产品/用户本轮给出四项决策，均已改为代码+配置+真库测试闭环：

| # | 决策 | 落地方式 | 验证结果 |
|---|---|---|---|
| 1 | 不再支持 `member_tenant` 及其他 tenant 筛选/统计，关注**门店** | 配置删声明 + 校验器拒未知编码 + 工具层显式报错 | `SqlCompilerTest.tenantFilter_noLongerCompiled`、`MetricDefinitionValidatorTest.noMetricReferencesTenantColumns`、`CrmAnalyticsToolTest.station_rejectsTenantWithoutQuery`/`product_notesTenantIgnored` 全绿 |
| 2 | 异常 SKU 按真实商品统计 | `order.yaml` 的 `caliber` 记录结论，不加过滤条件 | 基线 Top10 含 rank5 `198.0`「198plus」2,380,158.00 / 12,021，与编译层逐值一致 |
| 3 | 商品排行走“指标级跨度上限”方案 | 框架新增 `maxRangeDays`（Parser/Validator/QueryPlanValidator）+ `maxRangeDays: 92` + `defaultWindowMonths: 3` | `QueryPlanDefaultWindowTest`（8 用例）、`CrmAnalyticsToolTest.product_rejectsRangeOverMetricCap`（177 天区间被拒、不触库） |
| 4 | 接受周岁偏差，年龄改阴历生日 | `member.yaml` `AGE_DIST` 改 `member_yin_birthday` + `STR_TO_DATE`；`member_yin_birthday` 入 `piiComputeAllowedColumns` | `SqlCompilerTest` 阴历 golden SQL、`Phase2MetricsReconciliationTest.ageDist_matchesLunarOracle` 逐分桶一致 |

基线机制：对账数值从测试代码里抽到 `baseline/crmtest-baseline.yaml`（已入库），由 `BaselineFixtures` 读取；连到非 crmtest 库时用 JUnit `Assumptions` **跳过**而不是误报口径失败。本轮因此发现并修正一处基线采集口径错误：`storeGroupCount` 原用 `COUNT(DISTINCT member_store)`（忽略 NULL）与 `GROUP BY` 行数不等（260 vs 261），已改为直接数 `GROUP BY` 结果行并附带 `distinctStoreCodes` 区分两者语义。

构建环境说明：本机无独立 Maven/Gradle，测试通过 IDEA 自带 Maven 发行版离线执行（`JAVA_HOME` 指向 corretto-21）。“无 mvn 所以未跑测试”的限制已不成立。

尚未验证：端到端自然语言对话链路（mcp-server + mcp-client + 模型选择工具）；数据权限（服务端身份→门店范围）仍未接入，仅限测试环境使用。

## 九、路线 C：框架受控 LEFT JOIN 能力升级（2026-10-30）

背景：回答“男的买的多还是女的买的多”需要把**性别作为分组维度**，而性别在 `member_info`、消费金额在 `expense_calendar_info`，单表指标无法跨表分组。选择路线 C（升级框架支持受控 JOIN）而非单指标子查询，目的是“写对一次、全指标共享”。

框架改动（均为受控、白名单化，不允许自由 SQL/子查询进入）：
- `MetricDefinition` 新增可选 `Join(table, alias, mainAlias, on)`；`SqlCompiler.fromClause` 固定渲染 `主表 mainAlias LEFT JOIN 表 alias ON on`，合计查询与分组查询共用同一 FROM。
- `MetricDefinitionParser` 解析 `join` 节点（含 SnakeYAML YAML 1.1 把未加引号 `on:` 当布尔键 `true` 的陷阱兼容）。
- `MetricDefinitionValidator` 加载期安全总闸：join 表必须已登记、别名合法且不得顶替表名、mainAlias 合法、`on` 整串复核为单一限定等值、关联列必须在 PII 计算白名单内；声明 join 后列引用强制带限定符（mainAlias/alias），两表同名列（`del_flag`）不再歧义。
- `member_mobile` 加入 `piiComputeAllowedColumns`（仅作关联键，仍禁止原值输出）。

正确性与性能（`scripts/probe/step0_probe_gender_join_perf.py` 只读实测）：
- **基数**：`member_info.member_mobile` 全局唯一（重复组=0），LEFT JOIN **零行放大**；三组（男/未知/女）求和与 `stationPerformance` 合计逐值相符 → 金额可信。
- **性能**：固定窗口（06-01~06-27，185,228 单）join 分组冷跑 ~2.29s / 热跑 ~2.04s，远低于应用 60s hint / 65s 兜底（原 25s/30s，2026-11-03 上调）。EXPLAIN：会员侧走 `index_mobile` 唯一回表 1 行；订单侧走 `index_state`（低选择性）带 temporary+filesort，演示阶段可接受。
- 真库回归：`MetricsAsCodeMigrationReconciliationTest.genderComparison_joinMatchesOracleAndInvariant` 与独立 oracle SQL 逐组一致 + 不变式断言全部通过。

待确认：分组枚举已于 2026-10-30 由用户定档——保留第三桶、**不拆分**，未关联会员与“匹配会员但 sex 非 1/2”合并第三桶（依 §1.5 #9 所有消费均计会员消费）；第三桶展示名 2026-11-03 由用户改为**‘未知性别’**（原‘会员性别缺失’，仅改标签、分组语义不变）；仍待业务复核的是“实付/订单数/去重买家”三度量选型是否为老板真正想要的对比维度。

## 十、Ad-hoc 自助分析（2026-11-03）

### 10.1 定位与边界

Ad-hoc 自助分析是与固化指标**并存**的探索性查询能力：允许在白名单范围内自由组合维度、度量、过滤和时间窗口，回答固化指标未覆盖的组合类问题（如“各门店要客数按性别交叉”）。

- 已确认口径的业务问题**仍以固化指标工具为准**（`queryMemberMetric`/`queryOrderMetric`）；Ad-hoc 用于探索与固化指标未覆盖的组合。
- Ad-hoc **不是任意 SQL 执行**：模型只能引用语义层声明的名字（实体/维度/度量/条件模板），值一律参数化，SQL 由服务端确定性编译。
- **一次 `adhocQuery` 只允许一条实体链、一种度量粒度**；同一问题包含多个独立组合时使用 `adhocQueryBatch`（最多 5 块，块间不 join，单块失败不影响其余块）。
- 与固化指标的口径等价性已由真库交叉对账证明（`AdhocReconciliationIT` 9 例：VIP 快照计数/条件消费/派生人均/192 门店分组/性别 join 逐值相等，另含 detail 单表行数与实付金额 vs 独立 oracle、detail→member LEFT JOIN 不乘行、守卫负例）。

### 10.2 语义层事实来源

- 唯一事实来源：`mcp-server/src/main/resources/semantic/metadata.yaml`。
- **扩展可查列/维度/度量/条件模板 = 只改 YAML，Java 零改动**（重启生效）。新增列必须真实存在于 `DATABASE_SCHEMA.md` 记录的 DDL，否则加载期硬校验直接失败。
- 当前覆盖 3 个实体：
  - `member` → `member_info_view`（别名 mv），5 维度 / 3 度量 / 3 时间列 / 2 条件模板（IS_VIP、CANCELLED），基线过滤 `mv.del_flag = '0'`；`HAS_EXPENSE` 已于 2026-11-10 移除（条件模板只能绑定本实体字段，原快照判定 `member_expense_totalsum > 0` 非实时，等价需求走 `MEMBER_CONSUMPTION`）；
  - `order` → `expense_calendar_info`（别名 e），3 维度 / 3 度量 / 1 时间列，基线过滤 `expense_state='10' AND del_flag='0'`；
  - `detail` → `expense_calendar_details`（别名 ed，1,529 万行明细表），6 维度 / 5 度量 / 1 时间列，基线过滤 `ed.expense_d_state='10' AND ed.del_flag='0'`（列名带别名限定，避免 join 时歧义），**带查询守卫**（见 §10.3 #9）；
  - 实体间声明 LEFT JOIN 关系及 `cardinality`（`to_one`/`to_many`）：member↔order 双向、member→detail（`to_many`）、detail→member（`to_one`），关联键 `expense_d_mobile`/`expense_mobile` + joinFilters 口径注入；跨实体查询走元数据声明的方向，不允许模型自定 JOIN。`detail→order` 在 `expense_d_orderId = expense_orderId` 唯一性核查通过前不声明，三表信息用 `adhocQueryBatch` 拆块。
- 度量粒度：`listSemanticCatalog` 的 `measures[].grain` 为度量所属实体；实体链行粒度 = 最后一个 `to_many` 关系的目标实体（全 `to_one` 时为主实体）。一次查询只能选行粒度实体的度量，且实体链必须"先一对多、后一对一"。
- 全局约束：默认返回 100 行、上限 1000 行；维度 ≤5、度量 ≤10、过滤 ≤10。实体级约束可覆盖全局缺省（detail 缺省 500 行、时间窗上限 92 天）。

### 10.3 安全边界清单

| # | 边界 | 实现 |
|---|------|------|
| 1 | 白名单名字解析 | 列/度量/条件模板/时间列只能在 `metadata.yaml` 声明的名字中解析，未声明即 `INVALID_PARAM`，模型无法引用表外列 |
| 2 | 值一律参数化 | 编译器只生成占位符 `?`，值经 PreparedStatement 传入，无字符串拼接 |
| 3 | 操作符白名单 | 仅 `= != > < >= <= LIKE IS NULL IS NOT NULL`，其余拒绝 |
| 4 | PII 禁止原值输出 | 手机号/姓名等 PII 列不可作维度/输出；`expense_mobile`、`expense_d_mobile` 仅允许作为 JOIN 键、定向条件与 `COUNT(DISTINCT)` 计数（PII 计算例外，2026-11-03 业务授权，原值仍禁止出现在结果与日志） |
| 5 | VARCHAR 数值自动 NUM() | 金额/数量 VARCHAR 列聚合时编译器自动包 `CAST(NULLIF(TRIM(x),'') AS DECIMAL)`，与固化指标同一 golden 口径 |
| 6 | 行数限制 | LIMIT 缺省 100、上限 1000，越界请求直接拒绝；实体级 `defaultLimit` 可覆盖缺省（detail=500） |
| 7 | 查询超时 | SQL 注入 `/*+ MAX_EXECUTION_TIME(60000) */` 提示，60 秒上限（客户端 65 秒兜底；编译器与执行层各加一次，已做幂等，重复 hint 是语法错误）。MySQL 返回 3024/1317 时识别为超时：Ad-hoc 返回 `ADHOC_QUERY_TIMEOUT`，固化指标返回 `QUERY_FAILED` + 超时说明，要求缩小时间范围/增加过滤，禁止原参数重复 |
| 8 | 只读接缝 | 执行层复用既有受控查询通道，无任何写路径 |
| 9 | 大表查询守卫 | `metadata.yaml` 实体级 `queryGuard.requiredAnyOf` 声明"必须至少满足其一"的定向条件（detail：`timeRange` / `expense_d_mobile` / `expense_d_orderId`），服务端在编译前裁决，违反直接 `INVALID_PARAM` 并回读原因；实体级 `maxTimeRangeDays` 可收紧全局时间窗上限（detail=92 天，全局 550 天）。守卫对**实体链上任意带守卫实体**生效（member+detail 时 detail 仍需自身条件），定向列只认等值过滤（`LIKE`/`IS NULL`/`!=` 不放行），守卫条件列名在加载期按标识符白名单校验 |
| 10 | 意图→列约定与索引保护 | 实体级 `filterGuidance` 随 `listSemanticCatalog` 透出（只指导模型选列，不进 SQL、不做权限）。**门店过滤一律编码优先（2026-11-03 用户决策）**：`expense_stationName`/`expense_d_stationName` 无索引，已移出 order/detail 的 `filterableColumns`（服务端拒绝名过滤，提示仅可输出）；门店条件只能用编码列等值（有 `index_stationCode`）；名称→编码先用 member 实体按 `member_allot_stationName`（有索引，member 侧允许名称过滤）解析。可过滤列逐列带 `indexed` 标注（`metadata.yaml` `indexedColumns`，加载期校验 ⊆ filterableColumns，DDL 为准），模型组参优先选 indexed=true 的列收敛扫描量 |
| 11 | 粒度守卫 | 实体链禁止"先 `to_one` 再 `to_many`"（如 detail→member→order）；度量必须属于行粒度实体，混粒度返回 `INVALID_PARAM` 并提示 `adhocQueryBatch`。编译器对 join 侧 `COUNT(*)` 改用 `COUNT(countKey)`，避免 LEFT JOIN NULL 扩展行被计成 1 |

### 10.4 度量四类型语义与编译示例

| 类型 | 语义 | 编译 SQL 示例（一行） |
|------|------|----------------------|
| `simple` | 直接按元数据声明的 agg 聚合列 | `SUM(CAST(NULLIF(TRIM(e.expense_realamt),'') AS DECIMAL))`（VARCHAR 自动 NUM） |
| `conditional` | 只统计满足已声明条件模板的行（条件内 NULL 落 0） | `SUM(CASE WHEN mv.track = ? THEN 1 ELSE 0 END)`（IS_VIP，参数 `"1"`） |
| `distinct` | 去重计数（仅限声明 `COUNT_DISTINCT` 的度量） | `COUNT(DISTINCT e.expense_mobile)` |
| `derived` | 本请求内已声明度量的四则运算，**Java 层确定性计算**（非 SQL） | 人均 = `TOTAL_EXPENSE / MEMBER_COUNT`（scale=2，由 `DerivedCalculator` 补值） |

条件聚合只能引用元数据 `conditionPresets` 声明的模板；新增模板（如“非要客” `IS_NOT_VIP`）= 在 `metadata.yaml` 加一行，无需改代码。

### 10.5 已知限制

- 不支持子查询、不支持窗口函数。
- 派生度量只能引用**同一请求内**已声明的度量别名，不支持跨请求嵌套派生。
- JOIN 仅支持元数据声明的方向与关联键（`metadata.yaml` `relationships`），模型不能自定义关联。
- 复杂组合（多 join、多条件聚合）的查询耗时缺乏索引层面验证，上线前应对高频组合用 `explainAdhoc` 预演执行计划。
- `detail` 实体的度量口径（`DETAIL_SALES` 行级实付求和、`DETAIL_STD_AMT` 行级标准金额求和）**尚未与业务确认是否等于订单级实付**：明细行金额与 `expense_calendar_info.expense_realamt` 是否逐单对齐需抽样复核后才能对外引用；当前只作为探索性组合，对外数字优先引用固化指标。
- `detail` 与 `member`/`order` 的关系只声明单向左 JOIN（明细 → 会员）。反向 join 会把会员/订单级快照金额按明细行乘翻，属已知口径事故模式，元数据层面直接不给入口。

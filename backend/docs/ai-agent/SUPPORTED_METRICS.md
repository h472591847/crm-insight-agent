## 当前可支持指标清单（含自然语言示例）

### 一、会员域指标

#### 1. `MEMBER_OVERVIEW` — 会员总览

**能力：** 会员总数、性别分布（纯人数口径；消费金额见 `MEMBER_CONSUMPTION`）

**口径：** 数据源 `member_info_view`，固定条件 `del_flag='0'`。该表就是 CRM 会员信息列表页的数据源：`MemberInfoController → MemberInfoService.findPage → MemberInfoDao.findList` 实际 `FROM member_info_view a`、固定条件仅 `a.del_flag='0'`，与本指标取数来源和过滤完全一致，人数可直接与 CRM 列表页对齐。`member_info_view` 是 `member_info` 行更新后由触发器同步的物理表（不是 SQL 视图）；`member_info` 有效会员 628,527 行与它的 428,930 行差异属触发器同步范围背景，不影响一致性。

| 自然语言示例                   |
| ------------------------------ |
| "我们一共有多少会员？"         |
| "男会员和女会员各有多少人？"   |
| "哈市鸿翔路旗舰店有多少会员？" |

---

#### 2. `MEMBER_NEW` — 新增会员数

**能力：** 按注册门店统计新增会员数，支持门店分组

| 自然语言示例                                     |
| ------------------------------------------------ |
| "上个月新增了多少会员？"                         |
| "今年各门店新增会员数排名是怎样的？"             |
| "2026 年 6 月份哈市鸿翔路旗舰店新增了多少会员？" |
| "最近三个月哪个门店新增会员最多？"               |
| "本季度各门店的新会员数量对比"                   |

---

#### 3. `ACTIVE_MEMBER` — 活跃会员数

**能力：** 近 3 个月内有消费的去重会员数、订单数，按门店分组

| 自然语言示例                                |
| ------------------------------------------- |
| "最近三个月有多少活跃会员？"                |
| "各门店的活跃会员数排名"                    |
| "哈市鸿翔路旗舰店近 3 个月有多少活跃会员？" |
| "活跃会员一共产生了多少笔订单？"            |
| "哪个门店的活跃会员最多？"                  |

---

#### 4. `MEMBER_LEVEL_DIST` — 会员层级分布

**能力：** 按 11 个层级字段分组统计（客户级别、新会员等级、维护级别、公司级别、城市级别、门店级别、个人级别、消费层级、重要度、一年半销售级别、全年销售级别），覆盖会员层级追问的全部级别维度。

**级别取值（2026-11-10 按 CRM `SetLevelDao.getGrade()` 真库核实）：** `company_level`（公司级别/毛利级别，18 个月）21 个：AA~AC、BA~BC、CA~CC、DA~DC、EA~EC、FA~FC、GA~GC；`half_year_level`（一年半销售级别，18 个月）17 个：AA~AC、BA~BC、CA~CC、DA~DC、EA~EC、FA~FB；`total_year_level`（全年销售级别，全部时间）24 个：AA~AC 至 HA~HC；`city_level`（城市级别）8 个 A~H；`store_level`（门店级别）8 个 A~H；`personal_level`（个人级别）真库 8 个 a~f；`reserved15`（海鼎客户级别）9 个中文标签（高级会员/VIP/至尊VIP/PLUS会员/V级企业VIP/白金VIP/VV级企业VIP/企业VIP/喔淘会员）。`set_level` 只有编码、无中文名称，原样输出；空值（NULL/空串）统一归“未填写”，不会产生空字符串分组。`company_level` 另有 1 条游离值 `'A'`（`set_level` 的父级行 `level_code='companyLevel'`，CRM 下拉不展示，且该会员不在 `member_info_view` 中），属数据异常/父级码，原样输出、不映射等级含义。

| 自然语言示例                        |
| ----------------------------------- |
| "会员的层级分布是怎样的？"          |
| "各客户级别的会员有多少？"          |
| "按消费层级看，高级会员占比多少？"  |
| "新会员等级分布情况"                |
| "全年销售级别为 AA 的会员有多少人？" |
| "维护级别分布"                      |

---

#### 5. `AGE_DIST` — 年龄分布

**能力：** 按阴历生日计算周岁，分桶统计（≤19/20-29/30-39/40-49/50-59/≥60/未知）

| 自然语言示例                     |
| -------------------------------- |
| "会员的年龄分布是怎样的？"       |
| "30-39 岁的会员有多少人？"       |
| "年轻会员（20-29 岁）占比多少？" |
| "60 岁以上的会员有多少？"        |
| "各年龄段的会员数量对比"         |
| "会员年龄分布图表数据"           |

---

#### 6. `ANNUAL_AVG_CONSUMPTION` — 单客年均消费

**能力：** 窗口内总实付消费 / 有消费会员数，支持门店分组

**口径：** 按订单 `expense_buyDate` 实时重算，不读取 CRM 列表页的 18 个月快照 `member_expense_totalsum_year`（该快照非实时、子查询未加 `expense_state='10'`），两者数字可能不同。

| 自然语言示例                           |
| -------------------------------------- |
| "平均每个会员一年花多少钱？"           |
| "各门店的单客年均消费排名"             |
| "哈市鸿翔路旗舰店的客均年消费是多少？" |
| "近一年半会员平均消费金额"             |
| "哪个门店的会员消费能力最强？"         |

---

#### 7. `MEMBER_CONSUMPTION` — 会员消费金额（订单实付）

**能力：** 基于消费流水统计累计消费总额、有消费会员数（去重手机号）、订单数、人均消费，支持门店分组

**口径：** 金额取 `expense_calendar_info.expense_realamt`（不再取 `member_info_view.member_expense_totalsum` 会员快照）；有效订单 `expense_state='10' AND del_flag='0'`，退单负行自然抵消；按 `expense_mobile = member_mobile` 关联会员（1:1，不重复累计）；不传时间范围即全部历史累计。

**与 CRM 会员列表页不等价：** CRM 列表页的金额区间用的是 18 个月快照 `member_expense_totalsum_year`（由订单 `expense_realamt` 回写、非实时，且其快照子查询未加 `expense_state='10'`，可能把已退单金额计入）；本指标为订单实付实时汇总，两者在退单处理与更新时点上可能不同，回答时不得声称与 CRM 列表页数字一致。

| 自然语言示例                             |
| ---------------------------------------- |
| "所有会员累计消费了多少钱？"             |
| "有多少会员消费过？"                     |
| "平均每个有消费的会员花了多少钱？"       |
| "各门店会员消费总额排名"                 |
| "哈市鸿翔路旗舰店的会员消费额是多少？"   |

---

#### 8. `VIP_OVERVIEW` — 要客总览

**能力：** 当前要客总数（快照指标，不随时间窗口变化）；消费金额见 `VIP_CONSUMPTION_BY_STORE`

| 自然语言示例             |
| ------------------------ |
| "我们一共有多少要客？"   |
| "当前要客总数是多少？"   |

---

#### 9. `VIP_NEW` — 新增要客数

**能力：** 按 `track_time` 统计指定时间范围内的新增要客数量

| 自然语言示例                             |
| ---------------------------------------- |
| "上个月新增了多少要客？"                 |
| "今年新增要客总数"                       |
| "2026 年 6 月份新增要客数"               |
| "最近三个月新增要客趋势"                 |
| "本季度新增了多少要客？"                 |

---

#### 10. `VIP_CANCELLED` — 踢出要客数

**能力：** 按 `track_cancel_time` 统计指定时间范围内的踢出要客数量

| 自然语言示例                             |
| ---------------------------------------- |
| "上个月踢出了多少要客？"                 |
| "今年踢出要客总数"                       |
| "2026 年 6 月份踢出要客数"               |
| "最近三个月踢出要客趋势"                 |
| "本季度踢出了多少要客？"                 |

---

#### 11. `VIP_BY_STAFF` — 按导购统计要客数

**能力：** 按导购（`track_user_id`）分组统计当前要客数，支持排序

| 自然语言示例                             |
| ---------------------------------------- |
| "每个导购有多少要客？"                   |
| "要客数量最多的导购排名"                 |
| "各导购的要客数对比"                     |
| "哪个导购的要客最多？"                   |
| "导购要客分布情况"                       |

---

#### 12. `VIP_BY_STORE` — 按门店统计要客数

**能力：** 按分配门店（`member_allot_store`）分组统计当前要客数，支持排序

| 自然语言示例                             |
| ---------------------------------------- |
| "每个门店有多少要客？"                   |
| "要客数量最多的门店排名"                 |
| "各门店的要客数对比"                     |
| "哪个门店的要客最多？"                   |
| "门店要客分布情况"                       |
| "哈市鸿翔路旗舰店有多少要客？"           |

---

#### 13. `VIP_CONSUMPTION_BY_STORE` — 按门店统计要客消费

**能力：** 按门店分组统计要客累计消费总额、有消费要客数、订单数、人均消费，支持排序

**口径：** 金额取订单实付 `expense_calendar_info.expense_realamt`，按 `expense_mobile = member_mobile` 关联会员并限定 `member.track='1'`（当前要客）；有效订单 `expense_state='10' AND del_flag='0'`。这里的“要客数”是**窗口内有消费的要客数**（去重手机号），不等于 `VIP_OVERVIEW` 的当前要客总数。CRM 会员列表页的金额区间用 18 个月快照 `member_expense_totalsum_year`（非实时、子查询未加 `expense_state='10'`），与本指标不等价。

| 自然语言示例                             |
| ---------------------------------------- |
| "各门店要客消费总额排名"                 |
| "哪个门店的要客消费能力最强？"           |
| "门店要客人均消费对比"                   |
| "哈市鸿翔路旗舰店的要客消费情况"         |
| "各门店要客消费汇总表"                   |
| "要客消费 Top 10 门店"                   |

---

### 二、订单/门店/商品域指标

#### 14. `STATION_SALES` — 门店销售业绩

**能力：** 门店销售额、订单数、客单价，支持门店分组与排序

| 自然语言示例                       |
| ---------------------------------- |
| "上个月各门店的销售额排名"         |
| "哈市鸿翔路旗舰店的销售额是多少？" |
| "哪个门店的订单数最多？"           |
| "各门店的客单价对比"               |
| "本月销售额 Top 10 门店"           |
| "2026 年 6 月份总销售额是多少？"   |
| "门店业绩汇总表"                   |

---

#### 15. `PRODUCT_RANKING` — 商品销售排行

**能力：** 按 SKU 统计销售额/销量，支持门店过滤、TopN 排行（时间上限 92 天）

| 自然语言示例                     |
| -------------------------------- |
| "最畅销的商品是哪些？"           |
| "销售额 Top 10 商品"             |
| "销量最高的商品排名"             |
| "53 度飞天茅台卖了多少？"        |
| "哈市鸿翔路旗舰店的商品销售排行" |
| "上个月哪些商品卖得最好？"       |
| "按销量看，商品排行是怎样的？"   |

---

#### 16. `GENDER_CONSUMPTION_COMPARE` — 男女消费对比

**能力：** 按会员性别分组对比销售额、订单数、去重买家数（JOIN 会员表）

| 自然语言示例                       |
| ---------------------------------- |
| "男的买的多还是女的买的多？"       |
| "男性会员和女性会员的消费对比"     |
| "按性别看销售额差异"               |
| "男会员和女会员各贡献了多少订单？" |
| "性别维度的消费能力分析"           |
| "有多少男性/女性客户消费过？"      |

---

### 三、指标能力速查表

| 指标编码                     | 中文名称         | 典型问题关键词               | 时间窗口                  |
| ---------------------------- | ---------------- | ---------------------------- | ------------------------- |
| `MEMBER_OVERVIEW`            | 会员总览         | 多少会员、性别分布           | 无（存量）                |
| `MEMBER_NEW`                 | 新增会员数       | 新增、新会员、注册           | 默认 18 个月              |
| `ACTIVE_MEMBER`              | 活跃会员数       | 活跃、最近消费、有消费       | 默认 3 个月               |
| `MEMBER_LEVEL_DIST`          | 会员层级分布     | 层级、级别、等级分布         | 无（存量）                |
| `AGE_DIST`                   | 年龄分布         | 年龄、岁数、年龄段           | 无（存量）                |
| `ANNUAL_AVG_CONSUMPTION`     | 单客年均消费     | 年均消费、客均、平均花多少   | 默认 18 个月              |
| `MEMBER_CONSUMPTION`         | 会员消费金额     | 累计消费、消费总额、人均消费 | 无（累计）                |
| `VIP_OVERVIEW`               | 要客总览         | 要客总数                     | 无（快照）                |
| `VIP_NEW`                    | 新增要客数       | 新增要客、要客增长           | 默认 18 个月              |
| `VIP_CANCELLED`              | 踢出要客数       | 踢出要客、要客流失           | 默认 18 个月              |
| `VIP_BY_STAFF`               | 按导购统计要客   | 导购要客、员工要客数         | 无（快照）                |
| `VIP_BY_STORE`               | 按门店统计要客   | 门店要客、要客分布           | 无（快照）                |
| `VIP_CONSUMPTION_BY_STORE`   | 门店要客消费     | 要客消费、门店要客业绩       | 无（累计）                |
| `STATION_SALES`              | 门店销售业绩     | 门店销售、业绩、客单价       | 默认 18 个月              |
| `PRODUCT_RANKING`            | 商品销售排行     | 畅销、排行、Top、卖得最好    | 默认 3 个月（上限 92 天） |
| `GENDER_CONSUMPTION_COMPARE` | 男女消费对比     | 男 vs 女、性别对比           | 默认 18 个月              |

---

### 四、暂不支持的问题类型

| 问题类型  | 示例                       | 原因                         |
| --------- | -------------------------- | ---------------------------- |
| 流失/沉睡 | "哪些会员很久没消费了？"   | 业务定义暂不考虑             |
| 复购分析  | "复购率是多少？"           | 产品确认移出 Phase 2         |
| 地域分布  | "各城市会员分布"           | 需 TMS 数据支撑              |
| 品牌分析  | "茅台卖了多少？"（按品牌） | 消费侧无品牌字段             |
| 商品毛利  | "哪个商品毛利最高？"       | 明细无毛利字段               |
| 进销存    | "库存还有多少？"           | 无采购/库存表                |
| 会员占比  | "会员消费占比多少？"       | 所有消费均计会员消费，无意义 |

---

### 五、Ad-hoc 自助分析（工具能力，非固化指标）

2026-11-03 交付的探索性查询工具（`adhocQuery` / `adhocQueryBatch` / `listSemanticCatalog` / `explainAdhoc`），不是第 17 个固化指标：允许在语义白名单内自由组合维度 × 度量 × 条件模板 × 时间窗口，覆盖固化指标未预设的组合问题。可组合的范围以 `mcp-server/src/main/resources/semantic/metadata.yaml` 声明为准（`listSemanticCatalog` 可实时查看，含 PII 标注、可过滤列 `indexed` 索引标注、度量 `grain` 与关系 `cardinality`）。

| 自然语言示例 | Ad-hoc 组合方式 |
| ------------ | --------------- |
| "各门店要客数对比" | member: STORE_CODE × conditional(COUNT + IS_VIP) |
| "近半年新增要客的门店分布" | member: STORE_CODE × conditional(COUNT + IS_VIP) + timeRange(TRACK_TIME) |
| "男女会员的累计消费对比" | order+member: ORDER_GENDER × SUM(SALES) + derived 人均（会员快照无金额度量） |
| "各门店有消费会员数" | 固化指标 `MEMBER_CONSUMPTION`（BUYERS）+ STATION 过滤；adhoc 已不提供“有消费”条件模板 |
| "6 月各门店的销售额和订单数" | order: STATION_CODE × SUM(SALES) + COUNT(ORDER_COUNT) + timeRange(BUY_DATE) |
| "男女会员各消费了多少钱" | order+member: ORDER_GENDER × SUM(SALES)（跨实体 LEFT JOIN，方向由元数据声明） |
| "6 月明细行实付金额合计" | detail: SUM(DETAIL_SALES) + timeRange(BUY_DATE)（守卫：时间窗≤92 天） |
| "某手机号买过哪些商品" | detail: PRODUCT_NAME × DETAIL_LINES + filter(expense_d_mobile=)（守卫条件之一，可免时间窗） |
| "各门店 SKU 销量 Top" | detail: DETAIL_STATION_NAME × SUM(DETAIL_QTY) + timeRange(BUY_DATE) + orderBy |
| "明细行里男女会员谁买得多" | detail+member: DETAIL_GENDER × DETAIL_LINES + SUM(DETAIL_SALES) + timeRange(BUY_DATE) |
| "每个会员的明细消费金额" | member+detail: STORE_CODE/GENDER 等会员属性 × SUM(DETAIL_SALES) + timeRange(entity=detail, ≤92 天)（行粒度=detail，禁选会员快照度量） |
| "会员和订单按门店、会员和明细按商品各看一遍" | `adhocQueryBatch` 两块：① member+order: STATION_CODE × ORDER_COUNT/SALES；② member+detail: CATEGORY_NAME × DETAIL_SALES + timeRange(entity=detail) |

说明：
- **一次 `adhocQuery` 只能一条实体链、一种度量粒度**：`listSemanticCatalog` 的 `grain` 表示度量所属实体，`relationships.cardinality=to_many` 表示会放大行数。member→detail 的行粒度是 detail，只能选 `DETAIL_*`，会员快照度量（如 `MEMBER_COUNT`）会被服务端拒绝；多个独立组合用 `adhocQueryBatch`（最多 5 块，单块失败不影响其余块）。
- **conditional 只能引用已声明的条件模板**（当前 member 实体有 IS_VIP / CANCELLED；`HAS_EXPENSE` 已于 2026-11-10 移除，原因：原判定依赖非实时快照 `member_expense_totalsum`，且条件模板只能绑定本实体字段、无法切到 `member_info` 基础表或订单表，等价判定走 `MEMBER_CONSUMPTION`）。"各门店**非**要客数"目前无法直接回答——需先在 `metadata.yaml` 加一行 `IS_NOT_VIP` 模板（改 YAML 即生效，Java 零改动）。
- 度量支持 simple / conditional / distinct / derived 四类；derived 为本请求内度量的四则运算（如人均、占比），Java 层确定性计算。
- 姓名类 PII 列不可作维度输出；`expense_mobile` 仅允许去重计数。`expense_d_mobile` 于 2026-11-03 授权参与计算（JOIN 键 / 定向条件 / `COUNT(DISTINCT)`），**原值仍禁止出现在结果与日志**。
- **`detail`（`expense_calendar_details`，1,529 万行）带服务端查询守卫**：必须提供时间范围（≤92 天）或 `expense_d_mobile` 或 `expense_d_orderId` 的**等值**过滤之一，否则直接 `INVALID_PARAM` 拒绝，不做无界明细聚合。守卫对实体链上任意 detail 生效：`member+detail` 时时间范围必须显式写 `entity=detail`。守卫声明在 `metadata.yaml` 的 `queryGuard`，随 `listSemanticCatalog` 透出，模型无法放宽。
- **三表组合（member+order+detail）当前不做直连**：`detail→order` 依赖 `expense_d_orderId = expense_orderId` 的唯一性，尚未核查确认；需要三表信息时用 `adhocQueryBatch` 拆成"明细+会员"和"订单+会员"两块，避免跨粒度乘翻。
- **门店过滤一律编码优先（2026-11-03 用户决策）**：info/details 的门店名称列（`expense_stationName`/`expense_d_stationName`）**无索引，已移出过滤白名单**——对它们做 LIKE/等值都会扫全表，服务端直接拒绝并提示可过滤列。门店条件只能用 `expense_stationCode`/`expense_d_stationCode` 等值（有 `index_stationCode`）。用户给的门店名称不完整/不确定时，**先用 `guessStation` 解析**（`member_allot_stationName`，按 精确 → 前缀 → 包含 三级命中即停；前缀可用索引，包含匹配一次扫会员表可接受）：恰好 1 个候选→直接用其编码并在回答中说明实际门店名；**多个候选→停下把编码+名称列给用户，问清要查哪家店，不得自行挑选或全部汇总**；0 个候选→请用户提供门店编码，不得猜。**一句话出现多个门店时，把每个门店原文作为一个元素传 `keywords` 数组逐店解析、逐店统计并在回答中分别列出**，必须保留城市/区前缀（如「哈市长江路」），不得截短成路段名（「长江路」会跨城市命中多家），也不得只回答其中一家。门店名称列仍可作维度输出（`STATION_NAME`/`DETAIL_STATION_NAME`，MAX 展示）。
- **过滤列带 `indexed` 标注（2026-11-03）**：`listSemanticCatalog` 的每个可过滤列附 `indexed: true/false`（true=该列在表上有索引，组参时优先用它收敛扫描量；false=无索引、过滤可能扫全表，如 detail 的 `expense_d_commCodeName`）。标注事实进 `metadata.yaml` 的 `indexedColumns`，以 `DATABASE_SCHEMA.md` 原始 DDL 为准，加载期校验必须 ⊆ `filterableColumns`。
- **查询超时**：超过服务端 60 秒上限时，Ad-hoc 返回 `ADHOC_QUERY_TIMEOUT`（固化指标返回 `QUERY_FAILED` + 超时说明）。此时只能缩小时间范围或增加等值过滤后重查一次，不得用相同参数重复，也不得改用其他口径凑数。客户端 MCP 请求超时已配置为 340 秒（`spring.ai.mcp.client.request-timeout`，dev profile，覆盖批量 5 块最坏 5×65s），保证服务端结果能回传；否则会先被客户端 20 秒默认值截断。

**口径边界提醒**：Ad-hoc 结果为"白名单内列 + 结构化条件"的即时聚合，无业务确认口径正文；对外引用数字时优先用同名固化指标（如要客数优先 `VIP_BY_STORE` 而非 Ad-hoc 组合），两者交叉对账见 `AdhocReconciliationIT`（VIP 快照/消费/分组/性别 join 真库逐值相等；detail 另有单表行数与实付金额 vs 独立 oracle、LEFT JOIN 不乘行两例）。`detail` 的行级金额口径是否等于订单级实付尚未与业务确认，暂不对外引用。
# Phase 1 技术方案（CRM 数据分析 Agent · 评审稿）

> 目标：用**现有数据 + 已确认口径**，交付一批**可立即实现且可对账验证**的只读经营指标，跑通「自然语言 → Tool Calling → 受控 SQL → 结构化结果 → AI 解释」全链路。
>
> 原则：只读、聚合输出（不返回个人信息）、不新增 Maven 依赖、复用 `mcp-server` 现有数据源与工具注册机制。

## 一、Phase 1 范围（口径已完全明确）

| # | 指标 | 说明 |
|---|---|---|
| 1 | 会员总数 | `del_flag='0'` 会员计数 |
| 2 | 会员性别分布 | 男/女/未知 计数 |
| 3 | 会员消费金额汇总 | 总历史消费、人均消费（聚合，非个人级） |
| 4 | 各门店销售额 | 时间范围内按门店 `SUM(expense_realamt)` |
| 5 | 各门店订单数 | 时间范围内按门店 `COUNT(*)` |
| 6 | 各门店 / 全局客单价 | 销售额 / 订单数（Java 端确定性计算） |
| 7 | 商品销售排行 | 按 SKU 的销售额 / 销量 Top N |

不在 Phase 1：品牌维度、商品级毛利、进销存、复购/流失/活跃/沉睡/层级/年龄/地域（口径或缺数据待确认，属 Phase 2/3）。

## 二、总体架构

```
用户自然语言
   │  (mcp-client: ChatClient + defaultToolCallbacks 已就绪，无需改动)
   ▼
MCP over SSE
   ▼
mcp-server 新增 @Tool（只读分析工具）
   ├─ 参数校验（日期/区间/上限）+ 可选过滤（station）〔tenant 已于 2026-10-30 取消〕
   ├─ Mapper + MyBatis XML 受控聚合 SQL（禁止拼接自然语言）
   ├─ VARCHAR 金额安全转换 + 确定性计算（客单价等在 Java 完成）
   └─ 结构化结果 DTO 返回 → 交回模型仅做语言解释
```

- **落点模块**：全部在 `mcp-server`（唯一具备 MySQL/MyBatis 数据源的模块）。
- **数据源**：复用 `application-dev.yml` 现有数据源（CRM 三表约 10 万测试数据已导入该库）。
- **注册**：在 `ToolConfiguration.registerToolCallbackProvider(...)` 的 `toolObjects(...)` 追加工具 bean；Client 侧自动加载，无需改动。

## 三、工具设计（一个组件 `CrmAnalyticsTool`，含 3 个 @Tool）

| 工具 | 职责 | 入参 | 返回 |
|---|---|---|---|
| `queryMemberOverview` | 会员总数/性别/消费汇总 | tenant?, station?（默认不传=全局汇总，传入=按该维度拆分） | 总数、男女未知计数、总/人均消费；分组时附分组键 |
| `queryStationPerformance` | 门店销售额/订单数/客单价 | startDate,endDate(必填), tenant?, stationCode? | 各门店列表 + 合计行 |
| `queryProductRanking` | 商品销售/销量排行 | startDate,endDate, topN?(默认10,≤100), rankBy?(sales|qty), stationCode? | SKU Top N 列表 |

**入参 DTO 约定**（沿用现有 `@ToolParam` 风格）：
- 每个字段带 `@ToolParam(description=..., required=...)`，日期用 `String`(yyyy-MM-dd) 以便模型填充，服务端解析校验。
- 不信任模型入参：非法日期、区间倒置、跨度超限、`topN` 越界一律服务端拒绝并返回可读错误。

## 四、数据访问（Mapper + XML 受控聚合）

> 金额转换统一：`CAST(NULLIF(TRIM(x),'') AS DECIMAL(20,2))`；有效口径统一：`state='10' AND del_flag='0'`；时间左闭右开：`>= startDate AND < DATE_ADD(#{endDate}, INTERVAL 1 DAY)`。
> 下方 XML 片段是 2026-10-28 评审稿原貌（保留作历史记录）：其中的 `<if test="tenant != null">` 分支自 2026-10-30 起**不再生成任何租户条件**（指标层不声明 tenant 维度/过滤，请求层显式拒绝），现行实现见 `resources/metrics/*.yaml` + `SqlCompiler`。

### 4.1 `MemberStatMapper.xml`（会员总览）
```sql
SELECT
  COUNT(*)                                                       AS total_members,
  SUM(CASE WHEN member_sex = 1 THEN 1 ELSE 0 END)                AS male_count,
  SUM(CASE WHEN member_sex = 2 THEN 1 ELSE 0 END)                AS female_count,
  SUM(CASE WHEN member_sex IS NULL OR member_sex NOT IN (1,2) THEN 1 ELSE 0 END) AS unknown_count,
  COALESCE(SUM(member_expense_totalsum), 0)                      AS total_expense_amount
FROM member_info
WHERE del_flag = '0'
  <if test="tenant != null">AND member_tenant = #{tenant}</if>
  <if test="station != null">AND member_allot_store = #{station}</if>;
-- member_expense_totalsum 为 decimal(20,2)，无需字符串转换；人均消费在 Java 端 = total/activeCount
```

### 4.2 `ExpenseStatMapper.xml`（门店业绩）
```sql
SELECT
  expense_stationCode                              AS stationCode,
  MAX(expense_stationName)                         AS stationName,
  COALESCE(SUM(CAST(NULLIF(TRIM(expense_realamt),'') AS DECIMAL(20,2))),0) AS sales_amount,
  COUNT(*)                                         AS order_count
FROM expense_calendar_info
WHERE expense_state = '10' AND del_flag = '0'
  AND expense_buyDate >= #{startDate}
  AND expense_buyDate <  DATE_ADD(#{endDate}, INTERVAL 1 DAY)
  <if test="tenant != null">    AND expense_tenant = #{tenant}</if>
  <if test="stationCode != null">AND expense_stationCode = #{stationCode}</if>
GROUP BY expense_stationCode
ORDER BY sales_amount DESC;
-- 客单价 = sales_amount / order_count，在 Java 端计算（确定性、便于口径统一）
```

### 4.3 `ExpenseStatMapper.xml`（商品排行，走明细表）
```sql
SELECT
  expense_d_gdcode                                 AS sku,
  MAX(expense_d_commName)                          AS product_name,
  MAX(expense_d_commCodeName)                      AS category_name,
  COALESCE(SUM(CAST(NULLIF(TRIM(expense_d_realamt),'') AS DECIMAL(20,2))),0) AS sales_amount,
  COALESCE(SUM(CAST(NULLIF(TRIM(expense_d_qty),'') AS DECIMAL(20,4))),0)     AS quantity
FROM expense_calendar_details
WHERE expense_d_state = '10' AND del_flag = '0'
  AND expense_d_buyDate >= #{startDate}
  AND expense_d_buyDate <  DATE_ADD(#{endDate}, INTERVAL 1 DAY)
  <if test="stationCode != null">AND expense_d_stationCode = #{stationCode}</if>
GROUP BY expense_d_gdcode
ORDER BY ${rankColumn} DESC          -- rankColumn 由服务端从白名单[sales_amount|quantity]取值，禁止外部直传
LIMIT #{topN};
```
> 注：`ORDER BY` 的列名不能参数化占位符，服务端用**白名单枚举**映射（`sales`→`sales_amount`，`qty`→`quantity`），杜绝注入。

## 五、参数校验与错误处理（工具层统一）
- 日期格式 yyyy-MM-dd、`endDate >= startDate`、跨度上限（默认 ≤ 366 天，常量可配）。
- 默认时间范围：入参缺失时取**当前自然月**（本月 1 号 00:00 至今，左闭右开），由服务端计算，不依赖模型填值。

> ⚠️ **文档滞后说明（2026-10-30 补记）**：上面两条已被 2026-10-29 确认的产品口径取代，以代码为准 —— 跨度上限 366→**550 天**（`MetricsLimits.MAX_RANGE_DAYS`），缺省时间范围改为**近一年半**（`DEFAULT_RANGE_MONTHS=18`，指标级 `defaultWindowMonths` 可覆盖，如活跃会员 3 个月）。本文件保留原评审稿措辞作为历史记录，不再逐字修订；最新口径见 `BUSINESS_METRICS.md` §五/§六与 `PHASE2_METRICS_LAYER_TECH_PLAN.md`。
- `topN` 上限（≤100）、`rankBy` 白名单。
- 结果映射为 DTO 返回；空结果 vs 查询失败区分返回明确语义（不编造数字）。
- 复用/扩展 `ServiceLogAspect` 记录调用与耗时；日志不得输出 PII（Phase 1 全为聚合，天然无 PII）。

## 六、涉及文件清单（全部在 `mcp-server`）

**新增**
```
mcp/tool/CrmAnalyticsTool.java                         // 3 个 @Tool + 校验/映射
entity/request/MemberOverviewRequest.java
entity/request/StationPerformanceRequest.java
entity/request/ProductRankingRequest.java
entity/response/MemberOverviewResult.java
entity/response/StationPerformanceResult.java
entity/response/ProductRankingResult.java
mapper/MemberStatMapper.java
mapper/ExpenseStatMapper.java
resources/mapper/MemberStatMapper.xml
resources/mapper/ExpenseStatMapper.xml
src/test/java/.../CrmStatReconciliationTest.java       // 对账测试
src/test/java/.../CrmToolValidationTest.java           // 参数校验单测
```
**修改**
```
mcp/config/ToolConfiguration.java                      // toolObjects 追加 crmAnalyticsTool
```
> 均无新增 Maven 依赖；新 Mapper 位于 `com.github.yingli.mapper`，被 `@MapperScan` 自动扫描。

## 七、实施步骤

1. **Step 0 只读验证（先做，防脏值）**：在 dev 数据源执行只读 SELECT：
   - 三表行数、`expense_buyDate` 时间分布；
   - `expense_realamt` / `expense_d_qty` 是否存在空串/非数字：
     `SELECT COUNT(*) ... WHERE x IS NULL OR TRIM(x)='' OR x NOT REGEXP '^-?[0-9]+(\\.[0-9]+)?$'`；
   - `expense_state` / `del_flag` 取值分布确认（应无 state='20'）。
2. 依验证结果确定金额转换是否需要更严格清洗。
3. 实现 request/response DTO → Mapper + XML → `CrmAnalyticsTool`。
4. 注册到 `ToolConfiguration`。
5. 编写对账测试与校验单测。
6. 端到端：通过 `/chat/doChat` 用自然语言触发工具，核对返回。
7. 更新 `DEVELOPMENT_LOG.md` 与 `BUSINESS_METRICS.md`（Phase 1 指标置为已实现/已验证）。

## 八、测试与验收

- **对账测试**：同一时间范围/门店，工具结果 vs 独立基准 SQL 逐值比对（销售额、订单数、客单价、会员总数、排行 Top N）。
- **异常用例**（对齐 `crm-data-agent.md` 第八节）：缺参、非法日期、区间倒置、超跨度、空结果、DB 异常、退单负数是否正确抵消、明细与订单不重复累计（Phase 1 门店用单表 info、商品用单表 details，规避联查）。
- **端到端**：自然语言问题 → 正确选择工具 → 结果被如实解释。
- 验收标准：每个 Phase 1 指标数值与基准一致；无 PII 输出；无新依赖；现有 demo 功能不受影响。

## 九、实现决策（已由产品/用户 2026-10-28 确认锁定）

1. **默认时间范围**：用户未指定时取**本月**（当前自然月）。〔2026-10-29 已改为**近一年半**，2026-10-30 补记〕
2. **SKU 维度**：商品排行以 `expense_d_gdcode`（商品代码）为准。
3. **tenant 过滤**：所有工具保留可选 `tenant` 参数；测试数据多租户时按传入值限定。〔**2026-10-30 已决定并落地：全面取消**，改为只关注门店统计。取消依据：`member_tenant` 100% 为空、`expense_tenant` 61.6% 为空（非空唯一值仅 `小酒喔`），无区分度。三层实现：① 指标 YAML 不再声明 tenant 维度/可选过滤；② `QueryPlanValidator` 对未声明的 `TENANT` 编码抛 `INVALID_PARAM`（**显式拒绝，不静默忽略**）；③ 工具层收到 `tenant` 入参直接返回“已不支持”说明且不触库。Request DTO 保留 `tenant` 字段仅作入参兼容；Phase 1 手写 Mapper 保留 tenant 形参但仅作对账 oracle、调用方必须传 null〕
4. **会员总览维度**：~~支持按门店/租户拆分~~ →〔2026-10-30〕仅支持按**门店**（`member_allot_store`）拆分，**默认不拆分**（返回全局总数与性别分布）。
5. **权限简化**：Phase 1 不接入真实鉴权，`stationCode` 仅可选过滤（`tenant` 已取消）；生产接入前必须补服务端数据权限校验。
6. **基线数据集变更（2026-10-30）**：数据源已切为真实测试库 `crmtest`（三表 39.1 万 / 1,529 万 / 62.9 万行），本文件与 DEVELOPMENT_LOG 10-28/10-29 中基于 5000 行样本的基线数字（228 组 / 2,445,299.92 / 5000 人 等）**均已失效**，现行基线见 `BUSINESS_METRICS.md` §六。
7. **商品排行时间窗口上限（2026-10-30）**：不再依赖全局 550 天，`PRODUCT_RANKING` 启用**指标级** `maxRangeDays: 92` + `defaultWindowMonths: 3`，超长区间直接拒绝（旧实测 18 个月窗口 75.8s 超时）；同时确认异常 SKU（如 `198.0`）按真实商品统计、不过滤不合并且允许进榜。

---
*本文件为 Phase 1 技术方案评审稿；确认第九节后进入编码。开发遵循：先只读验证、再实现、再对账，全程不新增依赖、不做数据库写操作。*

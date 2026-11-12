# Phase 2 技术方案：指标即配置（Metrics-as-Code）+ SQL 编译层

> **要解决的痛点**：每加一个新指标都要写 Mapper + XML + DTO + @Tool + 测试；已完成的 Phase 1 SQL 也是手写。目标是**让"新增指标"退化成"新增一段配置"**，Java 代码零改动。
>
> **原则（沿用 Phase 1）**：全程只读、聚合输出不含 PII、**不新增 Maven 依赖**、口径未确认一律拒绝执行、数值计算在 Java/SQL 服务端完成、模型只负责理解意图与表达真实结果。

> ⚠️ **实现漂移说明（2026-10-30 补记，以代码为准）**：本文件是 2026-10-28 的方案评审稿，保留了当时的措辞与示例作为历史记录。与现行实现的差异：
> 1. **租户维度已全面取消**：下文示例里所有 `code: TENANT` 的 `dimensions`/`optionalFilters` 与 `AND expense_tenant = ?` 均**已作废**，现行配置只保留门店（`STATION`）；传入 `TENANT` 会被 `QueryPlanValidator` 显式拒绝。
> 2. **时间上限已变**：全局 `MAX_RANGE_DAYS` 366 → **550 天**，缺省区间改为近一年半（`defaultWindowMonths`）；2026-10-30 新增**指标级** `maxRangeDays`（`PRODUCT_RANKING` = 92 天）。
> 3. **§8.2 示例基线已失效**：那组 5000 行样本数字（228 组 / 2,445,299.92 / 117,906.50 / 890,272.00）已作废；现行做法是数值外置到 `src/test/resources/baseline/crm_dev-baseline.yaml`（**随仓库提交**，2026-10-30 确认），由测试类 `BaselineFixtures` 读取，区间固定为 **2026-06-01 ~ 06-27**，并用 `Assumptions` 做“连错库就跳过”守卫。

## 一、核心决策：把"谁写 SQL"和"什么时候写 SQL"拆开

| 路线 | SQL 由谁产生 | 同一问题两次结果 | 能否作为老板看板依据 | 结论 |
|---|---|---|---|---|
| 运行期每次现场生成（纯 NL2SQL） | 模型每次现写 | 可能不同 SQL、不同数字 | **不能**（不可复现、口径飘移） | 只用于长尾探索，且必须过闸门 |
| **指标即配置 + 编译器（本方案主路径）** | 框架按配置**确定性生成** | **完全一致** | **能** | ✅ 主路径 |
| 手写 Mapper + XML（现状） | 人写 | 一致 | 能 | 成本高，逐步淘汰 |

关键认知：**"我再也不手写 SQL" ≠ "让模型每次现场生成 SQL"**。后者牺牲的是数字可信度——而数字可信是这个项目唯一的交付物。

由此确定分工：

- **模型**：把自然语言映射为**结构化查询意图**（指标编码 + 维度 + 过滤 + 时间范围），全部是枚举值，可校验、可复现。
- **框架**：把查询意图**确定性地编译成 SQL**，并强制口径、安全、权限、限流。
- **开发期**：让 AI 帮我们**生成配置**（而不是生成运行期 SQL），人审核后固化。AI 写配置比写 SQL 好管：字段是枚举、语义可读、可 Schema 校验。

## 二、总体架构

```
用户自然语言
   │  mcp-client（ChatClient，无需改动）
   ▼
MCP over SSE
   ▼
mcp-server 工具（com.github.yingli.mcp.tool.MetricTool）
   ├─ 运行期域工具（仅 2 个）：queryMemberMetric / queryOrderMetric
   ├─ 元工具：listMetrics 目录 · explainMetric 干跑编译
   └─ 开发期脚手架：draftMetric（MetricDrafter 起草 YAML，不连库/不落库/不注册）
   │
   ▼  运行期查询链路（queryMemberMetric/queryOrderMetric → MetricQueryService.query 编排）
   ├─ ① MetricRegistry        启动期由 MetricRegistryConfiguration 从 metrics/*.yaml 加载+校验+注册，运行期只读命中
   ├─ ② QueryPlanValidator    requireDomain 域归属 + 枚举白名单 + 时间边界/跨度/topN 归一 + 口径状态闸门 → QueryPlan
   ├─ ③ SqlCompiler           QueryPlan + MetricDefinition → GeneratedSql{ sql, params[], columns, sqlHash }
   ├─ ④ SqlExecutor           受限只读执行（独立 JdbcTemplate：maxRows / queryTimeout / MAX_EXECUTION_TIME hint）
   └─ ⑤ MetricResult          列/行/合计/scope/caliber/sqlHash；派生指标由 DerivedCalculator 在 Java 端四则计算
                              → 交回模型仅做语言解释
```

> **与初稿流程图的差异（2026-10-30 校准，忠实反映已实现代码）**：
> - 运行链路**不含 `SqlGuard`**：它属 M4“逃生舱/长尾 AST 强校验”的设计预留，当前**未实现**（无 `guard` 包）。主路径 SQL 全部由 `SqlCompiler` 确定性生成，不存在自由 SQL 入口，故无需 AST 闸门。
> - 实际执行由 `SqlExecutor`（`MetricQueryService` 持有的受限 `JdbcTemplate`）完成，`MetricQueryService` 本身是**编排者**而非执行步骤。
> - 顺序以真实调用为准：`MetricRegistry` 命中 → `QueryPlanValidator` → `SqlCompiler` → `SqlExecutor` → 结果装配（含 `DerivedCalculator`）。
> - **热加载未实现**（`MetricRegistryConfiguration` 仅启动期加载；是否热加载见 §十四 待确认项）。

层次职责边界（不可混淆）：

| 层 | 负责 | 明确不负责 |
|---|---|---|
| 域工具 | 参数校验、编排、错误语义 | 业务口径判断、SQL 书写 |
| MetricRegistry | 口径的**唯一事实来源** | 执行 |
| SqlCompiler | 确定性 SQL 生成 | 权限决策、结果解释 |
| MetricQueryService | 受限执行、确定性计算（除法/占比/合计） | 让模型算数 |

## 三、指标配置规范（本方案的核心资产）

### 3.1 文件位置与加载

```
mcp-server/src/main/resources/metrics/
  member.yaml      # 会员域
  order.yaml       # 订单/门店/商品域
  delivery.yaml    # 配送域（待表结构确认）
  finance.yaml     # 财务结算域（待表结构确认）
```

由 `MetricRegistryConfiguration`（Spring `@Configuration` Bean）用 SnakeYAML（已随 spring-boot 传递引入）在**启动期**加载，并执行 `MetricDefinitionValidator` 手写校验（**不引入 JSON Schema 库**，避免新增依赖）。校验不通过**直接启动失败**——把配置错误暴露在部署期而不是查询期。〔2026-10-30 校准：初稿所称 `MetricRegistryLoader` 即本 `MetricRegistryConfiguration`；**热加载当前未实现**〕

### 3.2 字段定义

| 字段 | 必填 | 类型 | 说明 |
|---|---|---|---|
| `metric` | ✅ | code | 全局唯一，UPPER_SNAKE，如 `STATION_SALES` |
| `title` | ✅ | string | 中文指标名，进入结果与 prompt |
| `domain` | ✅ | enum | `member` / `order` / `delivery` / `finance` |
| `status` | ✅ | enum | `CONFIRMED` / `UNCONFIRMED`；**UNCONFIRMED 一律拒绝执行** |
| `caliber` | ✅ | string | 口径正文（必须写清状态位、退单、时间边界、粒度） |
| `table` | ✅ | string | 主表，必须在表白名单内 |
| `grain` | ✅ | enum | `ORDER_HEAD` / `ORDER_DETAIL` / `MEMBER` / `CONFIG`；**编译器据此禁止跨粒度 JOIN**，防订单级金额重复累计 |
| `timeColumn` | ◐ | string | 时间列；缺失表示累计口径（不注入时间条件，如会员总览） |
| `baselineFilters` | ◐ | list | 恒定 WHERE 片段，只允许字面量（如 `expense_state = '10'`） |
| `dimensions[]` | ✅(≥1) | obj | `{code, expr, alias}`；`code` 为模型可请求的枚举 |
| `attributes[]` | ◐ | obj | 非聚合描述列 `{expr, alias, agg}`，生成 `MAX(x) AS y`（对应现在手写的 `MAX(expense_stationName)`） |
| `measures[]` | ✅(≥1) | obj | `{code, expr, agg, alias, unit, zeroFill}`；`agg ∈ SUM/COUNT/MAX/MIN/AVG/COUNT_DISTINCT` |
| `optionalFilters[]` | ◐ | obj | `{code, column}`；请求传值时生成 `column = ?`（**参数化，非拼接**） |
| `sortable[]` | ◐ | list | 允许排序的 measure/derived code 白名单；**列名永不外部直传** |
| `limitDefault` / `limitMax` | ◐ | int | 默认 100 / 上限 500 |
| `defaultWindowMonths` | ◐ | int | 0~24；缺省时间窗口（需 `timeColumn`），0=沿用全局 18 个月〔2026-10-29 实现〕 |
| `maxRangeDays` | ◐ | int | 指标级时间跨度上限；0=沿用全局 550 天。显式越界→拒绝，缺省窗口越界→收敛〔2026-10-30 实现〕 |
| `preAggregateBy` | ◐ | obj | 两级聚合：`{column}`，先按该列聚合，外层再算（复购率这类非可加指标必需）。〔2026-10-30 校准：**未实现**，M4 预留，见 §5.2〕 |
| `derived[]` | ◐ | obj | Java 端派生计算 `{code, formula, scale}`，`formula` **只允许** `A / B`、`A * B`、`A + B`、`A - B` 的四则白名单解析（**不用 SpEL/Aviator，避免表达式注入、避免新依赖**） |
| `withTotalRow` | ◐ | bool | 生成"合计"行（对应现在手写的合计逻辑） |
| `nullDimensionLabel` | ◐ | string | 空维度归并展示名，如 `未知门店`（已确认口径 2026-10-28） |
| `forbiddenColumns[]` | ◐ | list | 该指标下显式禁止出现的列（PII 兜底） |
| `sourceType` | ◐ | enum | `SQL`（默认）/ `RAW_FRAGMENT`（逃生舱）。〔**未实现**，M4 预留〕 |
| `rawFragment` | ◐ | obj | 逃生舱：`{sql, params[]}`，**仍必须过 SqlGuard**（时间/权限/LIMIT 由框架注入）。〔**未实现**：无该字段与 `SqlGuard`，见 §7.2〕 |

### 3.3 强制约定（写进校验器，不可绕过）

1. **VARCHAR 金额/数量聚合必须走 `NUM()`**：配置里写 `expr: NUM(expense_realamt)`，编译器展开为
   `CAST(NULLIF(TRIM(expense_realamt),'') AS DECIMAL(20,2))`。
   若 measure 的 expr 直接引用已登记为 VARCHAR 的列且未包 `NUM()`，**加载期报错**。VARCHAR 列清单来自 `DATABASE_SCHEMA.md` 落成的 `column-types.yaml`。
   ⚠️ 已知必须清洗的字段：`gross_margin` 实测有 322 条非数字（见 DEVELOPMENT_LOG 2026-10-28）。
2. **时间边界统一左闭右开**：`>= ? AND < DATE_ADD(?, INTERVAL 1 DAY)`，编译器生成，配置不参与。
3. **`grain` 冲突即拒绝**：`ORDER_HEAD` 的 measure 与 `ORDER_DETAIL` 的维度不得出现在同一 QueryPlan。
4. **禁止 `SELECT *`**、禁止输出 `forbiddenColumns`。
5. **PII 列黑名单全局生效**：`member_name`、`member_mobile`、`member_address`、`member_birthday`、`member_cardno`、家庭成员相关列，以及订单表 `expense_mobile`、`userCode`/`userName`（营业员）不得作为 dimension/attribute 输出，除非该指标显式声明并已通过数据安全评估。

## 四、Phase 1 三个指标的等价配置（可行性直接证明）

现有 `ExpenseStatMapper.xml` / `MemberStatMapper.xml` 手写 SQL 完全可由下述配置产出，**Java 代码零改动**：

```yaml
# ---- order.yaml ----
- metric: STATION_SALES
  title: 门店销售业绩
  domain: order
  status: CONFIRMED
  caliber: "订单头表 1 单 1 行；expense_state='10' AND del_flag='0'；金额取实付 expense_realamt（VARCHAR，退单为负数行，SUM 自然抵消）；时间左闭右开含结束日"
  table: expense_calendar_info
  grain: ORDER_HEAD
  timeColumn: expense_buyDate
  baselineFilters: ["expense_state = '10'", "del_flag = '0'"]
  dimensions:
    - { code: STATION, expr: expense_stationCode, alias: station_code }
  attributes:
    - { expr: expense_stationName, alias: station_name, agg: MAX }
  measures:
    - { code: SALES,  expr: "NUM(expense_realamt)", agg: SUM,   alias: sales_amount, unit: CNY, zeroFill: true }
    - { code: ORDERS, expr: "*",                    agg: COUNT, alias: order_count,  unit: COUNT }
  optionalFilters:
    - { code: TENANT,  column: expense_tenant }
    - { code: STATION, column: expense_stationCode }
  sortable: [SALES, ORDERS]
  withTotalRow: true
  nullDimensionLabel: 未知门店
  derived:
    - { code: AVG_ORDER_VALUE, formula: "SALES / ORDERS", scale: 2, unit: CNY }

- metric: PRODUCT_RANKING
  title: 商品销售排行
  domain: order
  status: CONFIRMED
  caliber: "基于消费明细表按 SKU=expense_d_gdcode 聚合；expense_d_state='10' AND del_flag='0'；明细表无租户字段，不支持 TENANT 过滤"
  table: expense_calendar_details
  grain: ORDER_DETAIL
  timeColumn: expense_d_buyDate
  baselineFilters: ["expense_d_state = '10'", "del_flag = '0'"]
  dimensions:
    - { code: SKU, expr: expense_d_gdcode, alias: sku }
  attributes:
    - { expr: expense_d_commName,     alias: product_name,  agg: MAX }
    - { expr: expense_d_commCodeName, alias: category_name, agg: MAX }
  measures:
    - { code: SALES, expr: "NUM(expense_d_realamt)", agg: SUM, alias: sales_amount, unit: CNY, zeroFill: true }
    - { code: QTY,   expr: "NUM(expense_d_qty)",     agg: SUM, alias: quantity,     unit: PCS, zeroFill: true, decimalScale: 4 }
  optionalFilters:
    - { code: STATION, column: expense_d_stationCode }
  sortable: [SALES, QTY]
  limitDefault: 10
  limitMax: 100

# ---- member.yaml ----
- metric: MEMBER_OVERVIEW
  title: 会员总览
  domain: member
  status: CONFIRMED
  caliber: "member_info del_flag='0'；累计消费取 member_expense_totalsum(decimal)；人均消费基于有消费会员数；不返回任何个人级字段"
  table: member_info
  grain: MEMBER
  baselineFilters: ["del_flag = '0'"]
  dimensions:
    - { code: TENANT, expr: member_tenant,     alias: tenant }
    - { code: STATION, expr: member_allot_store, alias: station }
  measures:
    - { code: TOTAL_MEMBERS,   expr: "*", agg: COUNT, alias: total_members, unit: COUNT }
    - { code: MALE_COUNT,      expr: "CASE WHEN member_sex = 1 THEN 1 ELSE 0 END", agg: SUM, alias: male_count }
    - { code: FEMALE_COUNT,    expr: "CASE WHEN member_sex = 2 THEN 1 ELSE 0 END", agg: SUM, alias: female_count }
    - { code: UNKNOWN_COUNT,   expr: "CASE WHEN member_sex IS NULL OR member_sex NOT IN (1,2) THEN 1 ELSE 0 END", agg: SUM, alias: unknown_count }
    - { code: EXPENSE_MEMBERS, expr: "CASE WHEN member_expense_totalsum > 0 THEN 1 ELSE 0 END", agg: SUM, alias: expense_member_count }
    - { code: TOTAL_EXPENSE,   expr: "COALESCE(member_expense_totalsum, 0)", agg: SUM, alias: total_expense_amount, unit: CNY, zeroFill: true }
  optionalFilters:
    - { code: TENANT,  column: member_tenant }
    - { code: STATION, column: member_allot_store }
  derived:
    - { code: AVG_EXPENSE_PER_ACTIVE, formula: "TOTAL_EXPENSE / EXPENSE_MEMBERS", scale: 2, unit: CNY }
```

> 注：`MEMBER_OVERVIEW` 现在工具里是"无分组全局汇总"，配置化后 `dimensions` 可选——不传维度即全局聚合，与 Phase 1 行为一致（对应 DEVELOPMENT_LOG 中"默认不拆分"的已锁定决策）。

## 五、SqlCompiler 设计

### 5.1 生成规则（一次写对，之后所有指标共享）

```
1  SELECT 子句   = dimensions.expr + attributes(MAX(x) AS y) + measures(agg(NUM(x)) AS y)
2  FROM         = table；声明 join 时为 `table mainAlias LEFT JOIN joinTable alias ON on`
                 （路线 C 受控等值 JOIN，主表也上别名、列引用强制带限定符；ON 加载期整串复核为单一等值）
3  WHERE        = baselineFilters（逐条括号为 `(f) AND …`；为空时兜底 `1 = 1`）
                 AND timeColumn >= ? AND timeColumn < DATE_ADD(?, INTERVAL 1 DAY)
                 AND <命中的 optionalFilters 转成的 column = ?>            # 全部 ? 占位
                 AND <可选的数据权限注入条件>                              # §7.3，设计预留、当前未接入编译器
4  GROUP BY     = dimensions.expr（请求未传维度时退化为全局聚合，不带 GROUP BY）
5  HAVING       = 仅 preAggregateBy 两级聚合内部使用（未实现，M4 预留）
6  ORDER BY     = sortable 白名单映射出的别名 + 方向（列名永不来自外部）
7  LIMIT        = min(request.topN 或 limitDefault, limitMax)
8  合计行        = withTotalRow 时由 compileTotals 另发一次同 WHERE、无 GROUP BY / ORDER BY / LIMIT 的
                 全量聚合（不做 Java 累加，见 §5.3）
9  派生指标      = 不进 SQL，Java 端按 derived.formula 计算，除零返回 0（沿用现有 divide() 语义）
```

生成结果统一为：

```java
// 实际签名（GeneratedSql.java）；列清单含派生列登记（派生列不在 SQL 中、由服务层补值）
public record GeneratedSql(String sql, List<Object> params,
                           List<ColumnRef> columns, String sqlHash) {
    public record ColumnRef(String code, String alias, String unit, boolean derived) {}
}
```

`sqlHash = SHA-256(sql 折叠空白规范化后) 取十六进制前 16 位`，写入结果与审计日志，保证**任何数字都能反查到是哪条 SQL 算出来的**。

### 5.2 两级聚合（非可加指标：复购率、活跃占比等）

> ⚠️ **2026-10-30 校准：本节为 M4 设计预留，当前未实现**——`MetricDefinition` 无 `preAggregateBy` 字段、编译器无两级聚合路径，`JSqlParserCapabilityTest`（§5.4 / M0）亦未落地（属逃生舱前置）。以下为原设计，实现时参照。

配置声明 `preAggregateBy: { column: expense_mobile }`，编译器生成：

```sql
SELECT COUNT(*)                                            AS expense_member_count,
       SUM(CASE WHEN order_cnt >= 2 THEN 1 ELSE 0 END)      AS repurchase_member_count
FROM (
    SELECT expense_mobile, COUNT(*) AS order_cnt
    FROM expense_calendar_info
    WHERE <baseline + time + dataScope>
    GROUP BY expense_mobile
) t;
```

外层再按请求维度分组时，`preAggregateBy` 的 column 自动并入内层 GROUP BY。**这是本方案唯一有技术难度的部分**，必须在 M1 用一个已确认指标（复购率，待产品答口径）专项验证；Phase 1 三指标都不需要它，所以不阻塞主流程。

### 5.3 合计行的取舍（已按此实现）

初稿曾考虑"默认走 Java 端累加已返回行"，**实现最终未采纳**：若结果被 `LIMIT` 截断，"合计"必须是"全量合计"而非"Top N 合计"。因此规则写死为：**`withTotalRow` 时一律由 `compileTotals` 另发一次同 WHERE、无 GROUP BY / ORDER BY / LIMIT 的全量聚合**，在分组查询之后独立执行（实现见 `MetricQueryService.buildTotalRowIfNeeded`，快照测试见 `SqlCompilerTest`）。

### 5.4 JSqlParser 版本注意（M4 前置，当前未落地）

> ⚠️ **2026-10-30 校准**：本节属 M4 逃生舱/`SqlGuard`（§7.2）的前置设计，**当前未实现**——代码中不存在 `SqlGuard`、`guard` 包与 `JSqlParserCapabilityTest`，主路径也不使用 JSqlParser。以下内容保留备 M4 实施。

本地实际可用版本为 **`com.github.jsqlparser:jsqlparser:5.2`**（随 `mybatis-plus-jsqlparser:3.5.14` 引入）。5.x 相对 4.x 有 API 调整（`Limit`、`PlainSelect` 的构造与 setter 形态），**M0 必须先写一个解析可行性测试**确认所用 API，不要按 4.x 的直觉写代码。

## 六、运行时接口

### 6.1 工具清单（Phase 2 落地：2 个业务工具 + 2 个元工具 + 1 个开发期脚手架 `draftMetric`，见 §8.4）

| 工具 | 职责 | 说明 |
|---|---|---|
| `queryMemberMetric` | 会员域指标查询 | 域内所有指标共用一个工具 |
| `queryOrderMetric` | 订单/门店/商品域指标查询 | 同上 |
| `listMetrics` | 返回该域可用指标（code + title + caliber + 可用维度/度量） | **元数据发现**，让模型先查能力再提问，显著降低乱填参数 |
| `explainMetric` | 干跑：返回生成的 SQL + sqlHash + EXPLAIN 摘要，**不执行** | 开发/联调/排错主力 |

`delivery` / `finance` 域在表结构与口径确认后再加同名工具，不改既有工具。

### 6.2 请求结构（模型输出被约束成枚举组合）

```java
// entity/request/MetricQueryRequest.java
@ToolParam(description = "指标编码，必填；取值见 listMetrics 返回，非法编码直接拒绝")
private String metric;

@ToolParam(description = "分组维度编码列表，可选；如 [STATION]；不传=全局汇总")
private List<String> dimensions;

@ToolParam(description = "过滤条件列表，可选；每项 {code,value}，code 取自指标声明的可选过滤")
private List<FilterItem> filters;

@ToolParam(description = "开始日期 yyyy-MM-dd，可选；默认本月1号（测试库需显式传历史区间）", required = false)
private String startDate;

@ToolParam(description = "结束日期 yyyy-MM-dd，可选；默认今天，该日全天计入", required = false)
private String endDate;

@ToolParam(description = "返回条数，可选；默认与上限由指标声明", required = false)
private Integer topN;

@ToolParam(description = "排序度量编码，可选；必须是该指标 sortable 之一", required = false)
private String sortBy;

@ToolParam(description = "排序方向 asc|desc，可选；默认 desc", required = false)
private String sortDir;
```

`MetricQueryRequest` 的字段就是"指标 × 维度 × 过滤 × 时间 × 排序 × 条数"的笛卡尔约束，**模型无法表达越权或自由 SQL**。

### 6.3 结果结构（替代每个指标一个 Result DTO）

```java
// entity/response/MetricResult.java
private String metric;                 // 指标编码
private String title;                  // 中文指标名
private List<ColumnMeta> columns;      // {code, alias, unit, dataType}
private List<List<Object>> rows;       // 按 columns 顺序
private List<Object> totalRow;         // withTotalRow 时非空
private String scope;                  // "时间 2026-06-20 ~ 2026-06-27（含末日）；全部范围"
private String caliber;                // 口径正文，强制回显
private String sqlHash;                // 反查指纹
private String errorCode;              // INVALID_PARAM | METRICS_NOT_CONFIRMED | SQL_REJECTED | NO_DATA | QUERY_FAILED
private String message;                // 面向模型的可读说明；空结果与失败必须区分
```

Phase 1 的 6 个 `*Request`/`*Result` 类在迁移完成后删除（`ProductTool` 等 demo 不受影响）。

## 七、安全闸门与执行约束

### 7.1 编译期闸门（主路径，覆盖 100%）

编译器只从配置取表/列/表达式，参数一律 `?` 占位，因此主路径**不存在注入面**。需要的是配置校验与请求校验（§3.3、§6.2）。

### 7.2 生成期自检（逃生舱 / 长尾生成 SQL，必须过）

> ⚠️ **2026-10-30 校准：本节整体未实现**——`SqlGuard` 与 AST 校验均不落地（无 `guard` 包），属 M4 设计预留。主路径 SQL 全部由 `SqlCompiler` 确定性生成，不存在自由 SQL 入口，故当前运行链路无 AST 闸门环节。以下 10 条保留备逃生舱实施。

用 JSqlParser 做 AST 校验（**不用正则**），顺序执行、任一失败即拒绝并回喂原因：

1. 必须能解析为单条语句（多语句 `;` 直接拒）；
2. 顶层必须是 `SELECT`（拒 INSERT/UPDATE/DELETE/DROP/ALTER/TRUNCATE/CREATE/REPLACE/CALL/SET/GRANT）；
3. 禁止 `INTO OUTFILE`、`LOAD_FILE`、`information_schema`、`mysql.*` 等系统对象；
4. 表 ∈ 注册表白名单 + 显式批准的视图；
5. 列 ∉ PII 黑名单、∈ 指标声明列集合；
6. 必须存在 `timeColumn` 区间条件且跨度 ≤ 上限（全局 550 天，指标可用 `maxRangeDays` 收紧；本文初稿写的 366 已作废）；
7. VARCHAR 度量必须已包 `NUM()`；
8. 强制 `LIMIT`；
9. **数据权限注入**（§7.3）；
10. `EXPLAIN` 预演：`type=ALL` 且预估扫描行超阈值 → 拒绝并提示改用声明索引列。

配套两个复用点：长尾探索生成的 SQL 若被反复问到且对账通过 → 固化成配置（飞轮）。

### 7.3 数据权限

**服务端改写注入，不信任模型给的值**：`SubjectContext{ stationCodes }` 由会话/登录态确定，编译时无条件注入门店范围条件并**剥离模型自行提交的 STATION 条件**。用户问"隔壁店卖了多少钱"只能靠这里挡住。〔2026-10-30 调整：初稿设计的 `tenant` 注入维度随租户能力取消而作废，权限粒度改为**门店**；仍未接入真实鉴权〕

Phase 2 若仍未接入真实鉴权（延续 Phase 1 决策 5），则必须：只连测试库 + 结果标注"未启用数据权限，仅限测试环境"。

### 7.4 执行层三重兜底

```java
JdbcTemplate template = new JdbcTemplate(dataSource);
template.setMaxRows(MAX_ROWS);          // 结果集硬上限
template.setQueryTimeout(10);           // 服务端超时
// SQL 前缀加 MySQL 8 hint：/*+ MAX_EXECUTION_TIME(8000) */
// 连接层：独立 GRANT SELECT 只读账号；Statement.setReadOnly(true)
```

审计日志记录：`question摘要 / metric / sqlHash / 参数脱敏后 / 耗时 / 行数 / errorCode`。**不记录 PII、不记录完整结果集**。

## 八、使用方法

### 8.1 新增一个指标（目标：10 分钟 + 对账）

1. **产品给口径**（唯一无法自动化的环节）。口径写进 `METRICS_MAPPING.md`，状态必须为 ✅；未确认就先用 `status: UNCONFIRMED` 落配置，工具会明确拒绝而不是给可疑数字。
2. **让 AI 生成配置**（不要让它生成 SQL）：

   ```text
   角色：你是 CRM 指标建模助手。依据下方"口径正文 + 表结构片段 + 配置字段规范"，
   只输出符合 metrics/*.yaml schema 的一条指标配置，禁止输出 SQL、禁止输出解释文字。

   必须遵守：
   - 金额/数量若为 VARCHAR，measure.expr 必须写成 NUM(列名)；
   - grain 必须显式声明；禁止 ORDER_HEAD 指标使用明细表维度；
   - 时间边界一律左闭右开，不要在 caliber 里重复描述框架行为；
   - 有效订单基线口径：expense_state='10' AND del_flag='0'，退单为负数行；
   - dimension/attribute 不得引用 PII 列（member_mobile/member_name/expense_mobile 等）；
   - member_expense_totalsum_year 语义为"近一年半"，不得当自然年使用；
   - 口径正文若与 DDL 注释冲突，以我提供的口径为准，并在 caliber 中写明。

   口径正文：<产品确认文本>
   表结构：<DATABASE_SCHEMA.md 相关表段落>
   配置规范：<本文件 §3.2 表格>
   参考样例：<order.yaml 中 STATION_SALES 一条>
   ```

> 上述手工 prompt 流程已由 §8.4 的 `draftMetric` 工具确定性自动化（结构化入参 → 自动 `NUM()` / 复用加载期校验 / 干跑编译 → 产出可直接粘贴的 YAML），推荐优先用工具，prompt 方式作为无工具时的兜底。

3. **干跑自检**：`explainMetric(metric=..., dimensions=..., filters=...)` → 看生成的 SQL、`sqlHash`、EXPLAIN 是否走 `index_buyDate`/`index_stationCode`。
4. **对账**：写/跑一个集成测试，与产品认可的基准数字或独立基准 SQL 逐值比对（见 §8.2）。
5. **提交 YAML**：Java 代码零改动，重启（或热加载）即生效。

### 8.2 对账测试怎么写（沿用 Phase 1 已验证的做法）

```java
@SpringBootTest
class StationSalesReconciliationTest {
    @Autowired MetricQueryService metricQueryService;

    // DEVELOPMENT_LOG 2026-10-28 真库实测基线（6/20-6/27 分层样本）
    static final LocalDate START = LocalDate.of(2026, 6, 20);
    static final LocalDate END   = LocalDate.of(2026, 6, 27);

    @Test
    void 编译结果与原手写SQL逐值一致() {
        MetricResult r = metricQueryService.query(plan("STATION_SALES", START, END));
        assertThat(r.rowCount()).isEqualTo(228);              // 含"未知门店"行
        assertThat(r.totalRow().salesAmount())
                .isEqualByComparingTo("2445299.92");
        assertThat(r.rows().get(0).stationName()).isEqualTo("标杆旗舰店");
        assertThat(r.rows().get(0).salesAmount()).isEqualByComparingTo("117906.50");
    }
}
```

`MEMBER_OVERVIEW` 基线：total=5000 / male=4880 / female=120 / unknown=0、有消费会员 3993、累计消费 2,660,605.52。
`PRODUCT_RANKING` 基线：Top1「头部白酒A」销售额 890,272.00 / 销量 532。

> ⚠️ 测试库 `expense_calendar_info` 数据分布有限（曾出现"本月"默认区间返回空），**所有测试必须显式传历史区间**。

### 8.3 常见错误与排查

| 现象 | 原因 | 处置 |
|---|---|---|
| 启动失败：`metric X 的 measure Y 引用 VARCHAR 列未包 NUM()` | §3.3 规则 1 | 改配置为 `NUM(col)` |
| 返回 `METRICS_NOT_CONFIRMED` | `status=UNCONFIRMED` | 找产品定口径，不要改成 CONFIRMED 绕过 |
| 返回 `SQL_REJECTED` + 原因 | 闸门拒绝（表/列/时间/LIMIT/全表扫描） | 按原因调整请求或配置 |
| 数字比 CRM 报表大 | 订单头 × 明细重复累计 | 检查 `grain` 与 dimension 是否跨粒度 |
| 数字比预期小 | 加了 `state='20'` 之类状态过滤，或漏了退单负数语义 | 校对 `baselineFilters` 与口径 |
| 年度指标偏高 | 误用 `member_expense_totalsum_year`（实为近一年半） | 改用按 `expense_buyDate` 区间重算 |
| 查询慢/超时 | 未走索引列做时间/门店过滤 | 用 `explainMetric` 确认命中 `index_buyDate` |
| 空结果 | 区间无数据 or 默认"本月"在测试库无数据 | 显式传历史区间 |

### 8.4 `draftMetric` 指标脚手架（把 §8.1 的"生成配置 + 干跑自检"自动化）

为降低"指标即配置"的编写门槛新增的**开发期** MCP 工具（与 `listMetrics`/`explainMetric` 同族，实现在 `metrics/MetricDrafter`）。它把结构化的"指标意图"确定性地组装成 `MetricDefinition`，**即时跑一遍加载期同一套 `MetricDefinitionValidator` + `SqlCompiler` 干跑编译**，返回可直接粘贴进 `metrics/*.yaml` 的配置片段与样例 SQL——替代"写完要到重启才知道哪里错"。

**定位与红线（与运行时查询链路严格分离）**：全程**不连数据库**（判定依据只有 `ColumnTypeRegistry`）、**不写文件、不注册指标、不生成运行期 SQL**；`caliber` 必填、由人工提供，脚手架**不臆造业务口径**。

**自然语言起草示例**（老板/产品随口一说 → 模型翻译成 `draftMetric` 的结构化入参）：

> “帮我起个指标：按门店统计上个月的实付销售额和订单笔数，退单要抵消掉。”

模型据此组装：口径正文落“实付 `expense_realamt`、`expense_state='10' AND del_flag='0'` 退单负行自然抵消”，`table=expense_calendar_info`、维度 `STATION`、度量 `SALES=SUM(NUM(expense_realamt))` + `ORDERS=COUNT(*)`；脚手架再自动补 `grain=ORDER_HEAD`、包 `NUM()`、标 `status=UNCONFIRMED` 并返回可粘贴的 YAML。

> ⚠️ 自然语言里**没说的口径**（如“上个月”的具体边界、是否含未确认状态），脚手架不会替用户猜——会留成 `UNCONFIRMED` + `validationErrors`/`nextSteps`，仍需人工或业务确认后补 `caliber` 再提交。

**入参要点**（`MetricDraftRequest`，模型用结构化参数调用）：

| 字段 | 必填 | 脚手架的确定性动作 |
|---|---|---|
| `metric`/`title`/`domain`/`table` | 是 | metric 转 UPPER_SNAKE、domain 转小写；table 越出白名单进 `validationErrors` |
| `caliber` | 是 | 原样写入；缺失即报错（口径是唯一事实来源） |
| `measures[{code,agg,column/expr,alias,unit,decimalScale,zeroFill}]` | 是（≥1） | SUM/AVG 且列命中登记表 VARCHAR 数值 → **自动包 `NUM()`**；给了 `expr`（如 `DISTINCT e.expense_mobile`）则直通 |
| `dimensions`/`attributes`/`optionalFilters`/`derived`/`baselineFilters` | 否 | 组装为对应结构；PII 列、非字面量 baseline 由校验器拦截 |
| `grain` | 否 | **缺省按主表硬映射推断**（expense_calendar_info→ORDER_HEAD 等） |
| `status` | 否 | **缺省一律 `UNCONFIRMED`**，仅显式传 CONFIRMED 才落地为可执行 |
| `join{joinTable,joinAlias,mainAlias,onLeftCol,onRightCol}` | 否 | 按 `mainAlias.onLeftCol = joinAlias.onRightCol` 组装 ON，并在 YAML 给 `"on"` 键加引号（规避 SnakeYAML 布尔键陷阱） |
| `sortable`/`limitDefault`/`limitMax`/`withTotalRow`/`defaultSortBy` 等 | 否 | 框架约定按模板补齐（sortable 缺省=全部度量；limit 缺省 100/500） |

> ⚠️ **join 场景只有 ON 由脚手架组装，其它列引用（如 `m.member_sex`）必须自行带限定符**——不连库无法可靠判断列归属，硬猜会产出"看起来对的错 SQL"。越界写法由加载期校验拦截。

**返回字段**：`ok`、`yaml`（粘贴片段）、`validationErrors[]`（原样中文，供改入参重试）、`autoWrappedColumns[]`、`blockedPiiColumns[]`、`compiledSqlSample`+`sqlHash`（+`compiledTotalsSqlSample`）、`nextSteps[]`。

#### 示例一：单表指标

调用 `draftMetric` 传入：

```json
{
  "metric": "STATION_CONSUME_COUNT",
  "title": "门店消费笔数",
  "domain": "order",
  "caliber": "订单头 1 单 1 行；expense_state='10' AND del_flag='0'；按门店统计有效订单笔数；时间左闭右开含结束日",
  "table": "expense_calendar_info",
  "timeColumn": "expense_buyDate",
  "baselineFilters": ["expense_state = '10'", "del_flag = '0'"],
  "dimensions": [{"code": "STATION", "column": "expense_stationCode", "alias": "station_code"}],
  "attributes": [{"column": "expense_stationName", "alias": "station_name"}],
  "measures": [
    {"code": "ORDERS", "agg": "COUNT", "column": "*", "alias": "order_count", "unit": "COUNT"},
    {"code": "SALES", "agg": "SUM", "column": "expense_realamt", "alias": "sales_amount", "unit": "CNY", "zeroFill": true, "decimalScale": 2}
  ],
  "sortable": ["ORDERS", "SALES"],
  "defaultSortBy": "ORDERS",
  "limitDefault": 500, "limitMax": 2000, "withTotalRow": true,
  "nullDimensionLabel": "未知门店"
}
```

返回的 `yaml`（`grain` 由主表推断、`status` 缺省 UNCONFIRMED、`expense_realamt` 命中登记表被**自动包 `NUM()`**）：

```yaml
- metric: STATION_CONSUME_COUNT
  title: "门店消费笔数"
  domain: order
  status: UNCONFIRMED
  caliber: "订单头 1 单 1 行；expense_state='10' AND del_flag='0'；按门店统计有效订单笔数；时间左闭右开含结束日"
  table: expense_calendar_info
  grain: ORDER_HEAD
  timeColumn: expense_buyDate
  baselineFilters:
    - "expense_state = '10'"
    - "del_flag = '0'"
  dimensions:
    - { code: STATION, alias: station_code, expr: expense_stationCode }
  attributes:
    - { expr: expense_stationName, alias: station_name, agg: MAX }
  measures:
    - { code: ORDERS, expr: "*", agg: COUNT, alias: order_count, unit: COUNT }
    - { code: SALES, expr: "NUM(expense_realamt)", agg: SUM, alias: sales_amount, unit: CNY, zeroFill: true, decimalScale: 2 }
  optionalFilters: []
  derived: []
  sortable: [ORDERS, SALES]
  defaultSortBy: ORDERS
  defaultSortDir: desc
  limitDefault: 500
  limitMax: 2000
  withTotalRow: true
  nullDimensionLabel: "未知门店"
```

同时返回 `ok=true`、`autoWrappedColumns=["expense_realamt"]`，以及 `compiledSqlSample`（节选）：

```sql
SELECT expense_stationCode AS station_code, MAX(expense_stationName) AS station_name, COUNT(*) AS order_count,
       COALESCE(SUM(CAST(NULLIF(TRIM(expense_realamt), '') AS DECIMAL(20, 2))), 0) AS sales_amount
FROM expense_calendar_info
WHERE (expense_state = '10') AND (del_flag = '0')
  AND expense_buyDate >= ? AND expense_buyDate < DATE_ADD(?, INTERVAL 1 DAY)
GROUP BY expense_stationCode ORDER BY order_count desc LIMIT 500
```

#### 示例二：join 指标（路线 C，节选）

```json
{
  "metric": "GENDER_CONSUMPTION_COMPARE_DRAFT",
  "title": "男女消费对比（草稿）",
  "domain": "order",
  "caliber": "订单头按关联会员性别分组对比，主口径=实付销售额；口径待业务复核",
  "table": "expense_calendar_info",
  "timeColumn": "e.expense_buyDate",
  "join": {"joinTable": "member_info", "joinAlias": "m", "mainAlias": "e",
           "onLeftCol": "expense_mobile", "onRightCol": "member_mobile"},
  "baselineFilters": ["e.expense_state = '10'", "e.del_flag = '0'"],
  "dimensions": [{"code": "GENDER", "alias": "gender",
                 "expr": "CASE WHEN m.member_sex = 1 THEN '男' WHEN m.member_sex = 2 THEN '女' ELSE '会员性别缺失' END"}],
  "measures": [
    {"code": "SALES", "agg": "SUM", "column": "e.expense_realamt", "alias": "sales_amount", "unit": "CNY"},
    {"code": "BUYERS", "agg": "COUNT", "expr": "DISTINCT e.expense_mobile", "alias": "buyer_count", "unit": "COUNT"}
  ]
}
```

产出关键片段（ON 自动组装 + `"on"` 键加引号；限定金额列 `e.expense_realamt` 命中登记表→`NUM(e.expense_realamt)`；维度 `expr` 与 `DISTINCT` 度量原样直通）：

```yaml
  join:
    table: member_info
    alias: m
    mainAlias: e
    "on": "e.expense_mobile = m.member_mobile"
  dimensions:
    - { code: GENDER, alias: gender, expr: "CASE WHEN m.member_sex = 1 THEN '男' WHEN m.member_sex = 2 THEN '女' ELSE '会员性别缺失' END" }
  measures:
    - { code: SALES, expr: "NUM(e.expense_realamt)", agg: SUM, alias: sales_amount, unit: CNY, decimalScale: 2 }
    - { code: BUYERS, expr: "DISTINCT e.expense_mobile", agg: COUNT, alias: buyer_count, unit: COUNT }
```

#### 在 §8.1 流程中怎么用

1. 产品给口径 → 把口径正文连同结构化字段喂给 `draftMetric`；
2. 看返回：`validationErrors` 非空就逐条改入参重试，直到 `ok=true`；
3. 复核 `yaml` 与 `compiledSqlSample`（必要时再 `explainMetric(includeExplain=true)` 看执行计划），把片段粘进 `metrics/<domain>.yaml`；
4. 写对账测试（§8.2）用标准答案验数；
5. 口径经业务确认后，把缺省的 `status: UNCONFIRMED` 手工改为 `CONFIRMED` 再提交——**脚手架不会替你确认口径**。

## 九、涉及文件清单

> 〔2026-10-30 校准〕按真实代码重写：实际包为 `com.github.yingli.metrics`（非初稿的 `mcp/metrics`）；维度/度量等子模型与 Grain/Status 枚举内嵌于 `MetricDefinition` record，未拆独立类；`SqlGuard` 与 `JSqlParserCapabilityTest` 未落地（M4 预留），已从清单移出。下文为**当前实际存在**的文件。

**新增（已落地）**

```
metrics/model/MetricDefinition.java            // record（内嵌 Join/Grain/Status/Dimension/Attribute/Measure/Filter/Derived）
metrics/model/QueryPlan.java                   // record
metrics/MetricRegistry.java                    // 内存索引 + 查询
metrics/MetricRegistryConfiguration.java       // Spring @Bean 装配 + SnakeYAML 启动期加载（即初稿所称 Loader）
metrics/MetricDefinitionParser.java            // YAML → MetricDefinition 手写显式映射
metrics/MetricDefinitionValidator.java         // §3.3 规则（启动期失败）
metrics/ColumnTypeRegistry.java                // VARCHAR / PII 登记表（column-types.yaml）
metrics/MetricsLimits.java                     // 全局限额与白名单常量
metrics/QueryPlanValidator.java                // 请求归一 + 域归属 + 口径闸门 → QueryPlan
metrics/MetricException.java                   // 错误语义（INVALID_PARAM / NOT_CONFIRMED / QUERY_FAILED…）
metrics/SqlCompiler.java                       // 核心生成器（NUM 展开 / 受控 LEFT JOIN / compileTotals / sqlHash）
metrics/GeneratedSql.java                      // record { sql, params, columns, sqlHash }
metrics/DerivedCalculator.java                 // 四则白名单派生计算
metrics/SqlExecutor.java                       // 受限只读执行接口 + 内部类 JdbcSqlExecutor
metrics/MetricQueryService.java                // 编排：命中 → 归一 → 编译 → 执行 → 装配（含合计与派生）
metrics/MetricDrafter.java                     // draftMetric 脚手架：组装+自动NUM+join ON+校验+干跑+YAML 产出
entity/request/MetricQueryRequest.java
entity/request/FilterItem.java
entity/request/MetricDraftRequest.java         // draftMetric 脚手架入参
entity/response/MetricResult.java
entity/response/ColumnMeta.java
resources/metrics/member.yaml
resources/metrics/order.yaml
resources/metrics/column-types.yaml
src/test/java/com/itzixi/metrics/SqlCompilerTest.java                     // golden SQL 快照
src/test/java/com/itzixi/metrics/MetricDefinitionValidatorTest.java
src/test/java/com/itzixi/metrics/MetricDrafterTest.java                   // 脚手架闭环（产出 YAML 再解析过校验器）
src/test/java/com/itzixi/metrics/MetricsFixtures.java                     // 不连库测试夹具
src/test/java/com/itzixi/metrics/MetricsAsCodeMigrationReconciliationTest.java  // vs 手写SQL 逐值
src/test/java/com/itzixi/metrics/Phase2MetricsReconciliationTest.java     // 真库对账
src/test/java/com/itzixi/metrics/QueryPlanDefaultWindowTest.java          // 缺省窗口/指标级上限
src/test/java/com/itzixi/metrics/BaselineFixtures.java                    // 基线外置（crm_dev-baseline.yaml）
src/test/java/com/itzixi/mcp/tool/MetricToolInjectionTest.java            // 越权/注入/非法枚举
```

**未落地（初稿列出、当前不存在，备 M4 实施）**

```
guard/SqlGuard.java                            // 逃生舱/长尾 AST 校验（§7.2，M4 预留）
JSqlParserCapabilityTest.java                  // M0 API 可行性（§5.4，M4 前置）
```

**修改**

```
mcp/tool/CrmAnalyticsTool.java     // 三个方法改为委托 MetricQueryService（过渡期保留旧签名，实际已改）
mcp/tool/MetricTool.java           // 新增 @Tool draftMetric，构造注入 MetricDrafter
mcp/config/ToolConfiguration.java  // MetricTool 入 toolObjects（初稿“追加”已完成，后续加 @Tool 无需再改）
```

**迁移完成后删除（2026-10-30 核实：过渡期内仍全部保留，未删除）**

```
mapper/MemberStatMapper.java  +  resources/mapper/MemberStatMapper.xml
mapper/ExpenseStatMapper.java +  resources/mapper/ExpenseStatMapper.xml
entity/request/{MemberOverview,StationPerformance,ProductRanking}Request.java
entity/response/{MemberOverview,StationPerformance,ProductRanking}Result.java
```

## 十、依赖与环境核实（已实测，非推测）

| 能力 | 依赖 | 状态 |
|---|---|---|
| AST 校验 SQL | `com.github.jsqlparser:jsqlparser:5.2` | ✅ 已在本地仓库，随 `mybatis-plus-jsqlparser:3.5.14` 引入 |
| 受限执行生成 SQL | `JdbcTemplate`（`spring-boot-starter-jdbc`） | ✅ 已随 `mybatis-plus-spring-boot3-starter:3.5.14` 传递引入 |
| 解析指标配置 | `org.yaml:snakeyaml:2.4` | ✅ 已确认：`spring-boot-starter:3.5.14` 的 POM 直接声明 `org.yaml:snakeyaml`，而 `spring-boot-starter-mail` / `spring-ai-starter-mcp-server-webflux` 均传递引入 `spring-boot-starter` |

**结论：本方案不新增任何 Maven 依赖**，与 Phase 1 原则一致。
上述三项均由读取本地 `.m2` 仓库的 POM 声明核实（非推测）。本机 `mvn` 不在 PATH，进入 M0 时仍建议在 IDEA 内置 Maven 跑一次 `dependency:tree` 作为最终确认，并把 `jsqlparser` / `snakeyaml` / `spring-jdbc` 的实际生效版本记入 `DEVELOPMENT_LOG.md`。

## 十一、实施里程碑

| 阶段 | 内容 | 产出/验收 | 预估 |
|---|---|---|---|
| **M0 可行性钉子** | JSqlParser 5.2 API 能力测试；依赖传递性复核；`column-types.yaml` 首版 | `JSqlParserCapabilityTest` 通过 | 0.5 天 |
| **M1 编译器最小版** | 模型 + Loader + Validator + `SqlCompiler`（不含 preAggregate） | `SqlCompilerTest` 通过 | 1.5 天 |
| **M2 迁移对账** | Phase 1 三指标配置化，与现有 XML **逐值比对** → 删 Mapper/DTO | `MetricsAsCodeMigrationReconciliationTest` 通过，现有 11 个测试全绿 | 1 天 |
| **M3 运行时接口** | `MetricQueryService` + 受限执行 + 通用 `MetricResult` + 2 域工具 + `listMetrics`/`explainMetric` | 端到端 `/chat/doChat` 触发新工具 | 1 天 |
| **M4 长尾能力** | `SqlGuard`（AST 10 条）+ 逃生舱 + 两级聚合 + Golden Set 回归 | 注入/越权用例全部被挡 | 1.5~2 天 |

> 〔2026-10-30 校准〕实际进度：M1–M3 已完成（并在此基础上新增 §8.4 `draftMetric` 脚手架与路线 C 受控 JOIN）；**M0 的 JSqlParser 可行性测试与 M4 整段（SqlGuard/逃生舱/两级聚合）未实施**，且初稿的“M3 含 Golden Set”实际归入了 M4。后续是否启动 M4 取决于长尾需求，见 §十四。

M1+M2（约 3 天）即可兑现"新指标不写 SQL、不写 Mapper"，M3/M4 可并行推进。

## 十二、测试与验收

**必须覆盖**（对齐 `crm-data-agent.md` 第八节 12 项，另加本方案特有项）：

- 编译正确性：每个配置指标的生成 SQL 快照（golden file）比对，改编译器必改快照 → 强制 review；
- 逐值对账：Phase 1 三指标 vs 现网 XML 结果完全一致（同参数同库）；
- 粒度防重：故意构造 `grain=ORDER_HEAD` + 明细维度 → 必须拒绝；
- VARCHAR 脏值：`''`、空白、`'abc'`、`'1,234.00'`、超长小数 → `NUM()` 行为可预期且不抛给模型；
- 参数非法：未知 metric/dimension/filter/sortBy 编码（含已取消的 `TENANT`）、区间倒置、跨度超上限（全局 550 / 指标级 92）、topN 越界；
- 空结果 vs 失败：`errorCode` 语义不同，不得混用；
- 注入与越权（闸门）：多语句 `;`、注释绕过、`UNION SELECT` 探测 `information_schema`、请求他店数据、要求输出 `member_mobile`；
- 性能：`explainMetric` 断言命中索引；全表扫描被拒。

**验收标准**：Phase 1 三指标数值与既有实测基线完全一致；新增指标只需 YAML；无新增 Maven 依赖；无 PII 输出；`mvn -o -pl mcp-server test` 全绿；demo 原有功能（Date/Email/Product 工具）不受影响。

## 十三、表达力边界（诚实说明）

| 场景 | 纯配置可否 | 处置 |
|---|---|---|
| 单表聚合 + 分组 + 排序 + TopN | ✅ | 主路径 |
| 条件计数（CASE WHEN 求和） | ✅ | measures 里写 CASE 表达式 |
| 派生比率（客单价、占比） | ✅ | `derived` 四则白名单，Java 算 |
| **非可加：复购率、活跃占比** | ✅（需 M4） | `preAggregateBy` 两级聚合 |
| **序列/时序模式：首购后 30 天复购、连续 3 月下滑、复购周期中位数** | ❌ | 逃生舱 `rawFragment`（AI 生成一次 + 人审 + 过闸门） |
| **跨域 JOIN：配送 × 结算 × 订单** | ❌（待 relationship 设计） | Phase 3；或先在 CRM 侧建只读视图 |
| 品牌/商品级毛利/进销存 | ❌ | 已确认无数据，移出范围（G1/G2/G3） |

预期健康比例：**80~90% 指标纯配置，10~20% 走逃生舱**；逃生舱 SQL 稳定后再补进编译器能力，逐步缩小例外。

## 十四、待确认问题（阻塞项）

1. **口径**：Phase 2 指标（新增/活跃/流失/沉睡、复购族、层级/年龄/地域分布、会员销售额占比、单客年均消费、门店毛利、品类排行）仍需产品回答 `METRICS_MAPPING.md` 第四节 15 个问题。**这是唯一的硬阻塞**——配置化省的是写 SQL，省不掉定义指标。
2. **只读账号**：能否申请到 `GRANT SELECT` 独立账号？能否用视图物理隔离 PII 列？
3. **性能上限**：可接受的单查询 P95 耗时？生产库数据量级（测试样本仅 5857/8892/5000 行，生产消费表 AUTO_INCREMENT 已达 580 万级，**索引与时间边界策略必须在生产量级下重估**）。
4. **是否允许建加速层**：若长尾多、查询慢需宽表/汇总表 → 规则第四节禁止把 CRM 数据复制进本项目新表，除非明确授权 + 安全评估。建议由 CRM 团队在其侧建只读视图，或另立独立分析库。需你决策。
5. **配送 / 财务结算表结构与口径**：完全缺失，两域配置无从生成。
6. **热加载**：是否需要"改 YAML 不重启生效"？（建议 Phase 2 先不做，避免配置漂移不可追溯。）

## 十五、风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 编译器有 bug | **影响所有指标**（单点故障面变大） | SQL 快照测试 + 迁移期与手写 SQL 双跑对账 + `sqlHash` 可反查 |
| 口径未确认即配置 | 输出权威但错误的数字，比无数字更危险 | `status` 闸门 + 结果强制回显 `caliber` |
| 逃生舱被滥用为"万能 SQL 口子" | 退化成不可信系统 | rawFragment 必须过 `SqlGuard` 且需 code review 才可合入 |
| 模型乱填枚举组合 | 查询失败/歧义 | `listMetrics` 元发现 + 非法值明确 `INVALID_PARAM` + 失败原因回喂自修（≤2 轮） |
| VARCHAR 脏值静默转换 | 数字偏差不可见 | `NUM()` 统一 + `column-types.yaml` 强制 + 上线前脏值计数 SQL 体检 |
| 数据权限仍为空实现 | 生产越权泄露 | M3 明确标注"仅限测试环境"；生产接入前必须补 `SubjectContext` |
| 测试库与生产量级差异 | 慢查询/超时在生产暴露 | 时间跨度上限 + 索引约束 + `MAX_EXECUTION_TIME` + 生产灰度只放开内部试用 |

---

*本文件为 Phase 2 技术方案评审稿。建议起手式：M0 + M1 + M2（约 3 天），先用 Phase 1 三个已确认指标把编译器跑对账，再谈新指标。方案不改动 CRM 数据库对象，不新增 Maven 依赖，全程只读。*

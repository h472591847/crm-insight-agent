# Case Study：CRM 企业智能体问数 Agent

## 业务问题

> "老板下午开会，要我给三个门店上周的 GMV 和环比。我打开 BI 系统，选时间、选门店、跑报表、导出 Excel、合并、算百分比…… **2 天**。如果是老板临时加个维度（比如"只看金卡会员"），又要重新跑一遍。"

**核心矛盾**：BI 报表是固化视图，老板的问题是无限变体。每加一个维度组合都要改 SQL、改报表模板、改权限。

**传统方案**：雇 1-2 个 BI 开发，维护 30-50 张固定报表，覆盖 80% 常规问题。剩下 20% 临时问题排队等开发，平均响应 **2-3 天**。

**这个项目的目标**：自然语言提问 → 秒级返回 SQL 聚合结果，**取数从 2 天到 30 秒**。

---

## 架构方案

### 决策：不是 Text2SQL，是"指标即配置 + SQL 编译器"

纯 Text2SQL 的问题：

| 问题 | 后果 |
|---|---|
| 模型每次现场生成 SQL | 同一问题两次可能不同 SQL、不同数字 |
| 注入风险 | `SELECT * FROM members; DROP TABLE orders` |
| 口径漂移 | 模型可能漏 `del_flag='0'`，数字飘了没人知道 |
| JOIN 翻倍 | 订单头 × 明细 = 金额被放大 |

**我们的路线**：

```
自然语言
  │  模型只做意图理解
  ▼
结构化查询意图（metric + dimension + filter + timeRange）
  │  全部是枚举值，可校验、可复现
  ▼
SqlCompiler 确定性编译 SQL
  │  框架只从 YAML 配置取表/列/表达式
  │  参数一律 ? 占位，无注入面
  ▼
JdbcTemplate 受限执行
  │  maxRows / queryTimeout / MAX_EXECUTION_TIME hint
  ▼
MetricResult（含 sqlHash + caliber 回显）
```

### 为什么选 MCP (Model Context Protocol)

不是技术炫技，是**企业 Agent 工具层的标准接口**：

- 不绑定模型：换豆包 / DeepSeek / Claude，协议层透明
- 不绑定宿主：可接入 Claude Desktop / Cline / Dify / 自研前端
- 工具即能力：`queryMemberMetric` / `queryOrderMetric` / `listMetrics` / `explainMetric` / `draftMetric` — 5 个 Tool 覆盖查询 → 发现 → 起草全链路
- 流式输出：SSE 协议，秒级返回首 token，不挂前端

### 指标配置示例

新增"门店客单价"指标，**只写 YAML，Java 零改动**：

```yaml
- metric: STATION_AVG_ORDER_VALUE
  title: 门店客单价
  grain: ORDER_HEAD
  status: CONFIRMED
  table: expense_calendar_info
  timeColumn: expense_buyDate
  baselineFilters: ["expense_state = '10'", "del_flag = '0'"]
  dimensions: [{ code: STATION, expr: expense_stationCode }]
  measures:
    - { code: SALES,  expr: "NUM(expense_realamt)", agg: SUM,   unit: CNY }
    - { code: ORDERS, expr: "*",                    agg: COUNT }
  derived:
    - { code: AVG_ORDER_VALUE, formula: "SALES / ORDERS", scale: 2, unit: CNY }
```

---

## 安全设计（三层纵深）

### 第一层：编译期闸门

| 机制 | 防什么 | 配置位置 |
|---|---|---|
| grain 约束 | ORDER_HEAD 指标不能用明细表维度 → JOIN 翻倍 | `MetricDefinitionValidator` 启动期硬校验 |
| VARCHAR 必须 `NUM()` | `expense_realamt` 是 VARCHAR，直接 SUM 会错 | `column-types.yaml` 声明 + 编译器自动包装 |
| 时间边界左闭右开 | 防止 `BETWEEN` 包含结束日全量 | 编译器硬编码 `>= ? AND < DATE_ADD(?, INTERVAL 1 DAY)` |
| 枚举白名单 | dimension/filter/code 不在 YAML 里 → 拒绝 | `QueryPlanValidator` |

### 第二层：执行层 Guard（最后防线）

`ReadOnlySqlGuard` 在 SQL 进入 JDBC 前强制校验：

- 顶层必须是单条 `SELECT`
- 禁止 `INSERT/UPDATE/DELETE/ALTER/DROP/TRUNCATE/CREATE`
- 禁止 `information_schema` / `mysql.*` / `sleep()` / `get_lock()`
- 禁止 `INTO OUTFILE` / `LOAD_FILE`
- 字符串内容先剥离再扫描（防止 `'delete'` 误杀）

### 第三层：运行期兜底

```java
JdbcTemplate template = new JdbcTemplate(dataSource);
template.setMaxRows(5000);           // 结果集硬上限
template.setQueryTimeout(10);        // 服务端超时 10s
// SQL 前缀加 MySQL hint: /*+ MAX_EXECUTION_TIME(8000) */
// 数据库层：独立只读账号 GRANT SELECT
```

### 数字可审计

每条 SQL 生成 `sqlHash = SHA-256(sql 折叠后)`，写入结果与审计日志。**任何数字都能反查到是哪条 SQL 算出来的** — 企业对账的基石。

---

## 量化效果

### 性能

| 环节 | P95 耗时 | 说明 |
|---|---|---|
| SQL 编译 | < 1ms | YAML 解析 + 模板拼接，纯内存操作 |
| SQL 执行（单表聚合） | 5-50ms | 命中 `expense_buyDate` / `expense_stationCode` 索引 |
| SQL 执行（JOIN 会员表） | 7-100ms | `expense_mobile = member_mobile`，需要覆盖索引 |
| 豆包模型路由 + 生成 | 2-5s | Tool Call 决策 + 自然语言组织 |
| 全链路（从发送到首 token） | **< 5s** | SSE 流式输出，不等模型全生成 |

### 数字正确性

| 指标 | 基线 | 来源 |
|---|---|---|
| 上月 GMV | 951,680.40 元 | `SUM(NUM(expense_realamt))` 1,508 笔 |
| Q3 成交会员 | 2,064 | `COUNT(DISTINCT expense_mobile)` |
| 门店客单价 | ¥2,193 / ¥2,163 | 派生指标 SALES/ORDERS，Java 端除法 |

### 配置化 vs 手写对比

| 指标 | 手写成本 | 配置化成本 |
|---|---|---|
| 新增一个指标 | Mapper.java + XML + DTO.java + Result.java + 测试 ≈ **1-2 天** | YAML 20 行 + 干跑校验 ≈ **10 分钟** |
| 修改口径 | 改 XML + 重编译 + 重部署 | 改 YAML + 重启（或热加载） |
| JOIN 翻倍防护 | 靠人 review 发现 | `grain` 配置化，编译期硬拒绝 |

### 踩坑实录（就是你的咨询产品说明书）

| 坑 | 现象 | 根因 | 修复 |
|---|---|---|---|
| **手机号二次转义** | 门店会员消费全是 0 元 | `expense_mobile = member_mobile` JOIN 永不命中 | 生成器 `_raw_mobile` 存原始值 |
| **豆包 endpoint ID** | `/api/v3/v1/chat/completions` 404 | 用 model name 调用而不是 endpoint ID | `.env` 换成 endpoint ID |
| **completions-path 层级** | URL 拼错位置 | `spring.ai.openai.completions-path` 写错层 | 修正为 `spring.ai.openai.chat.completions-path` |
| **MySQL 字符集** | 中文注释变 `C3A4C2BC` hex | `character_set_client=latin1` + PowerShell 管道编码 | `docker cp` + `mysql source --default-character-set=utf8mb4` |
| **VARCHAR 金额直接 SUM** | 某些门店金额偏差 30% | `expense_realamt` 是 VARCHAR，MySQL 隐式转换截断到第一个非数字字符 | `NUM()` = `CAST(NULLIF(TRIM(col),'') AS DECIMAL(20,2))` |

---

## 技术栈

```
Agent 层     : Spring Boot 3.5 + Spring AI 1.1 + MCP Client (SSE)
工具层       : Spring Boot + MCP Server (SSE) + SnakeYAML + 手写 SqlCompiler
模型         : 豆包方舟 API (deepseek-v4-flash-ga-260731)
数据库       : MySQL 8.0 (utf8mb4, GRANT SELECT 只读账号)
缓存         : Redis Stack (Redisson ChatMemory)
前端         : Vue 3 + Vite + Element Plus
测试         : JUnit 5 (编译快照 + 对账测试) + Playwright 1.53 (端到端)
```

---

## 下一步

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | JSqlParser 可行性测试 + `column-types.yaml` 首版 | 待做 |
| M1 | MetricRegistry + Validator + SqlCompiler | ✅ 已完成 |
| M2 | Phase1 三指标配置化 + 逐值对账 | ✅ 已完成 |
| M3 | MetricQueryService + 2 域工具 + listMetrics/explainMetric | ✅ 已完成 |
| **M4** | SqlGuard AST 闸门 + 两级聚合 + 审计日志 | **进行中** |
| 企业级 | SubjectContext 数据权限 + 独立只读账号 + 生产压测 | 待规划 |

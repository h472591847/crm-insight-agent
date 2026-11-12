# CRM Insight Agent

> 基于 **标准 MCP (Model Context Protocol)** 构建的企业级数据分析 Agent。
> 自然语言提问 → 确定性 SQL 编译 → 只读安全闸门 → 秒级返回聚合结果。

<p align="center">
  <img src="docs/demos/Q1-GMV.gif" width="480" alt="Q1 Demo">
  <br/>
  <sub>「上月 GMV 是多少？」 —— 自然语言 → 秒级聚合结果</sub>
</p>

## 为什么是 MCP

本项目不是"一个 CRM 工具"，而是**企业 Agent 工具层的可复用参考实现**：

- ✅ **标准 MCP Server (SSE)** — 可接入任意 MCP 宿主（Claude Desktop、Cline、LobeChat…）
- ✅ **标准 MCP Client** — Agent 编排层用 Spring AI + MCP Client，换宿主零改动
- ✅ **工具即能力** — 5 个 MCP Tool 覆盖指标查询 / 即席 SQL / 门店筛选 / 安全守卫 / 指标起草
- ✅ **不绑定模型** — 换豆包 / 换 DeepSeek / 换 Claude，协议层透明

```
┌─────────────────┐   MCP over SSE    ┌─────────────────┐   JDBC    ┌─────────┐
│  MCP Client     │ ◄──────────────► │  MCP Server     │ ◄───────► │  MySQL  │
│  (Spring AI)    │                   │  (Tool Runtime) │          │  8.0    │
│                 │                   │                 │          │ utf8mb4 │
│ • 会话编排      │                   │ • SqlCompiler   │          └─────────┘
│ • SSE 流式输出  │                   │ • MetricRegistry│
│ • ChatMemory    │                   │ • ReadOnlyGuard │          ┌─────────┐
└─────────────────┘                   └─────────────────┘ ◄───────► │  Redis  │
                                                                     │ Stack   │
```

## 快速开始

### 1. 起依赖服务（MySQL + Redis）

```powershell
docker compose up -d
# crm-mysql   :3336   (utf8mb4, 已灌 12,000 订单 / 3,000 会员)
# redis-stack :9379
```

### 2. 起 MCP Server（工具层）

```powershell
docker run --rm -p 9060:9060 `
  -v "E:/agent_project/crm_agent/crm-insight-agent/backend:/app" `
  -v "D:/MavenRepository:/root/.m2/repository" `
  -w /app amazoncorretto:21 bash run-server.sh
```

### 3. 起 MCP Client（Agent 层）

```powershell
$env:OPENAI_API_KEY = "<豆包方舟 API Key>"
$env:OPENAI_CHAT_MODEL = "deepseek-v4-flash-ga-260731"  # endpoint ID，不是 model name

docker run --rm -p 9090:9090 `
  -e OPENAI_API_KEY=$env:OPENAI_API_KEY `
  -e OPENAI_CHAT_MODEL=$env:OPENAI_CHAT_MODEL `
  -v "E:/agent_project/crm_agent/crm-insight-agent/backend:/app" `
  -v "D:/MavenRepository:/root/.m2/repository" `
  -w /app amazoncorretto:21 bash run-client.sh
```

### 4. 起前端（可选，用于 DEMO）

```powershell
cd frontend
npm install
npm run dev     # http://localhost:9173/crmAgent/
```

### 5. 跑 Playwright 自动化 DEMO

```powershell
$env:PLAYWRIGHT_BROWSERS_PATH = "E:\agent_project\crm_agent\demo-e2e\.playwright-browsers"
cd demo-e2e
npx.cmd playwright test   # Q1-Q4 自动跑完 + 视频 + 断言
```

## 核心设计

### 指标即配置（Metrics-as-Code）

> 新增一个业务指标 ≈ **写 20 行 YAML**，Java 代码零改动。

```yaml
# mcp-server/src/main/resources/metrics/order.yaml
- metric: STATION_SALES
  title: 门店销售业绩
  grain: ORDER_HEAD
  status: CONFIRMED
  table: expense_calendar_info
  timeColumn: expense_buyDate
  baselineFilters: ["expense_state = '10'", "del_flag = '0'"]
  dimensions: [{ code: STATION, expr: expense_stationCode }]
  measures:
    - { code: SALES,  expr: "NUM(expense_realamt)", agg: SUM, unit: CNY }
    - { code: ORDERS, expr: "*",                    agg: COUNT }
  derived:
    - { code: AVG_ORDER_VALUE, formula: "SALES / ORDERS", scale: 2 }
```

框架按配置**确定性编译**成 SQL，同一问题两次结果完全一致 —— 数字可复现、可对账、可进老板看板。

### 语义白名单（不是 Text2SQL）

模型只输出**结构化枚举组合**（metric + dimension + filter + time range），框架只从配置取表/列/表达式，参数一律 `?` 占位。不存在"模型每次现场生成一条 SQL"的注入面。

### 三层安全纵深

| 层 | 机制 | 防什么 |
|---|---|---|
| **编译期闸门** | 表/列白名单 + 时间边界 + grain 不跨粒度 | 模型乱填参数 |
| **执行层 Guard** | ReadOnlySqlGuard — 正则 + 关键词匹配 + 只读账号 | 写操作 / DDL / 系统对象 |
| **运行期兜底** | maxRows / queryTimeout / MAX_EXECUTION_TIME hint | 慢查询 / 结果集爆炸 |

### SQL 可审计

每条 SQL 生成 `sqlHash = SHA-256(sql 折叠后)`，写入结果与审计日志。**任何数字都能反查到是哪条 SQL 算出来的**。

## 技术栈

| 层 | 技术 |
|---|---|
| Agent 编排 | Spring Boot 3.5 + Spring AI 1.1 + MCP Client (SSE) |
| 工具运行时 | Spring Boot + MCP Server (SSE) |
| 指标引擎 | SnakeYAML + 手写 SqlCompiler + JdbcTemplate |
| 模型 | 豆包方舟 API (deepseek-v4-flash) |
| 数据库 | MySQL 8.0 (utf8mb4) |
| 缓存 | Redis Stack (Redisson) |
| 前端 | Vue 3 + Vite + Element Plus |
| 测试 | JUnit 5 + Playwright 1.53 |

## 文档

| | |
|---|---|
| 🎬 [DEMO — Q1-Q4 演示](docs/DEMO.md) | 4 段 GIF + 架构图 + 技术细节 |
| 📖 [Case Study](docs/CASE_STUDY.md) | 业务问题 → 架构方案 → 安全设计 → 量化效果 |
| 🧠 [方法论资产](docs/METHODOLOGY.md) | 指标口径确认 / 语义白名单 / Text2SQL 安全边界 |
| 🛠 [新增指标 SOP](docs/NEW_METRIC_SOP.md) | 产品给口径 → draftMetric 起草 → explainMetric 干跑 → 对账 |
| 🔒 [安全设计](docs/SECURITY.md) | 三层纵深 + 只读账号 + 审计日志 |

## License

MIT — 可复用于企业 Agent 工具层参考实现。

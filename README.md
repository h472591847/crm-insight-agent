# CRM Insight Agent

> An enterprise-grade data analysis Agent built on the **standard MCP (Model Context Protocol)**.
> Natural Language Query → Deterministic SQL Compilation → Read-Only Safety Gate → Sub-Second Aggregated Results.

<p align="center">
  <img src="docs/demos/Q1-GMV.gif" width="480" alt="Q1 Demo">
  <br/>
  <sub>"What was last month's GMV?" — Natural Language → Sub-Second Aggregated Result</sub>
</p>

## Why MCP?

This project is not just a standalone CRM tool, but a **reusable reference implementation for enterprise Agent tool layers**:

- ✅ **Standard MCP Server (SSE)** — Integrates seamlessly with any MCP host (Claude Desktop, Cline, LobeChat, etc.)
- ✅ **Standard MCP Client** — Agent orchestration layer powered by Spring AI + MCP Client; switch hosts with zero changes.
- ✅ **Tools as Capabilities** — 5 MCP Tools covering metric queries, ad-hoc SQL, store filtering, security guards, and metric drafting.
- ✅ **Model Agnostic** — Switch between Doubao, DeepSeek, or Claude effortlessly with a transparent protocol layer.

```text
┌─────────────────┐   MCP over SSE    ┌─────────────────┐   JDBC    ┌─────────┐
│  MCP Client     │ ◄──────────────► │  MCP Server     │ ◄───────► │  MySQL  │
│  (Spring AI)    │                   │  (Tool Runtime) │          │  8.0    │
│                 │                   │                 │          │ utf8mb4 │
│ • Session Orche │                   │ • SqlCompiler   │          └─────────┘
│ • SSE Streaming │                   │ • MetricRegistry│
│ • ChatMemory    │                   │ • ReadOnlyGuard │          ┌─────────┐
└─────────────────┘                   └─────────────────┘ ◄───────► │  Redis  │
                                                                     │ Stack   │
```

## Quick Start

### 1. Start Dependent Services (MySQL + Redis)

```powershell
docker compose up -d
# crm-mysql   :3336   (utf8mb4, pre-seeded with 12,000 orders / 3,000 members)
# redis-stack :9379
```

### 2. Start the MCP Server (Tool Layer)

```powershell
docker run --rm -p 9060:9060 `
  -v "E:/agent_project/crm_agent/crm-insight-agent/backend:/app" `
  -v "D:/MavenRepository:/root/.m2/repository" `
  -w /app amazoncorretto:21 bash run-server.sh
```

### 3. Start the MCP Client (Agent Layer)

```powershell
$env:OPENAI_API_KEY = "<Your API Ark Key>"
$env:OPENAI_CHAT_MODEL = "deepseek-v4-flash-ga-260731"  # endpoint ID, not model name

docker run --rm -p 9090:9090 `
  -e OPENAI_API_KEY=$env:OPENAI_API_KEY `
  -e OPENAI_CHAT_MODEL=$env:OPENAI_CHAT_MODEL `
  -v "E:/agent_project/crm_agent/crm-insight-agent/backend:/app" `
  -v "D:/MavenRepository:/root/.m2/repository" `
  -w /app amazoncorretto:21 bash run-client.sh
```

### 4. Start the Frontend (Optional, for DEMO)

```powershell
cd frontend
npm install
npm run dev     # http://localhost:9173/crmAgent/
```

### 5. Run Playwright Automated DEMO

```powershell
$env:PLAYWRIGHT_BROWSERS_PATH = "E:\agent_project\crm_agent\demo-e2e\.playwright-browsers"
cd demo-e2e
npx.cmd playwright test   # Automatically runs Q1-Q4 + records video + assertions
```

## Core Design

### Metrics-as-Code

> Adding a new business metric takes ≈ **20 lines of YAML** with zero Java code modifications.

```yaml
# mcp-server/src/main/resources/metrics/order.yaml
- metric: STATION_SALES
  title: Store Sales Performance
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

The framework **deterministically compiles** configurations into SQL, guaranteeing identical results for identical queries — fully reproducible, reconcilable, and audit-ready for executive dashboards.

### Semantic Whitelisting (Not Raw Text2SQL)

The model only outputs **structured enum combinations** (metric + dimension + filter + time range). The framework maps these directly to pre-configured tables, columns, and expressions with parameters strictly bound via `?` placeholders, completely eliminating SQL injection risks inherent in raw Text2SQL generation.

### Three-Tier Defense-in-Depth Security

| Layer | Mechanism | Protection Scope |
|---|---|---|
| **Compilation Gate** | Table/column whitelisting + time boundary + grain cross-validation | Prevents arbitrary or hallucinated model parameters |
| **Execution Guard** | ReadOnlySqlGuard — Regex + keyword matching + read-only DB user | Blocks write operations, DDL statements, and system objects |
| **Runtime Fallback** | maxRows / queryTimeout / MAX_EXECUTION_TIME hints | Mitigates slow queries and result-set explosion |

### SQL Auditing

Every generated SQL statement produces a unique `sqlHash = SHA-256(normalized sql)`, which is written to execution and audit logs. **Every business metric can be deterministically traced back to the exact SQL query that generated it.**

## Tech Stack

| Layer | Technology |
|---|---|
| Agent Orchestration | Spring Boot 3.5 + Spring AI 1.1 + MCP Client (SSE) |
| Tool Runtime | Spring Boot + MCP Server (SSE) |
| Metric Engine | SnakeYAML + Custom SqlCompiler + JdbcTemplate |
| LLM Model | Doubao Ark API (deepseek-v4-flash) |
| Database | MySQL 8.0 (utf8mb4) |
| Caching | Redis Stack (Redisson) |
| Frontend | Vue 3 + Vite + Element Plus |
| Testing | JUnit 5 + Playwright 1.53 |

## Documentation

| | |
|---|---|
| 🎬 [DEMO — Q1-Q4 Showcase](docs/DEMO.md) | 4 GIFs + Architecture Diagram + Technical Details |
| 📖 [Case Study](docs/CASE_STUDY.md) | Business Problems → Architectural Design → Security → Quantified Impact |
| 🧠 [Methodology](docs/METHODOLOGY.md) | Metric Definition Alignment / Semantic Whitelisting / Text2SQL Boundaries |
| 🛠 [New Metric SOP](docs/NEW_METRIC_SOP.md) | Business Definition → draftMetric → explainMetric Dry-run → Reconciliation |
| 🔒 [Security Design](docs/SECURITY.md) | Three-Tier Defense + Read-Only User + Audit Logging |

## ☕ Support & Sponsorship

If **crm-insight-agent** saves you time or helps your workflow, consider buying me a coffee! Your support keeps this open-source project maintained and fuels future feature updates.

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/h472591847)

## License

MIT — Free to use as a reference implementation for enterprise Agent tool layers.

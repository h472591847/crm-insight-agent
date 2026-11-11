# CRM Insight Agent — 企业问数 Agent 工具层参考实现

> 一个 **协议标准、可复用** 的企业数据分析 Agent 工具层：以标准 [MCP](https://modelcontextprotocol.io/) Server 暴露受控的 CRM 问数能力，可接入任意 MCP 宿主（Claude Desktop、Cursor、自定义 Agent 框架）。管理者用自然语言提问，Agent 经 YAML 驱动的语义层理解意图，编译参数化只读 SQL，返回结构化结果。
>
> **定位**：不只是一个 CRM 工具，更是「企业 Agent 如何安全地接入业务数据库」的可复用参考实现——语义白名单、只读 SQL 闸门、JOIN 翻倍防护、PII 聚合、会话隔离均在服务端强制，模型只负责表达意图。

[![Java](https://img.shields.io/badge/Java-21-orange)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.14-brightgreen)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.2-green)](https://spring.io/projects/spring-ai)
[![MCP](https://img.shields.io/badge/MCP-Server%20%26%20Client-purple)](https://modelcontextprotocol.io/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)
[![CI](https://github.com/h472591847/crm-insight-agent/actions/workflows/ci.yml/badge.svg)](https://github.com/h472591847/crm-insight-agent/actions/workflows/ci.yml)

**English TL;DR:** A protocol-standard, reusable enterprise data-analytics agent layer. It exposes controlled CRM query capabilities as a standard **MCP Server** (SSE), consumable by any MCP host. The agent resolves natural-language intent through a YAML-driven semantic layer, compiles parameterized read-only SQL, and streams structured results. Security is enforced entirely server-side: entity/column whitelists, read-only SQL guard, JOIN-fanout protection, PII-safe aggregation, session isolation, and three-tier timeouts. The semantic layer, SQL safety gates, and session system are **original implementations** (not from the Spring AI tutorial scaffolding). Ships with a deterministic mock MySQL dataset for one-command demo.

---

## 目录

- [自研核心](#自研核心)
- [核心特性](#核心特性)
- [架构](#架构)
- [安全设计](#安全设计)
- [MCP 工具清单](#mcp-工具清单)
- [快速开始](#快速开始)
- [Mock 数据集](#mock-数据集)
- [项目结构](#项目结构)
- [开发与测试](#开发与测试)
- [文档索引](#文档索引)
- [与教程模板的关系](#与教程模板的关系)
- [已知限制与 Roadmap](#已知限制与-roadmap)
- [License](#license)

---

## 自研核心

本项目的价值不在「又一个 Spring AI Demo」，而在以下**全部为独立设计与实现**的核心层（教程模板仅提供 Spring AI + MCP 的脚手架，见[与教程模板的关系](#与教程模板的关系)）：

| 自研模块 | 位置 | 解决什么问题 |
|---|---|---|
| **Ad-hoc 语义层** | `mcp-server/.../adhoc/` | 把「维度 × 度量 × 过滤」的自由组合查询，编译成安全的参数化 SQL。`metadata.yaml` 是唯一事实来源，新增可查列只改 YAML 不改 Java。含 `SemanticRegistry`、`AdhocSqlCompiler`、`AdhocRequestValidator`。 |
| **只读 SQL 安全闸门** | `mcp-server/.../shared/sql/` | `ReadOnlySqlGuard` 静态校验：仅允许单条 `SELECT`，拒绝多语句/注释/DDL/系统库；配合连接池 `read-only` + 会话级 `SET SESSION TRANSACTION READ ONLY` 兜底。 |
| **多层查询守卫** | `mcp-server/.../shared/limits/` + `validate/` | QueryGuard（明细表必须带时间窗或等值过滤）、Cardinality 守卫（`to_many` 关系拒绝跨粒度选度量，防 JOIN 翻倍）、`countKey`（LEFT JOIN 场景 `COUNT(*)` 自动改 `COUNT(countKey)`）。 |
| **PII 保护策略** | `adhoc/validate/` + `metrics/validate/` | 手机号/姓名等个人级字段只允许聚合输出（去重计数），禁止原值/明细输出。 |
| **固化指标引擎** | `mcp-server/.../metrics/` | YAML 配置驱动的指标注册表（`MetricRegistry`），含口径校验、SQL 编译、`explainMetric` 预览（不执行）。覆盖会员/订单 7 个核心指标。 |
| **会话与记忆体系** | `mcp-client/.../repository/` + `utils/` | Spring AI 1.x 无官方 Redis ChatMemory starter，**手动实现** `RedisChatMemoryRepository`（TTL 7 天、按会话隔离）+ `SessionOwnershipRepository`（列表/读取/删除/写入均校验 userId，越权 403、无归属 404）+ Redisson 分布式锁防并发写。 |
| **澄清式交互协议** | `mcp-client` + `frontend/ClarifyCard.vue` | 模型拿不准门店/口径时，通过 `<clarify>{JSON}</clarify>` 输出追问卡片，前端渲染选项，确认后继续执行——避免猜错门店、答非所问。 |

> 这些模块的设计决策（如为什么语义层用 YAML 而不是让模型自由写 SQL、为什么 JOIN 翻倍必须在编译期拦截）记录在 [`docs/ai-agent/`](backend/docs/ai-agent/)，可直接复用于其他企业问数场景。

---

## 核心特性

### 1. 标准 MCP Server，可接入任意宿主

工具层以 **标准 MCP Server**（SSE 传输）独立部署，Agent 编排层（`mcp-client`）通过 MCP 协议消费。这意味着：

- **不绑定本项目前端**：同一个 `mcp-server` 可被 Claude Desktop、Cursor、或任何 MCP 宿主直接调用。
- **工具层与编排层解耦**：换 Agent 框架（LangChain、AutoGen、自研）不需要重写工具。
- **能力可横向复用**：把 `metadata.yaml` 换成另一家企业的表结构，同一套语义层 + 安全闸门即可复用。

### 2. 双模式问数：固化指标 + Ad-hoc 语义层

| 模式 | 适用场景 | 例子 |
|---|---|---|
| **固化指标**（YAML 配置驱动） | 高频、口径已确认的固定指标 | "本月有效订单金额多少" → 预定义 SQL 直接查询 |
| **Ad-hoc 自助分析**（语义元数据驱动） | 探索性、一次性组合查询 | "长江路店本月 VIP 会员的订单金额按性别分布" → 自由组合 维度 × 度量 × 过滤 |

固化指标覆盖会员/订单两大主题 7 个核心指标（会员总数、近 30 天活跃、有效订单数、订单金额、实付金额、去重买家数、客单价）。Ad-hoc 语义层注册 **5 个实体**（member / order / detail / station / goods）。

### 3. 多轮澄清式交互

模型拿不准门店/会员/口径时，通过 `<clarify>{JSON}</clarify>` 协议输出追问卡片，前端 `ClarifyCard.vue` 渲染选项，确认后继续执行。

### 4. 会话管理与记忆隔离

- Redis ChatMemory 持久化（手动实现，Spring AI 1.x 无官方 starter），TTL 7 天、按会话隔离
- 会话归属校验：列表/读取/删除/写入均校验 userId，他人访问 403、无归属 404
- Redisson 分布式锁防止同一会话并发写入导致记忆错乱

---

## 架构

```
┌─────────────────────┐   HTTP/SSE    ┌──────────────────────────────┐    MCP (SSE)    ┌───────────────────────────────┐
│  前端 Vue 3 + TS     │ ────────────▶ │  mcp-client（Agent 编排）      │ ──────────────▶ │  mcp-server（工具层）           │
│  SSE 流式渲染        │               │  · Spring AI 1.1.2 (OpenAI兼容)│                 │  · MCP Tools（指标/Adhoc/门店） │
│  ClarifyCard 追问    │               │  · MCP Client                 │                 │  · 固化指标引擎（YAML）  [自研]  │
│  会话侧边栏          │               │  · Redis ChatMemory [自研]     │                 │  · Ad-hoc 语义层 [自研]         │
└─────────────────────┘               │  · 会话归属校验 [自研]          │                 │  · 只读 SQL 安全闸门 [自研]     │
                                      └──────────────────────────────┘                 │  · 查询守卫 / PII 策略 [自研]  │
                                                                                       └───────────────┬───────────────┘
                                                                                                       │ 只读 JDBC（参数化）
                                                                                                       ▼
                                                                                              ┌─────────────────┐
                                                                                              │   CRM MySQL     │
                                                                                              │  （只读连接池）  │
                                                                                              └─────────────────┘
```

> 任何 MCP 宿主都可替换 `mcp-client` 直接消费 `mcp-server`，工具层与编排层完全解耦。

| 层 | 技术 |
|---|---|
| 语言 | Java 21（corretto） |
| 框架 | Spring Boot 3.5.14 + Spring AI 1.1.2（OpenAI 兼容端点，如阿里云百炼） |
| 工具协议 | MCP（Spring AI MCP server/client，SSE 传输） |
| 数据 | MySQL 8 + MyBatis Plus（只读连接池 + 会话级 `READ ONLY`） |
| 缓存/记忆 | Redis Stack + Redisson 3.45.1 分布式锁（ChatMemory 手动实现） |
| 前端 | Vue 3 + TypeScript + Pinia + Element Plus + Vite |

---

## 安全设计

模型只能"表达意图"，**所有安全边界由服务端强制**，用户与模型均不可绕过：

| 闸门 | 规则 | 防什么 |
|---|---|---|
| 只读 SQL 闸门 [自研] | 仅允许单条 `SELECT`，拒绝多语句/注释/DDL/系统库 | SQL 注入、越权写入 |
| 连接级只读 | 连接池 `read-only` + 会话 `SET SESSION TRANSACTION READ ONLY` | 兜底防线 |
| 实体白名单 | 只能查 metadata.yaml 声明过的实体 | 全表扫非授权表 |
| 列白名单 | 维度/度量/过滤列必须在 YAML 注册 | 拼未知列、注入 |
| baselineFilters | 自动追加 `del_flag='0'`、订单状态过滤 | 把退单/删除数据算进来 |
| QueryGuard [自研] | 明细表（1500 万行级）必须带 ≤92 天时间窗或手机号/流水号等值 | 大范围扫描拖垮库 |
| Cardinality 守卫 [自研] | `to_many` 关系拒绝跨粒度选度量 | JOIN 后金额被行数放大翻倍 |
| countKey [自研] | JOIN 场景 `COUNT(*)` 自动改 `COUNT(countKey)` | LEFT JOIN 扩展行把 NULL 计成 1 |
| PII 保护 [自研] | 手机号/姓名等个人级字段只允许聚合输出（去重计数），禁止原值/明细输出 | 个人信息泄露 |
| 超时三档 | SQL hint 60s → JdbcTemplate 65s → MCP 请求 340s | 单请求卡死服务 |
| 凭证不入库 | API Key/密码只走环境变量 | 密钥泄露 |

---

## MCP 工具清单

| 工具 | 类型 | 功能 |
|---|---|---|
| `listMetrics` / `queryMemberMetric` / `queryOrderMetric` / `explainMetric` | 固化指标 | 指标目录、按口径查询、SQL 预览（不执行） |
| `listSemanticCatalog` / `adhocQuery` / `adhocQueryBatch` / `explainAdhoc` | Ad-hoc 语义层 | 语义目录、单块/批量（≤5 块）组合查询、SQL 预览 |
| `guessStation` | 辅助 | 门店名称模糊解析编码（多候选需用户确认） |
| `resolveOfficeStores` | 辅助 | 机构/总部/大区 → 下级门店编码集合（≤500） |
| `guessMemberByMobile` | 辅助 | 手机号查会员（PII 仅返回聚合信息） |
| `clarifyAsk` | 辅助 | 输出澄清追问卡片 |
| `DateTool` / `EmailTool` | 通用 | 时间 / 邮件 |

> 以上工具通过标准 MCP 协议暴露，可在任意 MCP 宿主中以 `tools/list` 发现并调用。

---

## 快速开始

### 0. 前置条件

- JDK 21+、Node 20+、Docker
- 一个 OpenAI 兼容端点的 API Key（阿里云百炼、OpenAI、DeepSeek 等均可）

### 1. 生成 Mock 数据

```bash
cd backend
python mock-data/generate_mock_data.py            # 默认 3000 会员 / 12000 订单
# python mock-data/generate_mock_data.py --scale small   # 快速演示：500 会员
```

### 2. 一键启动基础设施

```bash
# 回到仓库根目录
docker compose up -d
```

首次启动自动建库 `crmtest`（端口 `3336`，root 密码 `123456`）并导入 Mock 数据；Redis Stack 端口 `9379`（密码 `123456`）。

> MySQL 仅在数据卷为空的首次启动时执行 initdb 脚本；重新导入需先 `docker compose down -v`。

### 3. 配置环境变量

```bash
cp backend/mcp-client/src/main/resources/.env.example backend/mcp-client/src/main/resources/.env
cp backend/mcp-server/src/main/resources/.env.example backend/mcp-server/src/main/resources/.env
```

编辑两个 `.env`，填入模型端点与数据库连接（默认与 compose 一致：MySQL `localhost:3336`，Redis `localhost:9379`）。

### 4. 启动后端

```bash
cd backend
# 工具层（端口 9060，先启动）
./mvnw -pl mcp-server spring-boot:run

# 另开终端：Agent 编排层（端口 9090）
./mvnw -pl mcp-client spring-boot:run
```

Windows 下使用 `mvnw.cmd`。

### 5. 启动前端

```bash
cd frontend
npm install
npm run dev
# 访问 http://localhost:5173
```

### 6. 试一试

- "本月有效订单金额多少？"
- "长江路店近 30 天 VIP 会员的消费金额按性别分布"
- "上海区域各门店客单价排行"

---

## Mock 数据集

为了让任何人 **clone 即跑**，仓库提供完全虚构、确定性的脱敏数据生成器：

```bash
cd backend
python mock-data/generate_mock_data.py
```

- 生成 `backend/mock-data/init/01_schema.sql`（表结构，与真实 CRM DDL 对齐）与 `02_mock_data.sql`（虚构数据）
- 门店机构树（总部 → 大区 → 门店，含直营/加盟/渠道类型）、会员性别/层级/要客/PLUS 标记、订单与明细（VARCHAR 金额列、退单状态、删除标记等真实数据特征）
- 手机号/姓名/地址全部为虚构占位（`138****xxxx`、随机中文姓名），**无任何真实个人信息**
- `docker compose up` 时通过 MySQL `docker-entrypoint-initdb.d` 自动导入

---

## 项目结构

```
├── backend/                    # 后端（Maven 多模块）
│   ├── mcp-common              #   公共资源（统一 logback 配置）
│   ├── mcp-client              #   Agent 编排层（Spring AI MCP Client + 自研会话/记忆体系）
│   ├── mcp-server              #   工具层（MCP Server + 自研语义层/指标引擎/安全闸门）
│   ├── mock-data               #   Mock 数据生成器
│   ├── scripts/docker          #   完整 compose（含 SearXNG 联网搜索）
│   └── docs/ai-agent           #   业务口径 / 表结构 / 语义元数据规范
├── frontend/                   # 前端（Vue 3 + TS + Vite）
│   ├── src/api                 #   后端接口封装
│   ├── src/components          #   ClarifyCard 等组件
│   └── ...
├── docker-compose.yml          # 根目录一键启动（MySQL + Redis + Mock 数据导入）
└── README.md
```

---

## 开发与测试

```bash
cd backend

# 全量单测（默认排除 8 个依赖真实 CRM 测试库的集成/对账用例）
./mvnw -pl mcp-server -am test
```

本地已验证：**Tests run: 266, Failures: 0, Errors: 0**。

如需运行集成测试（需真实 CRM 测试库），临时取消排除：

```bash
./mvnw -pl mcp-server -am test -Dsurefire.excludes=
```

---

## 文档索引

| 文档 | 读者 | 内容 |
|---|---|---|
| [backend/docs/DELIVERY.md](backend/docs/DELIVERY.md) | 全体 | 交付范围、能力清单、使用方式、已知限制 |
| [backend/docs/ai-agent/BUSINESS_METRICS.md](backend/docs/ai-agent/BUSINESS_METRICS.md) | 产品 | 业务口径定义（有效订单、del_flag、门店编码口径） |
| [backend/docs/ai-agent/DATABASE_SCHEMA.md](backend/docs/ai-agent/DATABASE_SCHEMA.md) | 开发/DBA | CRM 核心表结构、DDL 与数据风险说明 |
| [backend/docs/ai-agent/METADATA_SCHEMA.md](backend/docs/ai-agent/METADATA_SCHEMA.md) | 开发 | 语义元数据字段规范 + 加载期硬校验清单 |
| [backend/docs/ai-agent/METRICS_CONFIGURATION.md](backend/docs/ai-agent/METRICS_CONFIGURATION.md) | 开发 | 固化指标 YAML 配置规范 |
| [backend/docs/ai-agent/SUPPORTED_METRICS.md](backend/docs/ai-agent/SUPPORTED_METRICS.md) | 产品/测试 | 指标清单与 Ad-hoc 组合示例 |

---

## 与教程模板的关系

诚实说明：本项目基于公开教程仓库 `SpringAi-MCP-RAG-Dev` 的 **脚手架**（Spring Boot + Spring AI + MCP server/client 的基础搭建、基础对话与 RAG/联网搜索示例）起步。

**教程模板提供的**：Spring AI 与 MCP 的集成骨架、基础对话流程、RAG 与联网搜索示例代码。

**本项目独立实现的（即上面「自研核心」全部内容）**：
- Ad-hoc 语义层（`metadata.yaml` 语义目录 + SQL 编译器 + 请求校验器）
- 只读 SQL 安全闸门 + 多层查询守卫（QueryGuard / Cardinality / countKey）
- PII 聚合保护策略
- 固化指标引擎（YAML 指标注册表 + 口径校验）
- Redis ChatMemory 手动实现 + 会话归属校验 + 分布式锁
- 澄清式交互协议（`<clarify>` 卡片）
- Mock 数据生成器与 docker-compose 一键演示环境

区分教程复刻与独立作品的关键：**教程教你怎么把 Spring AI 和 MCP 跑通；本项目解决的是「让模型安全地查企业生产库」这个教程不覆盖的问题。** 后者的设计决策与踩坑记录在 [`docs/ai-agent/`](backend/docs/ai-agent/)。

---

## 已知限制与 Roadmap

| 状态 | 事项 |
|---|---|
| 已知限制 | 模型端点切换需重启；metadata.yaml 变更重启生效；明细表 92 天窗；无审计日志；无登录鉴权 |
| 🔜 计划中 | 审计日志（查询内容持久化） |
| 🔜 计划中 | 登录鉴权与机构数据权限注入 |
| 🔜 计划中 | 多 Agent 并行分析（Graph/Supervisor） |
| 🔜 计划中 | metadata.yaml 热更新 |

> 说明：当前代码由个人独立完成，用于真实 CRM 业务的数据分析场景验证。由于合规要求，仓库使用 Mock 数据，不包含任何真实业务数据。

---

## License

[MIT](LICENSE)

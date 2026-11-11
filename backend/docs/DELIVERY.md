# CRM 数据分析 Agent 交付文档

> **版本**：v1.0（2026-11-05）
> **交付范围**：Phase 1 固化指标 + Ad-hoc 自助查询引擎
> **代码分支**：`crm_agent_dev`
> **测试覆盖**：mcp-server 219/219 单测通过 ✅

---

## 一、这个系统是干什么的？

面向企业管理者的 **CRM 业务数据自然语言查询助手**。管理者不需要写 SQL，用日常语言提问，AI 自动理解意图、调用受控的后端工具、查询 CRM 数据库、返回结构化结果。

**两种查询模式**：

| 模式 | 适用场景 | 例子 |
|---|---|---|
| **固化指标**（优先） | 高频、口径已确认的固定指标 | "本月有效订单金额多少" → 系统直接用预定义 SQL 查询 |
| **Ad-hoc 自助分析** | 探索性、一次性组合查询 | "长江路店本月 VIP 会员的订单金额按性别分布" → 自由组合维度×度量×过滤条件 |

---

## 二、已交付能力清单

### 2.1 固化指标（YAML 配置驱动，高频口径优先用）

已实现 **7 个核心指标**，覆盖会员、订单两大主题：

| 指标名 | 业务含义 | 口径确认状态 |
|---|---|---|
| `MEMBER_COUNT` | 有效会员总数 | ✅ 已确认 |
| `MEMBER_ACTIVE_30D` | 近 30 天活跃会员数 | ✅ 已确认 |
| `ORDER_COUNT` | 有效订单数（state=10） | ✅ 已确认 |
| `ORDER_AMOUNT` | 有效订单零售金额汇总 | ✅ 已确认 |
| `ORDER_REAL_AMOUNT` | 有效订单实付金额汇总 | ✅ 已确认 |
| `BUYER_COUNT` | 去重下单会员数 | ✅ 已确认 |
| `AVG_ORDER_AMOUNT` | 客单价 | ✅ 已确认 |

**交付工具**：`listMetrics`（列出全部可用指标）、`queryMemberMetric`/`queryOrderMetric`（按指标代码查询）、`explainMetric`（返回 SQL 预览，不开执行）。

### 2.2 Ad-hoc 自助分析（语义元数据驱动，探索性查询）

**5 个实体**已在语义层注册（可查列全在 metadata.yaml 声明）：

| 实体 | 粒度 | 说明 | JOIN 能力 |
|---|---|---|---|
| `member` | 会员快照 | 会员基础信息 + 最新消费快照 | ↔ order（to_one） |
| `order` | 订单主表 | 消费流水主记录 | ↔ member（to_one）、↔ detail（to_many） |
| `detail` | 订单明细 | 订单商品行（大表，1500 万+） | ↔ order（to_one） |
| `station` | 门店字典 | 门店编码/名称/区域 | 纯字典，单表查询 |
| `goods` | 商品字典 | 商品编码/名称/品类 | ↔ detail（to_one） |

**安全闸门**（系统自动执行，模型和用户不可绕过）：

| 闸门 | 规则 | 防什么 |
|---|---|---|
| 实体白名单 | 只能在 entities 里声明过的实体 | 全表扫非授权表 |
| 列白名单 | 维度/度量/过滤列必须在 metadata.yaml 声明 | 拼未知列 / SQL 注入 |
| baselineFilters | 每次查询自动追加（如 del_flag='0'） | 把退单/删除数据算进来 |
| queryGuard | 明细表必须带时间窗≤92 天或手机号/流水号等值 | 扫 1500 万行 OOM |
| cardinality | to_many 关系拒绝跨粒度选度量 | JOIN 后金额被行数乘翻 |
| indexed 标注 | 模型优先用有索引的列过滤 | 无索引列扫全表 |
| countKey | JOIN 场景 COUNT(*) 自动改成 COUNT(countKey) | LEFT JOIN 扩展行把 NULL 计成 1 |
| 超时三档 | SQL hint 60s → JdbcTemplate 65s → MCP 340s | 卡死整个服务 |

**交付工具**：`listSemanticCatalog`（每次查询前必调，列出当前所有可用实体/维度/度量/过滤列）、`adhocQuery`（单块查询）、`adhocQueryBatch`（多块独立查询，≤5 块）、`explainAdhoc`（返回编译后的 SQL 预览，不开执行）。

### 2.3 辅助工具

| 工具 | 用途 |
|---|---|
| `guessStation` | 门店名称模糊匹配编码（恰好 1 个候选直接用，多个问用户确认，0 个要编码） |
| `guessMemberByMobile` | 手机号查会员（支持模糊前缀匹配） |
| `clarifyAsk` | 模型需要澄清时输出追问卡片（前端渲染） |

### 2.4 Chat 会话管理

- Redis ChatMemory 持久化（手动实现，Spring AI 1.x 无官方 starter）
- 默认 TTL 7 天，按会话隔离
- SessionController：会话列表 / 历史消息 / 删除

### 2.5 前端（独立仓库 `spring-ai-mcp-frontend`）

- ChatView 对话主界面 + SSE 流式输出
- ChatSidebar 会话列表（10 秒刷新，切换/删除）
- ClarifyCard 追问卡片（模型输出 `<clarify>{JSON}</clarify>` 标记时自动渲染）

---

## 三、技术架构（业务方不必深读，给 IT 参考）

```
前端（Vue 3 + SSE）
    ↓ HTTP/JSON
mcp-client（Spring Boot 3.5 + Spring AI 1.1.2）
    ↓ MCP 协议 / HTTP
mcp-server（Spring Boot 3.5 + Spring AI MCP + MyBatis Plus）
    ↓ 只读 JDBC
CRM MySQL（测试数据库）
```

| 层 | 技术 | 版本 |
|---|---|---|
| 语言 | Java | 21（corretto-21.0.13） |
| 框架 | Spring Boot | 3.5.14 |
| AI | Spring AI（OpenAI 兼容端点） | 1.1.2 |
| 模型 | 百炼 OpenAI 兼容端点 `/compatible-mode/v1`（如 qwen3.8-flash，纯文本） | 百炼托管 |
| Embedding | 百炼兼容端点向量模型（默认 1024 维） | 1024 维 |
| 向量存储 | Redis VectorStore（Redis Stack） | — |
| 数据库 | CRM MySQL（只读） | 外部 |
| 缓存 | Redis + Redisson 分布式锁 | — |
| 构建 | Maven（根 pom 统一依赖，3 模块：mcp-common / mcp-client / mcp-server） | — |

---

## 四、怎么用？

### 4.1 启动前置条件

1. **CRM 测试数据库**：应用直连，只读权限账号，库表与 `DATABASE_SCHEMA.md` 一致
2. **Redis Stack**：本地 Docker 或远程，Redis VectorStore + ChatMemory 都用
3. **百炼 API Key**：能调 OpenAI 兼容端点 `/compatible-mode/v1` 的 Key（主账号或 MaaS endpoint 专用 Key 均可）
4. **环境变量**（必须在启动前设好）：

```powershell
$env:OPENAI_API_KEY="sk-xxxx"                                   # 百炼 API Key
$env:OPENAI_BASE_URL="https://llm-xxx.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"  # 必须带 /compatible-mode/v1
$env:OPENAI_CHAT_MODEL="qwen3.8-flash"                           # 兼容端点支持的模型名/endpoint ID
$env:OPENAI_EMBEDDING_MODEL="text-embedding-v3"                  # 1024 维，与 Redis 向量索引对齐
$env:OPENAI_EMBEDDING_DIMENSIONS="1024"
```

### 4.2 启动两服务

```powershell
# mcp-server（端口由 server.port 决定，默认 8081）
.\mvnw.cmd -pl mcp-server spring-boot:run

# mcp-client（默认 8080，代理 MCP server）
.\mvnw.cmd -pl mcp-client spring-boot:run
```

### 4.3 打开前端

访问前端服务地址（默认 http://localhost:5173），直接在对话框里提问。

---

## 五、安全与合规

| 项目 | 状态 | 说明 |
|---|---|---|
| 只读查询 | ✅ | 绝不生成 INSERT/UPDATE/DELETE/DDL |
| 参数化 SQL | ✅ | 所有用户输入值走 `?` 占位，JDBC 参数化传入 |
| 表名/列名白名单 | ✅ | 只能在 metadata.yaml 声明过的范围内选，模型不可控 |
| PII 不输出原值 | ✅ | 手机号/姓名等只聚合输出（去重计数等），不返回明细 |
| 超时兜底 | ✅ | SQL hint + JdbcTemplate + MCP 请求三档递增 |
| 日志脱敏 | ✅ | 只记 SQL/参数/行数，不记个人级数据 |
| 凭证不入库 | ✅ | OPENAI_API_KEY 等只走环境变量，.env 已 gitignore |
| 分布式锁 | ✅ | Redisson 3.45.1，防多实例并发查询 |
| 会话隔离 | ✅ | Redis ChatMemory 按 sessionId 隔离；会话归属（`chat:session:owner:{sessionId}`）按 userId 校验，列表/读取/删除/写入均校验，缺失 400、他人 403、无归属 404 |

---

## 六、当前已知限制（明确写出来，不隐瞒）

| 限制 | 说明 | 影响 |
|---|---|---|
| **模型接 OpenAI 兼容端点** | `spring.ai.openai.*`，base-url 必须带 `/compatible-mode/v1`，换模型要改配置 | 换模型需重启 |
| **Embedding 索引不可热更** | 换 embedding 供应商或维度后必须重建 Redis 向量索引 | 换模型后需删索引重灌文档 |
| **metadata.yaml 重启生效** | 新增实体/维度/度量后必须重启应用 | 热更新要做需额外开发 |
| **Ad-hoc 一次一条实体链** | 不支持跨独立实体的自由 JOIN（只能走声明过的 relationships） | 复杂多表分析要拆成多个 adhocQueryBatch 块 |
| **明细表 92 天窗** | detail 实体 queryGuard 强制时间窗≤92 天 | 跨年大跨度分析要走固化指标（如有） |
| **不支持原始 SQL 片段** | 条件聚合只能引用 conditionPreset ID，不能写任意 CASE WHEN | 复杂业务逻辑要先在 metadata.yaml 注册 |
| **没有审计日志** | 查询内容不持久化审计 | 合规场景需后续补 |
| **没有权限控制** | 任何人能打开前端提问 | 多用户场景需后续补登录鉴权 |

---

## 七、下一步规划（P2 及后续）

| 优先级 | 事项 | 依赖 |
|---|---|---|
| 🔴 高 | **端到端冒烟验证** | 两服务启动 + CRM 测试库 + OPENAI_API_KEY |
| 🔴 高 | **Redis 向量索引重建**（如果之前灌过 DashScope embedding） | OPENAI_EMBEDDING_MODEL/text-embedding-v3 |
| 🟡 中 | 引 `spring-ai-alibaba-agent-framework` + `graph-core` | 确认 SAA 自动配置副作用（排除 spring-ai-deepseek/zhipuai 硬依赖） |
| 🟡 中 | 写第一个 Graph/Supervisor 用例（多 Agent 并行分析） | 上一步 |
| 🟡 中 | 审计日志（查询内容持久化） | 无 |
| 🟢 低 | 登录鉴权（多用户权限隔离） | 无 |
| 🟢 低 | metadata.yaml 热更新（重启生效 → 刷新 Bean） | 无 |
| 🟢 低 | 换模型/Embedding 运行时切换 | 配置抽象层 |

---

## 八、文档索引（交付物）

| 文档 | 读者 | 内容 |
|---|---|---|
| **本文件** | 项目经理 / 业务方 | 交付范围、能力清单、使用方式、已知限制 |
| `docs/ai-agent/BUSINESS_METRICS.md` | 产品经理 | 业务口径定义（有效订单、del_flag、门店编码优先等） |
| `docs/ai-agent/DATABASE_SCHEMA.md` | 开发 / DBA | CRM 核心表结构与字段说明 |
| `docs/ai-agent/PROJECT_CONTEXT.md` | 新开发同学 | 项目定位、技术背景、关键文件索引 |
| `docs/ai-agent/METADATA_SCHEMA.md` | 开发 / 运维 | metadata.yaml 完整字段表 + 加载期硬校验清单 + 维护 Checklist |
| `docs/ai-agent/METRICS_CONFIGURATION.md` | 开发 | 固化指标 YAML 配置规范与编译链路 |
| `docs/ai-agent/SUPPORTED_METRICS.md` | 产品 / 测试 | 已交付固化指标清单 + Ad-hoc 维度×度量组合示例 |
| `docs/ai-agent/plans/` | 开发 | 三份技术方案（Phase1 / Phase2 / Ad-hoc 实施计划） |
| `.qoder/rules/` | AI Agent / 开发协作 | 规约铁律（阿里规约层级、OpenAI 兼容端点配置、会话归属、踩坑矩阵） |

---

## 九、Git 提交摘要（`crm_agent_dev` 分支）

| Commit | 说明 |
|---|---|
| `c716422` | git rm --cached dump.rdb |
| `73bc412` | 删除 tmp-repro + 方案集中到 plans/ |
| `4190920` | 中文文件名规范化 + METADATA_SCHEMA.md |
| `d9c5b9b` | 模型切 Spring AI Alibaba DashScope 1.1.2.2 + multi-model: true |
| `f995bec` | 模型获取 Schema 链路写入技术方案 |
| `…` | 更早的 Phase 1/2 交付提交 |

---

**文档结束。有问题请找开发同学。**

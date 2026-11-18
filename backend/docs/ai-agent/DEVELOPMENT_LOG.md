# SpringAi-MCP-RAG-Dev 开发日志

## 记录规则

只记录实际发生的事实。没有执行的测试不能写成通过；没有确认的业务口径不能写成已确定。每次重要需求澄清、架构决策、代码修改或验证后更新本文件。

## 归档

- Phase 1-3 密集交付期（2026-10-27 ~ 2026-11-10，26 条）已归档 → `archive/DEVELOPMENT_LOG_2026-10~11.md`
- 关键架构决策已提炼进活文档（METHODOLOGY.md / CASE_STUDY.md / SECURITY.md）

---

## 2026-11-19：DEV.to 同行评审落地（两轮）+ GitHub Actions CI 修复链

### 背景

项目发布到 DEV.to（帖：https://dev.to/ying_li_2410bddf58e38117a/i-built-an-enterprise-crm-insight-agent-with-mcp-spring-ai-deterministic-text2sql-not-prompt-2ph8）后，Tom Zafred（Founder & Principal Systems Architect at Biomed Systems Limited UK，MCP 治理工具链作者）做了真实逐行评审，给了三个关键建议。

### 第一轮落地（commit f299dac → fdc8836）

1. **WildcardPositionCheck — LIKE 前导通配符拒绝**（Tom 建议）
   - 问题：`LIKE '%张三'` 绕开 BTREE 索引强制全表扫描，只允许后缀通配符（索引友好）
   - 实现：`AdhocRequestValidator` 新增 WildcardPositionCheck，前导 `%` 或 `_` 一律拒绝；后缀通配符（如 `'张三%'`）允许
   - 影响列：所有可过滤列，索引列白名单 `member_name`, `member_allot_staffName`
   - 测试：`AdhocRequestValidatorTest` 新增前导拒绝 / 后缀放行用例

2. **SyncResourceSpecification — set_level 编码移到 MCP Resource**（Tom 建议）
   - 问题：`@Tool description` 内联 50+ set_level 编码，`tool-definitions.json` 膨胀到 ~50KB
   - 实现：`ToolConfiguration.levelCodesResource()` 注册 `crm://levels/set_level-codes` MCP Resource，按需拉取
   - 效果：`tool-definitions.json` 从 **50KB → 8KB**
   - SDK 踩坑：MCP SDK 0.17.0 的 `Resource` 有 Builder（无参 `builder()` + setter），但 `ReadResourceResult` / `TextResourceContents` 是纯 Record 直接 `new`，不能 `.builder()`

### 第二轮落地（commit ba6c269）

3. **MetadataBootstrapValidator — INFORMATION_SCHEMA 自动推断 FK cardinality**（Tom 提供 SQL）
   - 问题：`metadata.yaml` 的 `relationships.cardinality` 是手工声明，人会错
   - Tom 给的 SQL：
     ```sql
     SELECT TABLE_NAME, INDEX_NAME, MIN(COLUMN_NAME) AS key_col
     FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND NON_UNIQUE = 0
     GROUP BY TABLE_NAME, INDEX_NAME
     HAVING COUNT(*) = 1;
     ```
   - 实现：启动期 CommandLineRunner 跑 SQL，缓存单列唯一索引列 Map，解析 YAML relationship 的 ON 条件推断 cardinality
   - **只证 to_one，不证 to_many**：JOIN 列有唯一索引 → 证实 to_one；没有唯一索引 → 返回 null 信任 YAML 声明（不打 WARN）
   - 真实 CRM schema 跑数：
     ```
     member↔order: 无法推断（JOIN 列无唯一索引），跳过
     member↔detail: 无法推断，跳过
     order↔member: 无法推断（member_mobile 是普通 INDEX 不是 UNIQUE），跳过
     detail↔member: 无法推断（member_mobile 是普通 INDEX 不是 UNIQUE），跳过
     校验完成：3 条已声明关系，0 条不一致（全部跳过 = 信任 YAML 声明）
     ```
   - detail→order 未声明：expense_orderId 只有普通 BTREE INDEX（复合索引含 expense_d_state 才唯一），暂走 adhocQueryBatch
   - 根因：member_mobile 业务上是一对一，但 DDL 里是普通 INDEX 不是 UNIQUE。CRM DBA 未授权执行 ALTER TABLE。
   - 设计选择：只做正向证实，不做反向证伪。宁可漏判 1 条，不误报 10 条。

### MCP SDK 0.17.0 API 踩坑

| 坑 | 根因 | 正确写法 |
|---|---|---|
| import 找不到 Resource/ReadResourceResult | 以为 Spring AI 有 MCP 类，实际委托给 MCP SDK | `import io.modelcontextprotocol.spec.McpSchema;` + `import io.modelcontextprotocol.server.McpServerFeatures;` |
| `Resource.builder("uri")` 传参报错 | SDK 0.17.0 Builder 无参 | `Resource.builder().uri("...").name("...").build()` |
| `ReadResourceResult.builder()` 不存在 | SDK 只给 Resource/Tool/Prompt 有 Builder，其余是 Record | `new McpSchema.ReadResourceResult(List.of(...))` |

### GitHub Actions CI 修复链（4 次迭代）

| # | 报错 | 修复 | Commit |
|---|---|---|---|
| ① | `Permission denied` / `/usr/bin/env: 'bash\r'` | CI workflow 加 `sed -i 's/\r$//' ./mvnw` + `chmod +x` | `3c44ada`, `f299dac` |
| ② | `List.of()` 推断 `List<String>` 不是 `List<String[]>` | `List.<String[]>of(...)` 显式类型参数 | `1f0cccb` |
| ③ | 测试 `expected: <to_many> but was: <null>` | 断言跟新逻辑对齐（只证 to_one 返回 null） | `dc2c890` |
| ④ | `ToolContractSnapshotTest` 契约基线 diff | `MetricsFixtures.writeBaseline()` 更新基线 | `8a5e4ac` |

### 修改文件

- **主代码**：`AdhocRequestValidator.WildcardPositionCheck`（L326-338）、`MetadataBootstrapValidator`（新类）、`ToolConfiguration.levelCodesResource()`
- **测试**：`AdhocRequestValidatorTest` +4（前导拒绝/后缀放行/方向组合）、`MetadataBootstrapValidatorTest`（新类）、`ToolContractSnapshotTest` 契约基线更新
- **契约**：`tool-definitions.json`（50KB → 8KB）
- **CI**：`.github/workflows/ci.yml`（sed + chmod + 显式类型）
- **文档**：`SECURITY.md`、`METHODOLOGY.md`（新增第 06 讲）、`README.md`（架构图 + MCP Resource 段）、`CASE_STUDY.md`（安全层 + 踩坑实录 6 行）

### 架构或技术决策

1. **MetadataBootstrapValidator 只证 to_one 不证 to_many**：SQL 能证实 DDL 有唯一索引（= to_one），但"没唯一索引"≠"确实是一对多"（可能 DDL 没声明但业务上是一对一）。信任 YAML 声明，避免假警报。
2. **不阻塞启动**：数据库没连上时跳过，打 WARN 即可。INFORMATION_SCHEMA 对 SQLite 等数据库也不适用。
3. **MCP Resource 替代内联描述**：大段静态参考表移到 Resource，Tool definitions 保持精简。LLM 按需拉取 Resource 内容，只在需要时才读。
4. **WildcardPositionCheck 服务端强制**：模型侧提示词约束最后都要服务端兜底（历史经验），前导通配符绕索引是典型慢 SQL 入口，硬拒绝。

### 测试与验证结果

- GitHub Actions 全绿（4 次 CI 迭代后 `tool-definitions.json` 契约基线更新完成）
- 本地全量编译通过：`mvnw -o -pl mcp-server test`
- MetadataBootstrapValidator 真实 CRM schema 跑数：3 条已声明关系全部跳过（member_mobile 无 UNIQUE）、0 条不一致；detail→order 未声明，暂走 adhocQueryBatch

### 未解决问题

1. **CRM DDL 历史遗留**：`member_mobile` 业务上是一对一但 DDL 没 UNIQUE 约束（是普通 BTREE INDEX，见 DDL L278）。需 CRM DBA 确认是否加 `ALTER TABLE member_info ADD UNIQUE INDEX ...`（未授权不动）。
2. **detail→order cardinality**：`expense_orderId` 无单列 UNIQUE（复合索引含 `expense_d_state`），Tom 的 INFORMATION_SCHEMA SQL 只抓单列唯一索引。暂不声明 relationship，三表组合走 `adhocQueryBatch`。Tom Nov 19 建议配 `INFORMATION_SCHEMA.KEY_COLUMN_USAGE` 补 FK 边推断复合索引场景，待评估。
3. **Tom Zafred RFC-001 workbench-guard 集成**：3-Zoom FK subgraphs 与 AdhocSqlCompiler 的 cross-table grain validation 对接，待 Tom 回应 validateMeasureGrain() 代码对比请求。

### 下一步

1. ~~DEV.to 回复 Tom Zafred~~ ✅ 已回复（附真实跑数 + PR-level issues 合并 + workbench-guard RFC-001 star）
2. ~~GitHub Issue #1-3 跟进~~ ✅ 全部关闭（#1 Wildcard / #2 SyncResource / #3 MetadataBootstrapValidator）
3. ~~等 HN Show HN 解禁 + 发布~~ — 继续等
4. **回应 Tom Nov 19 追问**：dev.to 追加一条（Issues 已关闭 + member_mobile DDL 无 UNIQUE 真相澄清 + KEY_COLUMN_USAGE 配对主动对接 + PR 欢迎）
5. **RFC-001 workbench-guard 集成**：Tom 提了 3-Zoom FK subgraphs，与 validateMeasureGrain() 对比后考虑集成
6. **DEV.to 帖子 Edit**：加 UPDATE: Nov 19 — Peer Review Lands 段（架构图 + Tech Stack + What's next）

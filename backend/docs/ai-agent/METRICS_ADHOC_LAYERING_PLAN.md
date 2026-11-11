# 指标即配置与 Ad-hoc 分层实施基线

> 文档性质：**方案基线，已实施完成**。章节一至十章保留原方案技术结论；实际实施状态、验收结果与差异记录见文末「实施结果」。
> 适用模块：`mcp-server`
> 使用方式：后续重构按 B0 → B5 逐批实施，每批完成后对照本文逐项比对；未经评审不得调整批次顺序与范围。
> 基线日期：2026-11-07

## 实施状态登记

| 批次 | 内容 | 状态 |
|---|---|---|
| B0 | 契约快照与特征化测试 | 已完成 |
| B1 | DTO 与异常归位（含 adhoc/model 拆分） | 已完成 |
| B2 | 抽取 shared 共享层 | 已完成 |
| B3 | 抽取目录服务 + service 归位 | 已完成 |
| B4 | 类归位、改名与常量抽取 | 已完成 |
| B5 | Loader / Configuration 拆分 + 注解式 Bean | 已完成 |

---

## 一、现状与目标

### 1.1 现状

- `com.itzixi.metrics`（15 个类）把配置加载、解析、启动期校验、运行期校验、注册表、SQL 编译、SQL 执行、只读守卫、全局常量、派生计算、异常、查询服务、开发期工具、DTO 混在同一层。`SqlExecutor`、`ColumnTypeRegistry`、`GeneratedSql` 实为跨域基础设施，却归属指标域。
- `com.itzixi.adhoc`（12 个类）把注册表配置与 Bean 装配混在一个配置类；`model` 包同时放请求、响应、规格；`AdhocValidator` 内含 261 个保留字常量与六类校验规则。
- `mcp.tool` 中 `AdhocTool` 直接注入 `SemanticRegistry`、`ColumnTypeRegistry` 并自行组装目录输出；`GuessTool` 直接注入 Mapper（Phase 1 历史债）。
- 指标域 DTO 放在 `com.itzixi.entity.request/response`，与 Phase 1 遗留 DTO 混放，形成指标域对通用实体包的反向依赖。
- `SqlExecutor`、`ColumnTypeRegistry` 的 Bean 声明在 `MetricRegistryConfiguration`，adhoc 的运行期依赖由指标域配置提供，配置层跨域耦合。

### 1.2 目标

1. 按职责收口：请求进 `request`、出参进 `response`、领域模型进 `model`、校验进 `validate`、编译进 `compile`、注册表进 `registry`、装配进 `config`、共享设施进 `shared`。
2. 依赖方向单向：`mcp.tool → 域 service`，`域 service → 本域内部`，`shared → 不依赖任何域`。
3. 指标域与 adhoc 域互不 import，只能共同依赖 `com.itzixi.shared`。
4. 新人打开任一包即可判断文件职责；重构过程零行为变化、零模型契约变化。

---

## 二、目标包结构

```text
com.itzixi
├── McpServerApplication
├── mcp
│   ├── tool                  # MCP 适配层：@Tool 入参 → 域 service → 出参/DTO
│   └── config                # ToolConfiguration（工具注册，不含业务逻辑）
├── prompt                    # ToolPromptSnippets（工具描述文案，跨域共用）
├── metrics                   # 指标即配置域
│   ├── config                # MetricRegistryLoader（YAML 加载/校验）、MetricConfiguration（仅 Bean）
│   ├── model                 # MetricDefinition、QueryPlan、Status/Grain 枚举
│   ├── request               # MetricQueryRequest、MetricDraftRequest（含嵌套 Spec）、FilterItem
│   ├── response              # MetricResult、ColumnMeta
│   ├── registry              # MetricRegistry、MetricDomains
│   ├── validate              # MetricConfigValidator（启动期）、MetricQueryValidator（运行期）
│   ├── compile               # MetricSqlCompiler
│   ├── service               # MetricQueryService、MetricCatalogService
│   ├── tooling               # MetricDrafter（仅开发期）
│   └── exception             # MetricException
├── adhoc                     # 自助查询域
│   ├── config                # SemanticCatalogLoader（YAML 加载）、AdhocConfiguration（仅 Bean）
│   ├── model                 # SemanticRegistry 及其嵌套 record
│   ├── request               # AdhocQueryRequest、AdhocBatchRequest、MeasureSpec、FilterSpec、TimeRangeSpec
│   ├── response              # AdhocResult
│   ├── registry              # （可选）SemanticRegistry 的 lookup 拆分位，B6 再评估
│   ├── validate              # AdhocRequestValidator
│   ├── compile               # AdhocSqlCompiler
│   ├── service               # AdhocQueryService、SemanticCatalogService
│   └── exception             # AdhocException
├── shared                    # 跨域基础设施（禁止再建 shared/util）
│   ├── sql                   # SqlExecutor、ReadOnlySqlGuard、GeneratedSql、MysqlReservedWords
│   ├── catalog               # ColumnTypeRegistry、ColumnTypesLoader
│   ├── calc                  # DerivedCalculator
│   ├── limits                # ExecutionLimits、QueryLimits
│   └── config                # AnalyticsInfrastructureConfiguration
├── entity                    # 只放 MyBatis 行实体
│   ├── request               # Phase 1 遗留 DTO（迁移期保留，不再新增）
│   └── response
├── mapper                    # Phase 1 遗留 Mapper（不动）
└── service                   # Phase 1 遗留 Service（不动）
```

### 2.1 各包职责边界

| 包 | 职责 | 允许依赖 | 禁止依赖 |
|---|---|---|---|
| `mcp.tool` | 协议适配、参数转换、错误码转述 | 各域 `service`、`request`、`response`、`prompt` | `compile`、`validate`、`registry`、`shared.sql`、Mapper |
| `mcp.config` | 工具对象注册 | 工具类 | 业务规则 |
| `metrics.service` / `adhoc.service` | 用例编排、事务外的业务顺序 | 本域 `validate`、`compile`、`registry`、`model`、`request`、`response`、`shared` | 另一域内部实现、`mcp` |
| `*.compile` | 确定性 SQL 生成 | 本域 `model`、`shared.sql` | `service`、`registry` |
| `*.validate` | 纯校验，无 IO | 本域 `model`、`shared.catalog`、`shared.limits` | `compile`、`service` |
| `*.registry` | 不可变内存查询表 | 本域 `model`、`shared` | `service`、`compile` |
| `*.config` | 唯一允许跨域装配的位置 | 任意 | 业务规则、SQL 拼接 |
| `shared.*` | 两域共用基础设施 | JDK / Spring / 第三方 | `metrics.*`、`adhoc.*`、`mcp.*` |
| `entity` / `mapper` / `service`(legacy) | Phase 1 遗留 | MyBatis | 新域代码反向依赖 |

---

## 三、三条强制依赖规则

1. **工具层只能依赖各域 service 与 request/response。**
   `mcp.tool` 不得 import `*.compile`、`*.validate`、`*.registry`、`shared.sql`；目录组装必须由 `MetricCatalogService` / `SemanticCatalogService` 提供，不得在 Tool 内自行读注册表。
2. **adhoc 不得 import `com.itzixi.metrics`（反向同样禁止）。**
   两域只能共同依赖 `com.itzixi.shared`。B2 完成后，`GeneratedSql`、`SqlExecutor`、`ColumnTypeRegistry`、`DerivedCalculator`、通用上限常量都必须位于 `shared`，否则视为违规。
3. **每个域一个 Configuration 只声明 Bean，加载逻辑放 Loader，资源路径保持不变。**
   - `MetricConfiguration` / `AdhocConfiguration` 只做 Bean 装配。
   - `MetricRegistryLoader` / `SemanticCatalogLoader` / `ColumnTypesLoader` 负责读取 YAML、解析、启动期校验。
   - 资源路径保持现状：`resources/metrics/*.yaml`、`resources/metrics/column-types.yaml`、`resources/semantic/metadata.yaml`，本方案不改名、不移动。

---

## 四、文件级迁移清单（B0–B5）

> 说明：B1 在原批次名基础上包含指标域 DTO 归位；B3 包含 `MetricDomains` 前置类。两处均为闭环所必需，未新增批次。

### B0 特征化测试（改结构之前必须先做）

| 新增测试类（目标 FQN） | 职责 | 依赖 |
|---|---|---|
| `com.itzixi.mcp.tool.ToolContractSnapshotTest` | 用 `MethodToolCallbackProvider` 构建全部工具，快照 `name` / `description` / `inputSchema` 规范 JSON | `MetricsFixtures`、`AdhocFixtures`、Mockito |
| `com.itzixi.metrics.MetricCatalogContractTest` | 快照 `catalog(domain)` 输出的键集合与层级结构 | `MetricsFixtures` |
| `com.itzixi.adhoc.SemanticCatalogContractTest` | 通过 `AdhocTool.listSemanticCatalog()` 快照目录 JSON，含条件键出现/不出现行为 | `AdhocFixtures` |
| `com.itzixi.metrics.SqlGoldenSnapshotTest` | 2–3 个代表请求的完整 SQL 字符串快照 | 复用 `SqlCompilerTest` 用例 |

- 新增类：以上 4 个测试类（仅测试代码）。
- 新增非 Java 文件：见第五章四个基线文件。
- 纯移动：不适用。
- 波及测试：无既有类改动；在 `MetricsFixtures`、`AdhocFixtures` 中补「构建全部工具对象」helper。
- 注意：B3 修改 `AdhocTool`/`MetricTool` 构造器后，本批测试的 setup 需同步修改，但快照断言不得变化。
- 验收命令：

```powershell
$env:JAVA_HOME='C:\Users\LAN\.jdks\corretto-21.0.13'
.\mvnw.cmd -Pdev -pl mcp-server -am test '-Dprofiles.active=' '-Dtest=ToolContractSnapshotTest,MetricCatalogContractTest,SemanticCatalogContractTest,SqlGoldenSnapshotTest' '-Dsurefire.failIfNoSpecifiedTests=false'
.\mvnw.cmd -Pdev -pl mcp-server -am test '-Dprofiles.active='
```

### B1 DTO 与异常归位（含 adhoc/model 拆分）

| 当前全限定名 | 目标全限定名 | 纯移动 | 备注 |
|---|---|---|---|
| `com.itzixi.adhoc.model.AdhocQueryRequest` | `com.itzixi.adhoc.request.AdhocQueryRequest` | 是 | 工具入参 |
| `com.itzixi.adhoc.model.AdhocBatchRequest` | `com.itzixi.adhoc.request.AdhocBatchRequest` | 是 | 工具入参 |
| `com.itzixi.adhoc.model.MeasureSpec` | `com.itzixi.adhoc.request.MeasureSpec` | 是 | 请求内嵌规格 |
| `com.itzixi.adhoc.model.FilterSpec` | `com.itzixi.adhoc.request.FilterSpec` | 是 | 请求内嵌规格 |
| `com.itzixi.adhoc.model.TimeRangeSpec` | `com.itzixi.adhoc.request.TimeRangeSpec` | 是 | 请求内嵌规格 |
| `com.itzixi.adhoc.model.AdhocResult` | `com.itzixi.adhoc.response.AdhocResult` | 是 | 工具出参 |
| `com.itzixi.adhoc.AdhocException` | `com.itzixi.adhoc.exception.AdhocException` | 是 | 域异常 |
| `com.itzixi.entity.request.MetricQueryRequest` | `com.itzixi.metrics.request.MetricQueryRequest` | 是 | 指标工具入参 |
| `com.itzixi.entity.request.MetricDraftRequest` | `com.itzixi.metrics.request.MetricDraftRequest` | 是 | 含 4 个嵌套 Spec，随外层整体移动 |
| `com.itzixi.entity.request.FilterItem` | `com.itzixi.metrics.request.FilterItem` | 是 | Phase 1 工具改为引用 metrics.request |
| `com.itzixi.entity.response.MetricResult` | `com.itzixi.metrics.response.MetricResult` | 是 | 指标出参 |
| `com.itzixi.entity.response.ColumnMeta` | `com.itzixi.metrics.response.ColumnMeta` | 是 | 指标列元数据 |

- 新增类：无。
- 非 Java 文件：无。Phase 1 的 `MemberOverviewRequest/Result`、`ProductRankingRequest/Result`、`StationPerformanceRequest/Result` 留在 `entity.*`。
- 波及测试（仅 import 变化）：
  - `com.itzixi.adhoc.AdhocReconciliationIT`
  - `com.itzixi.adhoc.AdhocToolTest`
  - `com.itzixi.adhoc.AdhocValidatorTest`
  - `com.itzixi.adhoc.AdhocSqlCompilerTest`
  - `com.itzixi.adhoc.AdhocQueryServiceTest`
  - `com.itzixi.metrics.VipMetricsReconciliationTest`
  - `com.itzixi.metrics.Phase2MetricsReconciliationTest`
  - `com.itzixi.metrics.QueryPlanDefaultWindowTest`
  - `com.itzixi.metrics.MetricsAsCodeMigrationReconciliationTest`
  - `com.itzixi.metrics.MetricDrafterTest`
  - `com.itzixi.mcp.tool.MetricToolInjectionTest`
  - 主代码：`CrmAnalyticsTool`、`MetricTool`、`AdhocTool`、`MetricQueryService`、`QueryPlanValidator`、`MetricDrafter`
- 纯移动：**整批可纯移动**，IDE Move + Optimize Imports 即可，无 Spring 注解与包内可见性依赖。
- 验收命令：

```powershell
$env:JAVA_HOME='C:\Users\LAN\.jdks\corretto-21.0.13'
.\mvnw.cmd -Pdev -pl mcp-server -am test '-Dprofiles.active=' '-Dtest=AdhocValidatorTest,AdhocSqlCompilerTest,AdhocToolTest,AdhocQueryServiceTest,AdhocReconciliationIT,MetricToolInjectionTest,MetricDrafterTest,QueryPlanDefaultWindowTest,Phase2MetricsReconciliationTest,VipMetricsReconciliationTest' '-Dsurefire.failIfNoSpecifiedTests=false'
.\mvnw.cmd -Pdev -pl mcp-server -am test '-Dprofiles.active='
```

### B2 抽取 shared 共享层

| 当前全限定名 | 目标全限定名 | 纯移动 | 备注 |
|---|---|---|---|
| `com.itzixi.metrics.GeneratedSql` | `com.itzixi.shared.sql.GeneratedSql` | 是 | 两域共用编译产物 |
| `com.itzixi.metrics.SqlExecutor` | `com.itzixi.shared.sql.SqlExecutor` | 是 | 两域共用执行器 |
| `com.itzixi.metrics.ReadOnlySqlGuard` | `com.itzixi.shared.sql.ReadOnlySqlGuard` | 是 | 执行层只读闸门 |
| `com.itzixi.metrics.ColumnTypeRegistry` | `com.itzixi.shared.catalog.ColumnTypeRegistry` | 是 | 两域共用列类型/PII 登记 |
| `com.itzixi.metrics.DerivedCalculator` | `com.itzixi.shared.calc.DerivedCalculator` | 是 | 两域共用派生计算 |
| `com.itzixi.metrics.MetricsLimits` | `com.itzixi.shared.limits.MetricsLimits` | 是 | 本批只移动，B4 再拆分 |

新增类：

| 新增类 | 职责 | 拆出位置 |
|---|---|---|
| `com.itzixi.shared.config.AnalyticsInfrastructureConfiguration` | 声明 `columnTypeRegistry()`、`sqlExecutor(DataSource)` 两个 Bean，方法名/参数/行为与旧实现逐字一致 | `MetricRegistryConfiguration` 的同名 `@Bean` |

- 非 Java 文件：**无文件移动**。`resources/metrics/column-types.yaml` 路径不变；`COLUMN_TYPES_LOCATION` / `COLUMN_TYPES_FILE` 常量随 Bean 方法迁入新配置类（仍为 Java 常量）。
- Spring 变更：从 `MetricRegistryConfiguration` 删除 `columnTypeRegistry()`、`sqlExecutor()` 两个 `@Bean` 及对应常量；`metricRegistry`、`sqlCompiler` 两个 Bean 保留至 B5。
- 波及测试：
  - `com.itzixi.metrics.MetricsFixtures`：`new MetricRegistryConfiguration().columnTypeRegistry()` 改为新配置类/加载器
  - `com.itzixi.adhoc.AdhocFixtures`：同上
  - `com.itzixi.mcp.tool.MetricToolInjectionTest`（`SqlExecutor` import）
  - `com.itzixi.mcp.tool.CrmAnalyticsToolTest`（`SqlExecutor` import）
  - `com.itzixi.metrics.ReadOnlySqlGuardTest`（`SqlExecutor.JdbcSqlExecutor` import）
  - `com.itzixi.adhoc.AdhocReconciliationIT`、`AdhocToolTest`、`AdhocValidatorTest`、`AdhocSqlCompilerTest`、`AdhocQueryServiceTest`、`SemanticRegistryTest`（`ColumnTypeRegistry` / `GeneratedSql` import）
- 纯移动：**否**。类移动是纯的，但 `@Bean` 迁移必须与删除原子执行，否则出现重复 Bean（见第六章）。
- 验收命令：

```powershell
$env:JAVA_HOME='C:\Users\LAN\.jdks\corretto-21.0.13'
.\mvnw.cmd -Pdev -pl mcp-server -am test '-Dprofiles.active=' '-Dtest=ReadOnlySqlGuardTest,ToolContractSnapshotTest,MetricCatalogContractTest,SemanticCatalogContractTest,AdhocReconciliationIT' '-Dsurefire.failIfNoSpecifiedTests=false'
.\mvnw.cmd -Pdev -pl mcp-server -am test '-Dprofiles.active='
```

### B3 抽取目录服务 + service 归位

新增类：

| 新增类 | 职责 | 拆出位置 |
|---|---|---|
| `com.itzixi.metrics.registry.MetricDomains` | `normalizeDomain()` + `ALLOWED_DOMAINS`，供目录服务与查询服务共用 | **前置**：从 `MetricRegistryConfiguration.normalizeDomain()`（当前包内可见）与 `MetricsLimits.ALLOWED_DOMAINS` 移出。不做此步，`MetricQueryService` 移动后无法编译 |
| `com.itzixi.metrics.service.MetricCatalogService` | `catalog(domain)` + `columnHint(column)`，仅依赖 `MetricRegistry`、`MetricDomains` | 从 `MetricQueryService` 拆分 |
| `com.itzixi.adhoc.service.SemanticCatalogService` | 组装 `listSemanticCatalog` 输出（pii/indexed/guard/relationships） | 从 `AdhocTool.listSemanticCatalog()` 拆出 |

移动类：

| 当前全限定名 | 目标全限定名 | 纯移动 |
|---|---|---|
| `com.itzixi.metrics.MetricQueryService` | `com.itzixi.metrics.service.MetricQueryService` | 是（前提：`MetricDomains` 已落地） |
| `com.itzixi.adhoc.AdhocQueryService` | `com.itzixi.adhoc.service.AdhocQueryService` | 是 |

修改类（非移动）：

| 类 | 变化 |
|---|---|
| `com.itzixi.mcp.tool.AdhocTool` | 构造器 `(AdhocQueryService, SemanticRegistry, ColumnTypeRegistry)` → `(AdhocQueryService, SemanticCatalogService)`；目录方法改为一行委托 |
| `com.itzixi.mcp.tool.MetricTool` | 构造器新增 `MetricCatalogService`；`listMetrics` 改为委托；异常捕获仍为 `MetricException` |
| `com.itzixi.metrics.MetricQueryService` | 删除 `catalog()` / `columnHint()`；查询与干跑链路不变 |

- 非 Java 文件：无。`ToolConfiguration` 不新增工具、不改注册列表。
- 波及测试：
  - `com.itzixi.adhoc.AdhocToolTest`：构造器 3→2；目录断言改为经服务调用，输出必须逐键不变
  - `com.itzixi.mcp.tool.MetricToolInjectionTest`：构造器新增目录服务；`MetricsFixtures` 增加 `catalogService()` helper
  - `com.itzixi.mcp.tool.CrmAnalyticsToolTest`、`com.itzixi.adhoc.AdhocReconciliationIT`：`MetricQueryService` 包名 import
  - 新增 `com.itzixi.metrics.MetricCatalogServiceTest`、`com.itzixi.adhoc.SemanticCatalogServiceTest`（复用 B0 快照）
- 纯移动：**否**。含逻辑抽取与构造器变更；`MetricQueryService` 移动本身可视为纯移动，但必须先完成 `MetricDomains`。
- 验收命令：

```powershell
$env:JAVA_HOME='C:\Users\LAN\.jdks\corretto-21.0.13'
.\mvnw.cmd -Pdev -pl mcp-server -am test '-Dprofiles.active=' '-Dtest=AdhocToolTest,MetricToolInjectionTest,MetricCatalogServiceTest,SemanticCatalogServiceTest,ToolContractSnapshotTest' '-Dsurefire.failIfNoSpecifiedTests=false'
.\mvnw.cmd -Pdev -pl mcp-server -am test '-Dprofiles.active='
```

### B4 类归位、改名与常量抽取

| 当前全限定名 | 目标全限定名 | 类型 | 纯移动 |
|---|---|---|---|
| `com.itzixi.metrics.SqlCompiler` | `com.itzixi.metrics.compile.MetricSqlCompiler` | 改名 + 移动 | 是（IDE Rename/Move） |
| `com.itzixi.metrics.QueryPlanValidator` | `com.itzixi.metrics.validate.MetricQueryValidator` | 改名 + 移动 | 是 |
| `com.itzixi.metrics.MetricDefinitionValidator` | `com.itzixi.metrics.validate.MetricConfigValidator` | 改名 + 移动 | 是 |
| `com.itzixi.metrics.MetricRegistry` | `com.itzixi.metrics.registry.MetricRegistry` | 移动 | 是 |
| `com.itzixi.metrics.MetricException` | `com.itzixi.metrics.exception.MetricException` | 移动 | 是 |
| `com.itzixi.metrics.MetricDrafter` | `com.itzixi.metrics.tooling.MetricDrafter` | 移动 | 是 |
| `com.itzixi.adhoc.AdhocValidator` | `com.itzixi.adhoc.validate.AdhocRequestValidator` | 改名 + 移动 | 是 |
| `com.itzixi.adhoc.AdhocSqlCompiler` | `com.itzixi.adhoc.compile.AdhocSqlCompiler` | 移动 | 是 |
| `com.itzixi.adhoc.SemanticRegistry` | `com.itzixi.adhoc.model.SemanticRegistry` | 移动 | 是 |

新增 / 拆分：

| 新增类 | 职责 | 来源 |
|---|---|---|
| `com.itzixi.shared.sql.MysqlReservedWords` | 261 个 MySQL 8.0 保留字集合 | 从 `AdhocValidator.MYSQL_RESERVED_WORDS` 抽出 |
| `com.itzixi.shared.limits.ExecutionLimits` | `EXEC_MAX_ROWS`、`QUERY_TIMEOUT_SECONDS`、`MAX_EXECUTION_TIME_MS`、`DATE_PATTERN` | 拆分 `shared.limits.MetricsLimits` |
| `com.itzixi.shared.limits.QueryLimits` | `MAX_RANGE_DAYS`、`DEFAULT_RANGE_MONTHS`、`MAX_DEFAULT_WINDOW_MONTHS`、`HARD_MAX_ROWS`、`SORT_ASC/DESC` | 拆分同上；删除 `MetricsLimits` |

- 非 Java 文件：无。
- 编译断点：见第七章 `AdhocSqlCompiler.orEmpty()`。
- 波及测试：
  - `com.itzixi.metrics.SqlCompilerTest` → **改名** `com.itzixi.metrics.MetricSqlCompilerTest`（类名与文件名同步，`new MetricSqlCompiler()`）
  - `com.itzixi.metrics.MetricDefinitionValidatorTest`（import 改名）
  - `com.itzixi.metrics.QueryPlanDefaultWindowTest`（`MetricQueryValidator.normalize`）
  - `com.itzixi.metrics.MetricsFixtures`（`MetricRegistry`、`MetricSqlCompiler`）
  - `com.itzixi.mcp.tool.MetricToolInjectionTest`（`MetricRegistry`、`MetricSqlCompiler`）
  - `com.itzixi.adhoc.AdhocValidatorTest` → **改名** `com.itzixi.adhoc.AdhocRequestValidatorTest`
  - `com.itzixi.adhoc.AdhocSqlCompilerTest`、`AdhocToolTest`、`AdhocQueryServiceTest`（import）
  - `com.itzixi.adhoc.SemanticRegistryTest`（import）
- 纯移动：**大部分是**；唯一非纯点是 `orEmpty` 断点处理。测试改名属 IDE Rename，不改断言。
- 验收命令：

```powershell
$env:JAVA_HOME='C:\Users\LAN\.jdks\corretto-21.0.13'
.\mvnw.cmd -Pdev -pl mcp-server -am test '-Dprofiles.active=' '-Dtest=MetricSqlCompilerTest,MetricDefinitionValidatorTest,QueryPlanDefaultWindowTest,AdhocRequestValidatorTest,AdhocSqlCompilerTest,SemanticRegistryTest,MetricToolInjectionTest' '-Dsurefire.failIfNoSpecifiedTests=false'
.\mvnw.cmd -Pdev -pl mcp-server -am test '-Dprofiles.active='
```

### B5 Loader / Configuration 拆分 + 注解式 Bean

| 当前全限定名 | 目标全限定名 | 处理 |
|---|---|---|
| `com.itzixi.metrics.MetricRegistryConfiguration` | `com.itzixi.metrics.config.MetricRegistryLoader` + `com.itzixi.metrics.config.MetricConfiguration` | 拆分后删除原类 |
| `com.itzixi.metrics.MetricDefinitionParser` | `com.itzixi.metrics.config.MetricDefinitionParser` | 移动（Loader 内部使用） |
| `com.itzixi.adhoc.AdhocRegistryConfiguration` | `com.itzixi.adhoc.config.SemanticCatalogLoader` + `com.itzixi.adhoc.config.AdhocConfiguration` | 拆分后删除原类 |
| `com.itzixi.shared.config.AnalyticsInfrastructureConfiguration` | 保持不变 | 解析逻辑外移至 `ColumnTypesLoader` |

新增类：

| 新增类 | 职责 | 拆出位置 |
|---|---|---|
| `com.itzixi.metrics.config.MetricRegistryLoader` | 读 `classpath*:/metrics/*.yaml`、解析、校验、构建 `MetricRegistry`；启动失败语义与日志不变 | `MetricRegistryConfiguration` |
| `com.itzixi.metrics.config.MetricConfiguration` | 仅声明 `MetricRegistry`、`MetricSqlCompiler` 两个 Bean | 同上 |
| `com.itzixi.adhoc.config.SemanticCatalogLoader` | 读 `semantic/metadata.yaml`，构建 `SemanticRegistry` | `AdhocRegistryConfiguration` |
| `com.itzixi.adhoc.config.AdhocConfiguration` | 仅声明 `SemanticRegistry` Bean | 同上 |
| `com.itzixi.shared.catalog.ColumnTypesLoader` | 读 `metrics/column-types.yaml`，解析为 `ColumnTypeRegistry` | `AnalyticsInfrastructureConfiguration` |

注解式改造：

| 类 | 新增注解 | 同时删除 |
|---|---|---|
| `com.itzixi.adhoc.validate.AdhocRequestValidator` | `@Component` | `AdhocRegistryConfiguration#adhocValidator` |
| `com.itzixi.adhoc.compile.AdhocSqlCompiler` | `@Component` | `#adhocSqlCompiler` |
| `com.itzixi.adhoc.service.AdhocQueryService` | `@Service` | `#adhocQueryService` |
| `com.itzixi.adhoc.service.SemanticCatalogService` | `@Service`（若 B3 未加） | 无 |
| `com.itzixi.metrics.service.MetricCatalogService` | `@Service`（B3 已加则不变） | 无 |

- 非 Java 文件：**均无改动**。`application*.yml` 不新增配置；`@MapperScan("com.itzixi.mapper")` 不动；`resources` 下 YAML 路径不动；`ToolConfiguration#toolObjects` 不动。
- 波及测试：
  - `com.itzixi.metrics.MetricsFixtures`：`new MetricRegistryConfiguration()` → `new MetricRegistryLoader(...)`
  - `com.itzixi.adhoc.AdhocFixtures`：列类型加载改走 `ColumnTypesLoader` / `AnalyticsInfrastructureConfiguration`
  - 新增（建议）`com.itzixi.metrics.MetricRegistryLoaderTest`、`com.itzixi.adhoc.SemanticCatalogLoaderTest`：验证加载数量、未确认指标数、错误文案与拆分前一致
  - 上下文类测试：`AdhocReconciliationIT`、`MemberInfoMapperIntegrationTest`、`CrmAnalyticsMapperIntegrationTest` 必须重跑
- 纯移动：**否**。拆分 + 注解化 + 删除 `@Bean` 必须同一变更集，否则重复/缺失 Bean。
- 验收命令：

```powershell
$env:JAVA_HOME='C:\Users\LAN\.jdks\corretto-21.0.13'
.\mvnw.cmd -Pdev -pl mcp-server -am test '-Dprofiles.active=' '-Dtest=MetricRegistryLoaderTest,SemanticCatalogLoaderTest,AdhocReconciliationIT,MemberInfoMapperIntegrationTest,CrmAnalyticsMapperIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false'
.\mvnw.cmd -Pdev -pl mcp-server -am test '-Dprofiles.active='
```

---

## 五、B0 契约快照基线

四个基线文件（新增，均放 `mcp-server/src/test/resources/contract/`）：

| 基线文件 | 内容 | 生成方 | 断言方 |
|---|---|---|---|
| `tool-definitions.json` | 6 个工具的 `name` / `description` / `inputSchema` 规范 JSON | `ToolContractSnapshotTest` | 同左 |
| `catalog-metric.json` | `catalog(domain)` 的键集合与层级结构 | `MetricCatalogContractTest` | 同左 |
| `catalog-adhoc.json` | `listSemanticCatalog` 输出，含 `filterGuidance`/`queryGuard` 的有无行为 | `SemanticCatalogContractTest` | 同左 |
| `sql-golden.json` | 2–3 个代表请求的完整 SQL 字符串 | `SqlGoldenSnapshotTest` | 同左 |

比较规则：

1. JSON 以「键排序后的规范形式」比较，键顺序不敏感。
2. `inputSchema` 必须逐字段比对，不只看工具名。
3. 目录输出必须逐键比对：条件键「不应出现时不得出现」，布尔字段（`pii`/`indexed`）不得翻转。
4. 每批实施后重新运行四个测试，diff 必须为空；若为空则说明重构未改变模型契约。

---

## 六、Bean 迁移风险与原子切换

### 6.1 现状声明位置

| Bean | 当前声明 | 消费者 |
|---|---|---|
| `columnTypeRegistry` | `MetricRegistryConfiguration#columnTypeRegistry()` | `MetricRegistry`、`SemanticRegistry`、Validators、Compilers、B3 前的 `AdhocTool` |
| `sqlExecutor` | `MetricRegistryConfiguration#sqlExecutor(DataSource)` | `MetricQueryService`、`AdhocQueryService` |
| `semanticRegistry` | `AdhocRegistryConfiguration#semanticRegistry(ColumnTypeRegistry)` | `AdhocValidator`、`AdhocSqlCompiler`、B3 前的 `AdhocTool` |
| `adhocValidator` / `adhocSqlCompiler` / `adhocQueryService` | `AdhocRegistryConfiguration` 三个 `@Bean` | 手工装配链 |
| `metricRegistry` / `sqlCompiler` | `MetricRegistryConfiguration` 其余两个 `@Bean` | Service / Tool / 测试 |

### 6.2 新旧并存的具体表现

1. **同名 Bean 并存**：Spring Boot 默认关闭 Bean 覆盖，启动抛 `BeanDefinitionOverrideException`，服务无法启动。
2. **不同名但同类型并存**：例如旧 `adhocValidator` 与 `@Component` 生成的 `adhocRequestValidator`，注入点按类型解析，抛 `NoUniqueBeanDefinitionException`；参数名不参与匹配。
3. **新配置未被扫描**：配置类若脱离 `com.itzixi` 根包，抛 `NoSuchBeanDefinitionException`。
4. **测试不报、启动才报**：单元测试手工 `new` 不经过 Spring 容器，只有 `@SpringBootTest` / IT 会暴露。因此 B2、B5 以全量测试作为门槛。

### 6.3 原子切换做法（强制）

1. 同一变更集内完成「新配置加入 + 旧 `@Bean` 删除」，不保留过渡期。
2. 不复用 `spring.main.allow-bean-definition-overriding=true`，不使用 `@Primary` 掩盖冲突。
3. Bean 方法名与旧名保持完全一致（`columnTypeRegistry`、`sqlExecutor`），降低按名注入的隐性依赖。
4. B5 注解化时，删除 `@Bean` 与启用 `@Component`/`@Service` 视为同一操作，不得分成两次提交。
5. 切换后立即执行：上下文启动测试 + `AdhocReconciliationIT` + 两个 Mapper IT；再跑全量单测。

---

## 七、B4 编译断点：`AdhocSqlCompiler.orEmpty()`

- 现状：`orEmpty()` 是 `AdhocSqlCompiler` 的**包内可见静态方法**，`AdhocValidator` 与其同包，直接调用。
- B4 后：`AdhocValidator` → `com.itzixi.adhoc.validate.AdhocRequestValidator`，`AdhocSqlCompiler` → `com.itzixi.adhoc.compile.AdhocSqlCompiler`，跨包后包内可见性失效，**编译失败**。
- 推荐处理：在 `AdhocRequestValidator` 内复制 3 行私有 `orEmpty` helper，删除对编译器的跨类调用。理由：不为一次调用扩大编译器公开 API，也不新增 `shared/util` 兜底包。
- 备选（不推荐）：把 `orEmpty` 改为 `public static`。会使编译器承担与 SQL 生成无关的列表空值语义，扩大 API 面。
- 同类断点提醒（B3）：`MetricRegistryConfiguration.normalizeDomain()` 当前为包内可见，`MetricQueryService` 移包后会编译失败。因此 B3 必须先落地 `MetricDomains`，再移动 `MetricQueryService`。

---

## 八、模型契约影响评估

| 变更 | 是否影响契约 | 说明与验证 |
|---|---|---|
| B1 移动 `*Request` / `*Response` | 理论无影响 | Spring AI 从方法签名 + `@ToolParam` 生成 schema，不使用类 FQN；Jackson 使用属性名。验证：`tool-definitions.json` diff 为空 |
| B1 移动 `MetricDraftRequest` 及嵌套类 | 理论无影响 | schema 的 `$defs` 使用简单类名，包名不进入 schema |
| B1 移动 `FilterItem` | 理论无影响 | 属性名 `filters` 不变 |
| B3 `AdhocTool` / `MetricTool` 构造器变化 | 无影响 | 构造器不参与工具描述；工具名/描述/入参 schema 由 `@Tool` 方法与参数决定 |
| B3 目录输出改由 Service 组装 | **有实质风险** | `listSemanticCatalog` 返回 `Map`，键集合、条件键有无、`pii`/`indexed` 布尔都必须逐键一致。验证：`catalog-adhoc.json` 逐键 diff |
| B3 `MetricQueryService.catalog` 移出 | 低风险 | `listMetrics` 的 `errorCode` / `message` 结构需保持一致；未知域仍抛 `MetricException`。验证：`catalog-metric.json` + 未知域用例 |
| B4 类改名/移包 | 无影响 | 不涉及 `@Tool` 方法或 DTO 字段 |
| B5 Loader / Config 拆分与注解化 | 无影响 | 只影响装配，工具对象集合不变。验证：全量测试 + IT 启动 |

统一验证方式：

1. 以 B0 的 `tool-definitions.json` 为唯一契约基线，B1–B5 每批跑一次，`name` / `description` / `inputSchema` 零 diff。
2. 目录输出用「键集合 + 值归一化」比较，不只比较条数。
3. 错误路径单独快照：`listMetrics` 未知域、`adhocQuery` 非法维度各一条，确保 `errorCode` / `message` 不变。
4. 以全量单测 + 真库对账 IT 兜底，不依赖人工页面验证。

---

## 九、编码约定

主代码遵循《阿里巴巴 Java 开发手册》，并尽量使用 Lombok 减少样板代码。

### 9.1 通用

- 包名小写、单数名词；禁止 `util`、`common`、`misc`、`base` 作为兜底包。
- 类名后缀与职责严格对应：`Service` / `Validator` / `Compiler` / `Registry` / `Loader` / `Parser` / `Executor` / `Configuration` / `Request` / `Response` / `Exception`。
- 方法、字段、常量命名遵循阿里规约：常量 `UPPER_SNAKE_CASE`，方法动词开头，布尔字段避免 `is` 前缀歧义。
- 日志不输出个人信息、凭证、完整查询结果；保持现有安全边界。

### 9.2 Lombok 使用规则

1. 优先使用 Lombok，不手写无参构造器、全参构造器、getter/setter、`toString`。
2. 常用注解：`@Data`、`@NoArgsConstructor`、`@AllArgsConstructor`、`@Builder`、`@RequiredArgsConstructor`。
3. **Spring Bean 注入**：`@Service` / `@Component` 中 `final` 依赖统一用 `@RequiredArgsConstructor`，保持构造器注入。
4. **Jackson 反序列化 DTO**：必须保留无参构造，使用 `@NoArgsConstructor`；需要全参时叠加 `@AllArgsConstructor`。
5. **继承场景**：使用 `@EqualsAndHashCode(callSuper = true)`，避免父类字段未参与比较。
6. **构造器内含字段校验或归一化逻辑的类**：不得直接替换为 Lombok 全参构造；保留显式构造器或改为 Builder + 静态工厂，确保校验逻辑不被绕过。
7. **MyBatis 实体**：优先 `@Getter` / `@Setter`，谨慎使用 `@Data`（避免 `equals/hashCode` 在集合、缓存、代理场景产生隐患）。
8. 现有 `record`（`MetricDefinition`、`QueryPlan`、`SemanticRegistry` 嵌套模型）保持 `record`，不强行改写为 Lombok class。

---

## 十、只做 B0 + B1 的收益评估

### 10.1 已解决

- adhoc 的「请求 / 响应 / 模型」三合一被拆开，`adhoc` 包可按 `request` / `response` / `model` 快速定位。
- 指标域 DTO 从通用 `entity` 移回域内，`entity` 恢复为「MyBatis 行实体 + Phase 1 遗留 DTO」的单一语义，消除指标域对通用实体包的反向依赖。
- 异常归位到域内 `exception`，与 DTO 生命周期分离。
- 获得一套契约快照基线，后续任何重构都能证明「模型看到的工具没变」。

### 10.2 未解决（仍需 B2–B5）

- adhoc 仍反向依赖 `metrics` 包内的 `GeneratedSql`、`SqlExecutor`、`MetricsLimits`、`DerivedCalculator`、`ColumnTypeRegistry`。
- `AdhocTool` 仍直接注入注册表并自建目录；`MetricQueryService` 仍兼做查询与目录。
- `SqlExecutor` / `ColumnTypeRegistry` 的 Bean 仍由指标配置声明，adhoc 的运行期依赖来自指标域装配。
- `MetricsLimits` 仍是多关注点常量桶；`SqlCompiler` 仍是无域限定的通用名；`AdhocValidator` 仍是常量 + 六类规则的长文件。
- 配置类仍是「加载 + 装配」二合一，adhoc Bean 仍是手工 `new`。

### 10.3 量化判断

- 文件级可读性：约改善 40%。
- 依赖方向与可测试性：约改善 10%。
- 真正的架构收益集中在 B2（依赖反转）与 B3（Tool 瘦身）；B1 主要先让目录语义自洽，便于后续评审与实施。

---

## 附：本方案明确不做的内容

1. 不合并两套编译器、校验器、异常。
2. 不改 Phase 1 遗留：`CrmAnalyticsTool`、`GuessTool`、`entity` 下 Phase 1 DTO、`mapper`、`MemberInfoService/Impl`。
3. 不改 YAML schema 与资源路径（`metrics/`、`semantic/` 均保持现状）。
4. 不引入 SPI / 插件机制、通用查询 DSL、独立 Maven 模块。
5. 不拆分 `SemanticRegistry` 的 record 定义（如需要，另行立项 B6）。
6. 不改 MCP 工具名、入参/出参与提示词文案。
7. 不抽公共「时间窗口 SQL」「别名引号」助手到 shared 之外的抽象层。

---

## 实施结果（2026-11-07）

### 状态

B0–B5 六个批次已全部按顺序实施完成，无跳批、无合并批次。改动留在工作区，未执行 git 提交与 push。

| 批次 | 状态 |
|---|---|
| B0 契约快照与特征化测试 | 已完成 |
| B1 DTO 与异常归位（含 adhoc/model 拆分） | 已完成 |
| B2 抽取 shared 共享层 | 已完成 |
| B3 抽取目录服务 + service 归位 | 已完成 |
| B4 类归位、改名与常量抽取 | 已完成 |
| B5 Loader / Configuration 拆分 + 注解式 Bean | 已完成 |

### 验收结果（真实执行）

- 全量单测/上下文测试：`.\mvnw.cmd -Pdev -pl mcp-server -am test '-Dprofiles.active='` → **266 tests, 0 failures, 0 errors, BUILD SUCCESS**。
- 真实数据库 IT：`AdhocReconciliationIT` 14 tests、`CrmAnalyticsMapperIntegrationTest` 3 tests、`MemberInfoMapperIntegrationTest` 3 tests，均 0 failures；当前环境数据库连接可用。
- 说明：`AdhocReconciliationIT` 由 surefire 默认排除，需显式 `-Dtest=` 执行；上述全量命令不包含它，故单独列出。

### 与方案原文不一致、已获用户确认的实现

1. `AdhocSqlCompiler.injectedFilters()`：B4 跨包后 service 需要读取编译器注入的过滤条件。为避免向 service 暴露包内可见的 `Chain`/`chain()`，在编译器新增公开只读方法 `injectedFilters(AdhocQueryRequest)`，返回「主实体 baselineFilters + 各关系 joinFilters」；`AdhocQueryService` 改为调用该方法，行为与口径文案不变。
2. `orEmpty`：`AdhocSqlCompiler.orEmpty` 收紧为 `private`；`AdhocRequestValidator` 不再跨包调用编译器 helper，改为本域私有 `orEmpty`（null 视为空集合）。

### 依赖方向复查（静态 import 扫描，覆盖 mcp-server main/test）

扫描方法：读取 `src/main/java` 与 `src/test/java` 下全部 `.java` 的 `package` 声明与 `import com.itzixi.*`，按第三章三条规则逐条匹配，不凭印象判断。

| 规则 | 结论 |
|---|---|
| 1. `com.itzixi.adhoc` 不得 import `com.itzixi.metrics` | 主代码通过；测试仅 1 处合理例外（见下） |
| 2. `com.itzixi.metrics` 不得 import `com.itzixi.adhoc` | 通过（0 处） |
| 3. `com.itzixi.shared` 不得依赖任何业务域 | 修复后通过（0 处） |
| 4. `mcp.tool` 只依赖各域 service + request/response，不碰 compile/validate/registry/config/exception，不碰 Mapper | 主代码 2 处、测试 2 处例外；其余通过 |

#### 已修复违规

1. **规则三**：`shared/sql/ReadOnlySqlGuard` 原先 import `com.itzixi.metrics.exception.MetricException`，使 shared 反向依赖指标域。
   - 改法：新增 `com.itzixi.shared.sql.SqlGuardException`，只读闸门改抛该异常；`MetricQueryService` 增加 catch 并映射回 `MetricException.Code.SQL_REJECTED`，对外错误码不变；`ReadOnlySqlGuardTest` 断言同步改为 `SqlGuardException`。
   - 涉及文件：`shared/sql/ReadOnlySqlGuard.java`、`shared/sql/SqlGuardException.java`（新增）、`metrics/service/MetricQueryService.java`、`test/shared/sql/ReadOnlySqlGuardTest.java`。
2. **规则一（测试）**：`adhoc/SemanticCatalogContractTest`、`adhoc/SemanticCatalogServiceTest` import `com.itzixi.metrics.MetricsFixtures`，仅用于复用契约快照工具。
   - 改法：新增测试中立工具 `com.itzixi.contract.ContractSnapshots`（`canonicalJson/parseJson/readBaseline/assertContractBaseline/orderedMap/writeBaseline`）；`MetricsFixtures` 改为委托该类，公开方法签名保持不变；两个 adhoc 测试改依赖 `ContractSnapshots`。
   - 涉及文件：`test/contract/ContractSnapshots.java`（新增）、`test/metrics/MetricsFixtures.java`、上述两个 adhoc 测试。

#### 合理例外（未硬改）

| 文件 | 行 | 依赖 | 理由 |
|---|---|---|---|
| `test/adhoc/AdhocReconciliationIT.java` | 10–13 | `metrics.request.MetricQueryRequest`、`metrics.response.MetricResult`、`metrics.BaselineFixtures`、`metrics.service.MetricQueryService` | 跨域对账测试：同一业务问题分别走指标框架与 adhoc 后比对结果，天然需要同时依赖两域；放回任一域包都会造成反方向违规 |
| `main/mcp/tool/AdhocTool.java` | 3（使用于 95） | `adhoc.exception.AdhocException` | 工具层需要捕获域异常并转述为工具结果，属第三章明确允许的例外 |
| `main/mcp/tool/GuessTool.java` | 4、33–34、41 | `mapper.MemberInfoMapper` | Phase 1 遗留债，方案 §1.1 与「明确不做」第 2 条已声明不改，属于 B0–B5 范围外的已知偏差 |
| `test/mcp/tool/MetricToolInjectionTest.java` | 6、9、11 | `metrics.config.MetricDefinitionParser`、`metrics.registry.MetricRegistry`、`metrics.compile.MetricSqlCompiler` | 测试需手工构造底层对象与本地注册表，验证 Tool 注入装配；非生产依赖 |
| `test/mcp/tool/ToolContractSnapshotTest.java` | 5、41 | `mapper.MemberInfoMapper` | 快照测试需构造 Phase 1 `GuessTool`，以 mock 提供 Mapper |

修复后复跑：全量 **266 tests, 0 failures**；真库 IT 组合 **20 tests, 0 failures**（`AdhocReconciliationIT` 14 + 两个 Mapper IT 各 3）。

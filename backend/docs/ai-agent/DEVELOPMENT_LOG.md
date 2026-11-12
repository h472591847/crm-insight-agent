# SpringAi-MCP-RAG-Dev 开发日志

## 记录规则

只记录实际发生的事实。没有执行的测试不能写成通过；没有确认的业务口径不能写成已确定。每次重要需求澄清、架构决策、代码修改或验证后更新本文件。

## 2026-10-27：独立 CRM 数据分析 Agent 项目上下文初始化

### 一、项目定位

- 开发主体：`SpringAi-MCP-RAG-Dev`
- 应用形态：独立 AI 应用
- 外部数据源：CRM MySQL
- 连接方式：应用直接连接 CRM MySQL
- 当前环境：已有测试数据库，优先使用测试环境验证
- 项目边界：不在 CRM 源码项目内开发；未经授权不修改 CRM 源码、表结构、触发器或业务数据。

### 二、已知信息

- 核心表：`expense_calendar_info`、`expense_calendar_details`、`member_info`
- 已知触发器：`ff_update_member_info_view`
- 部分金额和数量字段使用 VARCHAR。
- 有效订单、退款、删除标记、会员累计字段等口径需要确认。
- 需要检查当前仓库现有技术架构，不能仅凭项目名称推断依赖版本和模块设计。

### 三、当前状态

- [ ] 已检查当前仓库目录和依赖版本
- [ ] 已确认测试库连接方式和只读账号
- [ ] 已确认测试数据脱敏情况
- [ ] 已整理完整原始 DDL
- [ ] 已与产品经理确认 MVP 场景
- [ ] 已确认核心业务指标
- [ ] 已确定工具接口和数据访问方案
- [ ] 已建立标准答案与对账测试
- [ ] 已完成首个端到端自然语言查询

请仅在实际完成后勾选。

### 四、待解决问题

1. 首批必须支持的业务问题是什么？
2. 测试库是否有只读账号和必要的网络访问权限？
3. 用户身份如何映射到租户、门店及数据权限？
4. 消费金额、有效订单、退款和毛利口径是什么？
5. 使用现有数据访问组件还是新增轻量数据访问层？
6. 首个 MVP 如何建立可重复的标准答案测试？

### 五、下一步

1. 检查现有仓库和依赖版本。
2. 确认测试数据库的安全连接配置。
3. 阅读并补齐完整原始 DDL。
4. 与产品经理确认首批问题和指标口径。
5. 设计一个范围明确、可用测试数据验证的查询工具。
6. 完成工具测试后再接入 Agent 对话流程。

## 2026-10-28：Phase 1 只读分析工具实现（门店业绩 / 会员总览 / 商品排行）

### 一、本次目标
实现 Phase 1 三个只读、聚合、无 PII 的 CRM 分析工具，跑通 Tool Calling 骨架。

### 二、已完成事项（均为实际执行）
- 新增 `mcp-server` 下：`CrmAnalyticsTool`（`queryMemberOverview`/`queryStationPerformance`/`queryProductRanking`）、6 个请求/响应 DTO、`MemberStatMapper`/`ExpenseStatMapper` 及其 XML。
- 在 `ToolConfiguration` 注册新工具。
- 编写 `CrmAnalyticsToolTest`（Mockito，不连库）覆盖参数校验、默认值/上限、客单价与人均的确定性计算。
- **构建与单测已实际执行并通过**：使用 IntelliJ 自带 Maven 运行 `mvn -o -pl mcp-server test` → `BUILD SUCCESS`，`Tests run: 8, Failures: 0, Errors: 0`。未新增任何 Maven 依赖。

### 三、关键技术决策
- 复用 `mcp-server` 现有数据源；门店用单表 `expense_calendar_info`、商品用单表 `expense_calendar_details`，规避订单↔明细联查重复累计。
- 金额 VARCHAR 统一 `CAST(NULLIF(TRIM(x),'') AS DECIMAL)`；`ORDER BY` 用 MyBatis `<choose>` 白名单，禁止外部传列名。
- 默认时间范围=当前自然月；跨度上限 366 天；TopN 默认 10、上限 100；日期与范围服务端校验。
- Phase 1 不接入真实鉴权（`tenant`/`station` 仅可选过滤），生产前必须补服务端权限。

### 四、Step 0 只读数据验证结论（测试库实测）
- 行数：info 10万 / details 57.1万 / member 10万。`expense_realamt` 无空无非数字（CAST 安全）。全部 `state='10'`、`del_flag='0'`，退单负数 3473 条，SUM 自然抵消。
- `gross_margin` 有 322 条非数字（属 Phase 2 门店毛利，届时需清洗）。
- 品类字段 `expense_d_commCodeName` 为细粒度混合类目且约 19.3 万行为空，无“酱香/浓香/清香”香型字段 → 分品类/香型排行不可直接实现。

### 五、尚未验证 / 风险
- Mapper 聚合 SQL **尚未对真实测试库执行验证**（本轮仅编译 + 无库单测）。上线前必须在 IDE 数据源用历史区间对账。
- ⚠️ `expense_calendar_info` 数据仅到 2026-01-03；“本月”默认在测试库会返回空，联调须显式传历史区间（如 2025 某月）。
- 商品排行不支持 `tenant`（明细表无租户字段）；端到端自然语言对话链路本轮未测。

### 六、下一步
用历史区间对真实库对账三个工具的 SQL；确认无误后将 Phase 1 指标在 `BUSINESS_METRICS.md` 标为已实现/已验证；再推进 Phase 2（口径已确认后）。

## 2026-10-28（补充）：数据重拉为 6/20-6/27 样本 + Phase 1 聚合 SQL 真库集成验证通过

### 一、数据集变更
- 本地测试库(crm_dev)由 10 万全量重拉为“6/20-6/27 活跃用户”分层样本：`expense_calendar_info` 5857 行(2025-10-03→2026-06-27)、`expense_calendar_details` 8892 行(2019-09-12→2026-06-27)、`member_info` 5000 行。
- 三表所在 schema = `crm_dev`，与 mcp-server 数据源同库，工具无需改数据源即可查表。
- 状态分布复核：info 全部 `state='10' AND del_flag='0'`，退单负数 137 行。

### 二、代码核对结论（无需改动）
- 复核 Phase 1 Mapper：租户列已正确使用 `expense_tenant`/`member_tenant`（此前给用户的临时体检 SQL 误写裸 `tenant`，正式代码无此问题）。

### 三、已实际执行的验证
- 新增 `CrmAnalyticsMapperIntegrationTest`（`@SpringBootTest`，注入两个 Mapper，直连真库执行将要上线的聚合 SQL，窗口显式 2026-06-20~27）。
- 运行 `mvn -o -pl mcp-server test -Dtest=CrmAnalyticsMapperIntegrationTest` → **BUILD SUCCESS，Tests run: 3, Failures: 0, Errors: 0**。推翻上一节“SQL 尚未对真实库执行”的状态。
- 实测基线：会员总览 total=5000 / male=4880 / female=120 / unknown=0（三分区自洽）、有消费会员 3993、累计消费 2,660,605.52；门店业绩 228 组（Top1 标杆旗舰店 117,906.50 / 90 单，店名初记“哈市鸿路旗舰店”为笔误，2026-10-29 对账实测校正，数值不变）；商品排行 Top10（Top1 头部白酒A 890,272.00 / 532）。

### 四、暴露的数据质量事实（待产品确认，未擅自处理）
- 门店业绩存在 1 组 `expense_stationCode` 为空的订单 → 是否从“门店业绩”中排除或归为“未知门店”，属业务口径，待确认。
- 个别 `expense_d_gdcode` 取值异常（如 `198.0`/`298.0`，疑似价格误填进商品代码）。

### 五、仍未验证 / 下一步
- 端到端自然语言对话链路（mcp-server + mcp-client + 模型触发 @Tool）仍未测。
- `BUSINESS_METRICS.md` 的 Phase 1 指标状态是否翻为“已实现/已验证”，待上述空门店编码口径确认后与用户一并处理。

### 六、口径定稿与收尾（2026-10-28，用户确认）
- 用户定：① 空门店编码归为“未知门店”；② 异常 SKU 原样透出（属测试数据）；③ 据此更新 markdown。
- 代码修改：`CrmAnalyticsTool.queryStationPerformance` 新增工具层归一（`UNKNOWN_STATION_NAME`），空/blank 的 stationCode 且无名的行置名“未知门店”，仍单独成行；未改 SQL。
- 集成测试改走工具层验证归一（注入 `CrmAnalyticsTool`）；重跑 `mvn -o -pl mcp-server test` → **BUILD SUCCESS，Tests run: 11, Failures: 0, Errors: 0**（3 集成 + 8 单测）。实测：门店 228 组含“未知门店”行=true，合计销售额 2,445,299.92。
- `BUSINESS_METRICS.md` 第五/六节已更新：Phase 1 四项指标标为“已确认+Phase1已实现+真库验证”并附实测基线；异常 SKU 与空门店口径写入备注。
- 下一步：端到端自然语言链路验证；或进入 Phase 2（需先答 `METRICS_MAPPING.md` 口径类问题）。

## 2026-10-29：Phase 2 方案定稿 —— 指标即配置（Metrics-as-Code）+ SQL 编译层

### 本次目标
产品/CRM 开发评审后确认：老板要的指标大部分 CRM 系统自身也未实现，且现有几十个接口不满足统计需求。据此**放弃调用 CRM API 的方案**，改为直连只读库。同时明确核心痛点：不愿每加一个指标就写 Mapper/XML/DTO/Tool，Phase 1 已完成的 SQL 也不想继续手写。本轮产出 Phase 2 技术方案与使用方法，**不改动任何 Java 代码**。

### 已完成事项
- 新增 `docs/ai-agent/PHASE2_METRICS_LAYER_TECH_PLAN.md`（538 行）：核心决策、架构分层、指标 YAML 配置规范、Phase 1 三指标的等价配置、SqlCompiler 生成规则、运行时接口、安全闸门、使用方法（含 AI 生成配置的提示词模板与错误排查表）、文件清单、里程碑 M0~M4、测试验收、表达力边界、待确认问题与风险。
- 确立关键区分：**开发期由 AI 生成配置 + 人审固化** ≠ **运行期由模型现场生成 SQL**。后者不可复现、不可作为看板依据，仅用于长尾探索且必须过 AST 闸门。
- 给出 Phase 1 三个手写 SQL（`ExpenseStatMapper.xml` 门店业绩/商品排行、`MemberStatMapper.xml` 会员总览）的等价 YAML，证明主指标可完全由配置驱动；并把此前散落在各 SQL 里的约定收拢为编译器一次实现：`NUM()` 金额转换、左闭右开时间边界、grain 防重复累计、sortable 列名白名单、LIMIT 上限、合计行、空维度归并“未知门店”、派生指标 Java 端四则计算。

### 修改文件
- 新增：`docs/ai-agent/PHASE2_METRICS_LAYER_TECH_PLAN.md`
- 修改：`docs/ai-agent/DEVELOPMENT_LOG.md`（本节）
- 未改动：`mcp-server`/`mcp-client` 全部代码、Mapper、XML、配置与数据库对象。

### 架构或技术决策
1. **主路径 = 指标即配置 + 确定性 SQL 编译**，非纯 NL2SQL。模型只输出结构化查询意图（指标/维度/过滤/时间/排序均为枚举）。
2. 新增指标成本目标：一段 YAML（约 10 分钟）+ 对账，Java 代码零改动；一次性投入编译器约 3~5 人日。
3. 派生计算公式仅允许四则运算白名单解析，**不引入 SpEL/Aviator**（避免表达式注入与新依赖）。
4. 逃生舱（`rawFragment`）与长尾生成 SQL 必须过 `SqlGuard` AST 校验（10 条规则）+ 数据权限服务端注入 + 强制 LIMIT，且合入需 code review。
5. 配置加载期校验失败即启动失败，把配置错误暴露在部署期而非查询期。
6. `status=UNCONFIRMED` 的指标一律拒绝执行并返回明确错误码，防止“口径未定却输出权威数字”。
7. 迁移采用“逆向抽取 + 双跑对账”：AI 从现有 XML 反推 YAML → 编译结果与手写 SQL 逐值一致 → 再删除旧 Mapper/DTO。

### 业务口径变化
无。本轮未新增或修改任何业务口径，沿用 2026-10-28 已锁定结论（有效订单 `expense_state='10' AND del_flag='0'`、退单为负数行自然抵消、SKU 取 `expense_d_gdcode`、默认时间为本月、空门店编码归“未知门店”、异常 SKU 原样透出）。Phase 2 指标口径仍全部待产品确认。

### 测试与验证结果
本轮**未执行任何单元测试、集成测试或数据库查询**。实际执行的验证仅有依赖核实（读取本地 `.m2` 仓库 POM 与目录）：
- `mybatis-plus-spring-boot3-starter:3.5.14` 的 POM 直接声明 `spring-boot-starter-jdbc` → `JdbcTemplate` 可用于执行编译产出的 SQL。
- `mybatis-plus-jsqlparser:3.5.14` 依赖 `com.github.jsqlparser:jsqlparser`，本地仓库存在版本 **5.2** → 可做 AST 校验。
- `org.yaml:snakeyaml` 本地存在版本 **2.4**；并核实 `spring-boot-starter:3.5.14` 的 POM **直接声明** `org.yaml:snakeyaml`，而 `spring-boot-starter-mail` / `spring-ai-starter-mcp-server-webflux` 均传递引入 `spring-boot-starter` → snakeyaml 在 mcp-server 类路径上可用。
- 结论：方案**不需要新增 Maven 依赖**，三项能力均由读取本地 `.m2` 仓库的 POM 声明核实；但尚未跑过完整 `dependency:tree` 输出，M0 建议补一次作为最终确认。
- 环境事实：本机 `mvn` 不在 PATH（`mvn` 命令不可用），构建与测试需通过 IDEA 内置 Maven 执行。

### 未解决问题
1. Phase 2 全部指标口径待产品回答 `METRICS_MAPPING.md` 第四节 15 个问题 —— **唯一硬阻塞**。
2. 配送域、财务结算域的表结构与口径完全缺失，无法生成配置。
3. JSqlParser 5.2 相对 4.x 的 API 差异未验证（`Limit`/`PlainSelect` 构造与 setter 形态），M0 需专项可行性测试。
4. 是否允许在 CRM 侧建只读视图/独立分析库做加速层，需用户决策（规则禁止擅自复制 CRM 数据到本项目新表）。
5. 只读 `GRANT SELECT` 账号与 PII 视图隔离尚未申请。

### 风险
- 编译器成为单点：一处 bug 影响所有指标 → 靠 SQL 快照测试 + 迁移期双跑对账 + `sqlHash` 反查缓解。
- 测试样本量（info 5857 / details 8892 / member 5000）与生产量级（消费表 AUTO_INCREMENT 达 580 万级）差距巨大，索引与超时策略在生产需重估。
- 逃生舱若失控会退化为“不可信的万能 SQL 口子”。
- Phase 2 若延续无真实鉴权状态，仅限测试环境使用，结果需标注该限制。

### 下一步
建议起手式：M0（JSqlParser 5.2 能力测试 + `dependency:tree` 复核 + `column-types.yaml`）→ M1 编译器最小版 → M2 用 Phase 1 三指标做逐值对账迁移，约 3 天即可兑现“新指标不写 SQL、不写 Mapper”。等用户确认是否开工，以及先答哪几项口径。

## 2026-10-29（续）：Phase 1 三工具改造为指标即配置编译层，双跑逐值对账全绿

### 本次目标
按 `PHASE2_METRICS_LAYER_TECH_PLAN.md` 落地 M1~M3：实现 Metrics-as-Code 框架（配置模型/加载校验/SQL 编译/受限执行/派生计算），把 Phase 1 三个手写工具迁移为 YAML 驱动，并完成与旧手写 Mapper 的真库逐值对账。

### 已完成事项
- **M1 框架层**（`com.github.yingli.metrics`，14 个类）：`MetricDefinition`/`MetricDefinitionParser`（SnakeYAML SafeConstructor）/`MetricRegistry`/`MetricRegistryConfiguration`/`MetricDefinitionValidator`/`ColumnTypeRegistry`/`SqlCompiler`/`GeneratedSql`/`QueryPlan`/`QueryPlanValidator`/`DerivedCalculator`（四则运算白名单递归解析）/`SqlExecutor`（JdbcTemplate，maxRows=2000 + queryTimeout=10s + `MAX_EXECUTION_TIME` hint）/`MetricException`/`MetricsLimits`。
- **M2 配置层**：`resources/metrics/member.yaml`（MEMBER_OVERVIEW）、`order.yaml`（STATION_SALES、PRODUCT_RANKING）、`column-types.yaml`（表白名单 + VARCHAR 数值列 + PII 黑名单）。`MetricQueryService` 统一编排：解析→校验→编译→受限执行→派生计算→合计行（withTotalRow 时另发 compileTotals 全量聚合，杜绝 TopN 合计冒充总体）。
- **M3 工具层**：新增域工具 `MetricTool`（`queryMemberMetric`/`queryOrderMetric`/`listMetrics`/`explainMetric`）并注册进 `ToolConfiguration`；**重写 `CrmAnalyticsTool`**：三个旧 @Tool 签名与返回 DTO 完全不变，内部委托编译层（迁移过渡期兼容旧调用方）。
- STATION_SALES 显式配 `limitDefault: 500 / limitMax: 2000`：Phase 1 无 LIMIT 且实测 228 组，若用默认 100 会截断破坏对账（已在配置注释说明）。

### 修改文件
- 新增 main：`metrics` 包 14 类、`MetricQueryService`、`MetricTool`、`entity/request/MetricQueryRequest|FilterItem`、`entity/response/MetricResult|ColumnMeta`、`resources/metrics/{member,order,column-types}.yaml`。
- 修改 main：`MetricRegistryConfiguration`（增 sqlExecutor Bean）、`ToolConfiguration`（注册 MetricTool）、`CrmAnalyticsTool`（整体重写为委托编译层）。
- 新增 test：`SqlCompilerTest`、`MetricDefinitionValidatorTest`、`MetricsFixtures`、`MetricToolInjectionTest`、`MetricsAsCodeMigrationReconciliationTest`；重写 `CrmAnalyticsToolTest`（改为 FakeExecutor 驱动编译层，不再 mock Mapper）。
- **未删除**：旧 `ExpenseStatMapper`/`MemberStatMapper` 及 XML、旧 DTO 保留作对账基准；未新增任何 Maven 依赖。

### 测试与验证结果（均已实际执行）
- 命令：IDEA 内置 Maven `mvn -o -pl mcp-server test` → **BUILD SUCCESS，Tests run: 46, Failures: 0, Errors: 0, Skipped: 0**。
- 分项：`SqlCompilerTest` 8（golden SQL 快照/NUM scale/compileTotals/注入参数化/非法维度排序拒绝）、`MetricDefinitionValidatorTest` 10（真实 3 配置零错误 + 9 类安全规则）、`CrmAnalyticsToolTest` 12（不连库）、`MetricToolInjectionTest` 10（跨域/UNCONFIRMED/注入/topN 夹取/explain 干跑/QUERY_FAILED）、`MetricsAsCodeMigrationReconciliationTest` 3（连真库）、既有 `CrmAnalyticsMapperIntegrationTest` 3（连真库，回归通过）。
- **逐值对账结论（窗口 2026-06-20~27）**：编译层与手写 XML 在同库同参数下 228 组门店逐行逐值一致（销售额/订单数/客单价/未知门店归并/降序）、全量合计 2,445,299.92、Top1 标杆旗舰店 117,906.50；商品 Top10 按位逐值一致（Top1 头部白酒A 890,272.00/532）；会员总览逐字段一致（5000/4880/120/0/3993/2,660,605.52）。
- 运行中暴露并修复 3 处测试断言写法问题（`ORDER BY` 含 "OR" 的误报、zeroFill 的 COALESCE 包装未计入期望、Top1 店名按实测校正）；**生产代码未因测试失败做任何修改**。

### 业务口径变化
无。三指标口径与 Phase 1 完全一致，对账即等价性证明。STATION_SALES 的 `baselineFilters`/时间边界/NUM 展开均从旧 XML 逆向抽取。

### 未验证 / 风险
- 新域工具 `MetricTool` 的端到端自然语言链路（模型选工具→组参→表达结果）未测；本轮验证到服务层与工具方法层。
- 数据权限仍未接入（延续 Phase 1 状态，仅限测试环境）；`SubjectContext` 服务端注入属后续项。
- 逃生舱 `rawFragment` + `SqlGuard` AST 校验（M4）未实现；JSqlParser 5.2 API 差异因此未验证。
- 旧 Mapper/DTO 删除待对账基准期结束后另行处理（`CrmAnalyticsMapperIntegrationTest` 依赖它们）。

### 下一步
可选：① 端到端 NL 链路验证（新域工具接入 mcp-client 对话）；② 新增指标用纯 YAML 流程演练（需先答 `METRICS_MAPPING.md` 口径问题）；③ M4 SqlGuard/逃生舱；④ 申请只读账号与权限映射。

## 2026-10-29（续二）：Phase 2 口径答复落地文档，识别出对已上线编译层的三处行为影响

### 本次事件
产品对齐了 `METRICS_MAPPING.md` 第四节全部口径问题（11 条答复），Phase 2 唯一硬阻塞解除。本轮**只更新文档，未改任何代码、未执行任何测试或数据库查询**。

### 口径结论摘要（详见 METRICS_MAPPING.md §1.5）
- 做：新增会员（归属＝注册店 `member_store`）、活跃会员（近 90 天有消费）、会员层级分布（六字段全统）、年龄分布（由生日算，不用 `age` 列）、单客年均消费（近一年半）、历史消费金额去负数。
- 不做：流失/沉睡/名单类、复购族、地域分布（需 TMS）。
- 全局：看板默认时间范围改为**近一年半**。
- 待对齐：“所有标杆酒企消费都算会员”的映射对象（疑指会员/散客判定）；年龄段划分默认方案；门店毛利、会员销售额占比仍未明确。

### 修改文件
- `METRICS_MAPPING.md`：新增 §1.5 答复表；第一节基线加默认时间行；A/B/C/D/E 组逐项状态翻转（✅/⛔）；第四节逐条标注答案；第五节分阶段修订。
- `BUSINESS_METRICS.md`：第五节确认记录表新增/更新 11 行（含确认日期）；第三节头部加指向说明。
- `DEVELOPMENT_LOG.md`：本节。

### 识别出的代码侧影响（尚未实施，待用户确认后开工）
1. **默认时间范围变更**：`QueryPlanValidator` 缺省当前自然月 → 需改为近一年半；旧 @Tool 描述、DTO 注释、`MetricQueryRequest` 参数描述同步。
2. **跨度上限冲突**：`MetricsLimits.MAX_RANGE_DAYS=366` < 一年半≈548 天，需放宽（建议 550）；真库需验证 548 天跨度聚合在 10s 超时内（测试库样本仅 5857 单，风险低；生产量级需重估）。
3. **PII 计算例外机制**：年龄分桶需引用 `member_yang_birthday`（现为 PII 黑名单，`checkColumns` 会拒绝表达式引用）。方案：区分“原值输出”与“聚合分桶输入”——仅允许在 CASE/TIMESTAMPDIFF 表达式内引用，仍禁止作为维度/描述列直接 SELECT；需在 `column-types.yaml`/`ColumnTypeRegistry`/`MetricDefinitionValidator` 实现并补测试，不得简单放行黑名单。
4. 新指标接入（纯 YAML 为主）：MEMBER_NEW / ACTIVE_MEMBER_90D / MEMBER_LEVEL_DIST（六维度）/ AGE_DIST / ANNUAL_AVG_CONSUMPTION_18M；历史消费金额“去负数”与全局“退单抵消”并存，需在指标级 `baselineFilters`/度量表达式层面表达并写清 caliber。

### 风险 / 未验证
- 层级六字段、`member_store`、生日字段的实际取值/填充率未对测试库做过 Step 0 只读核对（开发前必做）。
- 活跃会员＝“近 90 天”与看板默认区间＝“近一年半”是两个独立窗口，指标配置需各自声明，不可混用。

## 2026-10-29（续三）：Phase 2 框架改造 + 5 个新指标接入，真库逐值对账全绿

### 本次目标
落地续二节识别的三处代码侧影响与 5 个新指标（口径见 METRICS_MAPPING.md §1.5，含澄清结论：流失不做活跃做；标杆酒企口径＝活跃/年均消费按 `expense_mobile` 直接统计；年龄默认分桶；默认区间全部改含 Phase 1 旧工具）。

### 已完成事项
1. **框架改造**：
   - `MetricsLimits`：`MAX_RANGE_DAYS` 366→550；新增 `DEFAULT_RANGE_MONTHS=18`、`MAX_DEFAULT_WINDOW_MONTHS=24`。
   - `QueryPlanValidator.resolveDateRange`：缺省区间从“当前自然月”改为 `today.minusMonths(months)`，months 取指标级 `defaultWindowMonths`（>0 时）否则全局 18。
   - `MetricDefinition` 新增 `defaultWindowMonths` 字段（Parser 解析、Validator 校验 0~24 且要求有 timeColumn）。
   - **PII 三态裁决**：`column-types.yaml` 新增 `piiComputeAllowedColumns`（`piiColumns` 子集：`member_yang_birthday`、`expense_mobile`，新增条目须逐个 code review）；`MetricDefinitionValidator.checkColumns` 改为：裸 PII 列输出一律拒 → 复杂表达式内引用白名单列允许 → 其余 PII 引用拒。
   - Phase 1 三工具的 @Tool/DTO 描述同步“默认近一年半”。
2. **Step 0 只读数据体检**（`Step0DataProbeTest`，真库已执行）：生日字段 5000 行全 NULL；`customer_level`/`consume_hierarchy` 全空、`new_member_level_key` 3333/5000（7 值）、`half_year_level` 3867（10 值）、`total_year_level` 3492（10 值）、`member_importance` 31；`member_store` 填充 4999/5000、240 店；`member_tenant` 全空；`member_created` 集中 2026-06-20~27；`expense_mobile` 5857 行无空、去重 3923。关键事实写入各指标 caliber。
3. **新指标 YAML**（`member.yaml` +5，全部 `status: CONFIRMED`）：MEMBER_NEW（注册店归属，窗口 18）、ACTIVE_MEMBER（按 mobile 去重、窗口 3）、MEMBER_LEVEL_DIST（六维度存量分布）、AGE_DIST（生日 CASE 分桶）、ANNUAL_AVG_CONSUMPTION（窗口 18，退单负行自然抵消，不适用“历史累计去负数”特例）。

### 修改文件
- 主代码：`MetricsLimits` / `QueryPlanValidator` / `MetricDefinition` / `MetricDefinitionParser` / `MetricDefinitionValidator` / `ColumnTypeRegistry` / `MetricTool` / `CrmAnalyticsTool` / 三个 Request DTO 注释。
- 配置：`metrics/column-types.yaml`、`metrics/member.yaml`。
- 测试：新增 `Step0DataProbeTest`、`QueryPlanDefaultWindowTest`、`Phase2MetricsReconciliationTest`；`SqlCompilerTest` +3 golden、`MetricDefinitionValidatorTest` +5（PII 三态+窗口校验）；`MetricToolInjectionTest`/`MetricDefinitionValidatorTest` 目录计数断言 3→8、member 1→6。

### 测试与验证结果（均已实际执行）
- `mvn -o -pl mcp-server test` 全量：**68/68 BUILD SUCCESS**（含 @SpringBootTest 真库用例）。
- 真库逐值对账（区间 2026-06-20~27，与手写 oracle SQL 同区间比对）全部通过：
  - ACTIVE_MEMBER：逐门店 `COUNT(DISTINCT expense_mobile)`/订单数一致；合计行＝全局去重（≤分店求和，验证了“TopN 求和≠总体”设计）。
  - MEMBER_NEW：逐注册店分组一致；区间内新增总数＝5000（与 Step 0 “created 集中 06-20~27”事实自洽）。
  - MEMBER_LEVEL_DIST：`NEW_LEVEL` 归并“未填写”后与 oracle 逐组 Map 相等，总数 5000。
  - AGE_DIST：仅“未知”一组 5000 人（测试库生日全空，属数据限制非口径错误；oracle 交叉验证生日非空人数=0）。
  - ANNUAL_AVG_CONSUMPTION：逐门店金额/去重人数一致，派生单客年均＝金额/人数(HALF_UP 2)。
- 新 YAML 加载期校验全部通过（8 指标注册，启动即验证）。

### 业务口径变化
- 看板默认时间范围：当前自然月 → **近一年半**（Phase 1 旧工具同步生效）；跨度上限 366→550 天。
- PII 新增“计算输入例外”机制（仅白名单两列，不可原值输出）。

### 未解决问题 / 风险
- 生日字段测试库全空：AGE_DIST 在真实库上的分布有效性待生产数据验证。
- `member_tenant` 未填充：租户维度在测试库上无区分度。
- 生产量级下 548 天跨度聚合的查询耗时未验证（当前仅 5857 单样本库）。
- 数据权限仍未接入（延续既有状态，仅限测试环境）。

### 下一步
- 模型端自然语言→新指标编码的路由验证（listMetrics 目录已含 8 指标）；真实/脱敏库上 AGE_DIST、层级分布复验。

## 2026-10-30：数据源切换为真实测试库 crm_dev，Step 0 全量重探测（旧基线全部失效）

### 本次目标
`application-dev.yml` 的数据源已从 5000 行样本库切到真实 CRM 测试库 `crm_dev`。本次重做 Step 0 只读体检，量化既有基线与口径断言的失效范围，并把探测事实补记入文档。**本轮未改动任何 Java 代码、指标 YAML 与测试断言。**

### 已完成事项
1. **只读探测 6 轮**（`scripts/probe/step0_probe_*.py`、`step0_baseline_draft.py`；pymysql 直连，凭证运行时从 `mcp-server/src/main/resources/.env` 读取且不打印；每轮以 `START TRANSACTION READ ONLY` 开始、结束 `rollback`，仅执行 SELECT / EXPLAIN / SHOW）。
2. **印证业务确认**：`expense_calendar_info` 391,093 行状态分布只有 `('10','0')` 一组；`expense_calendar_details` 15,294,611 行为 `('10','0')` 15,294,573 + `('10','1')` 38。两表均**无 state='20'**，退单以负数行存在：info 12,181 行合计 −29,098,188.09，details 564,969 行 → 负数抵消逻辑成立。
3. **量级对比**：info 39.1 万 / details 1,529 万 / member 62.9 万（旧记录为 5,857 / 8,892 / 5,000）。
4. **新基线采集并落草稿**：`mcp-server/src/test/resources/baseline/crm_dev-baseline.DRAFT.yaml`（带 DRAFT，当前无测试读取，待复核后改名启用）。
5. **10-30 追加探测（生日与性别）**，见下文「业务口径变化」与「未解决问题」。

### 修改文件
- 文档：本文件、`BUSINESS_METRICS.md`（§五 年龄分布口径修订、§六 基线整段重写）、`PHASE1_TECH_PLAN.md`（§五/§九 默认时间范围与跨度上限标注已被取代）。
- 非 Java 新增：`scripts/probe/` 6 个探测脚本、`mcp-server/src/test/resources/baseline/crm_dev-baseline.DRAFT.yaml`。
- **未改动**：`mcp-server` 主代码、`resources/metrics/*.yaml`、任何测试。

### 架构或技术决策
- 对账区间从「随今天漂移的自然月」改为**固定历史区间**，建议 `2026-06-01` ~ `2026-06-27`（左闭右开 06-28）。理由：三表数据量最大的连续完整月份（info 185,228 单 / details 263,450 行 / `member_created` 上界亦为 06-27），单月聚合实测 1~1.2s 可稳定复现，且不随日期漂移。
- **基线数值外置**：测试保留结构性/自洽断言（分区求和=总数、降序、编译层 vs 手写 Mapper 逐值一致、参数非法不触库），把数值断言改为读取 `src/test/resources/baseline/*.yaml`，换库/换窗口只改基线文件不改测试代码。

### 业务口径变化
- **年龄相关改用阴历生日 `member_yin_birthday` 计算**（产品 2026-10-30 确认）。实测支撑：`member_yang_birthday`（datetime，阳历）在 `del_flag='0'` 的 628,527 行中 **100% 为空**；`member_yin_birthday` 为 **varchar(200)**，非空 2,766 行；`age` 列仅 421 条有值。当前 `AGE_DIST` 配置表达式仍写 `member_yang_birthday`，**尚未修改**，改前该指标在真实库上必然全落「未知」。
- 阴历生日字段形态实测（不取原值，仅聚合）：非空 2,766 行长度全部为 10、全部匹配 `^\d{4}-\d{1,2}-\d{2}$`、`STR_TO_DATE(col,'%Y-%m-%d')` **零解析失败**；解析后范围 1900-11-29 ~ 2026-06-16，跨年数 67；`TIMESTAMPDIFF` 结果 <0 为 0 条、>120 为 2 条、0~120 为 2,764 条。
- **其余口径未变**（有效订单、退单抵消、左闭右开、金额 VARCHAR 走 NUM() 等）。

### 测试与验证结果（如实区分）
- **已实际执行**：6 轮数据库只读探测 + EXPLAIN；新增第 6 轮（生日/性别）9 条查询全部成功、0 失败。
- **未执行**：本轮**没有跑任何 Java 测试**（`mvn` 不在本机 PATH，Phase 2 全量 68 用例的当前状态未知）。既有硬编码 5000 样本基线的断言在真实库上必然失败，属**已定位、未修复**。
- 真实库关键数字（窗口 2026-06-01~06-27，全局无过滤）：
  - 会员总览：总 628,527｜男 592,032｜女 29,969｜未知 6,526（三分区求和=总数 ✓）｜有消费会员 577,042｜累计消费 2,102,473,940.12。
  - 门店业绩：314 组（空门店编码 398 行，其中 372 行连门店名也为空，归并 1 组）｜合计 113,923,128.62｜订单 185,228｜Top1 `01010102` 标杆旗舰店 4,960,148.38 / 3,134 单。
  - 商品排行：Top1 头部白酒A 41,084,915.90 / 销量 24,198；Top5 为 SKU `198.0`「198plus」2,380,158.00 / 12,021 行。
  - 性别取值全枚举：`1`=592,032、`2`=29,969、`NULL`=6,524、`0`=2（DDL 注释仅定义 1男/2女，**0 含义未定义**）。
  - 按阴历生日的年龄分桶预演：19岁及以下 892｜40-49 822｜30-39 590｜50-59 342｜20-29 66｜≥60 54（合计 2,766，占有效会员 **0.44%**，其余 625,761 行仍归「未知」）。
  - 脏值二次核实（含自我纠正）：`expense_d_realamt` 正则判「非数字」7,871 条，但**以 CAST 结果为准复测 cast_null=0**（值形如 `.4`/`.25` 属合法写法），无金额丢失；`expense_d_qty` 2 条、`gross_margin` 351 条（空 11,369）CAST 亦全部成功；`expense_realamt` 无空无非数字。
  - 耗时实测：门店业绩 18 个月 1.6s ✓ / 单月 1.0s ✓；商品排行单月 1.2s ✓ / **18 个月 75.8s ✗**（`FORCE INDEX(index_buyDate)` 后仍 14.2s，超 `QUERY_TIMEOUT_SECONDS=10`）。根因 EXPLAIN：窗口变大后优化器由 `index_buyDate` range 翻转为 `index_state` ref，估算 7,753,707 行 + `Using temporary; Using filesort`。
  - 其他可用事实：`member_mobile` 全表唯一（628,527=628,527）→ 按手机号关联会员与消费的前提成立；`member_store` 填充 628,494/362 店、`member_allot_store` 575,879/350 → 注册店归属可落地；`customer_level`、`consume_hierarchy` 仍**全空**不能作维度；品类去重 93 个（空 2,920,544、含 `--` 占位，无香型）；同窗口订单头合计 113,923,128.62 vs 明细合计 113,892,639.62，差 30,489 元（0.027%），可作「订单↔明细不重复累计」的一致性阈值。
  - **修正自己的两条误报**：① 前述 7,871 条脏值→实为 CAST 全部成功；② 「details 到 10-22、info 到 06-27，两表新鲜度不一致」→ 实为 details 在 06-28 之后仅有 2026-10-22 的 3 行离群数据，两表新鲜度基本一致。

### 未解决问题（均需业务/用户拍板，未擅自处理）
1. **`tenant` 过滤在真实库不可用**：`member_tenant` 628,527 行 **100% 为空**（传任何租户值都返回 0 会员）；`expense_tenant` 61.6% 为空（240,924/391,093），非空唯一值仅 `标杆酒企`。建议会员侧显式拒绝并说明原因，而非静默返回 0；订单侧「空租户」是否视同 `标杆酒企` 待确认。
2. **「异常 SKU」可能是真实商品**：`198.0/198plus`、`298.0/298plus`(3,231 行/96 万)、`99.0/99plus`(982 行/9.7 万) 在真实库窗口内按销售额排到**第 5 名**。旧「疑似价格误填、原样透出」的决定是在 5,857 行样本上做的，现会实质影响榜单，需重新确认过滤/合并口径。
3. **商品排行在默认 18 个月窗口必然超时**：三个选项 — a) 给 `PRODUCT_RANKING` 设指标级跨度上限（如 92 天，代价最小）；b) 模板加 `FORCE INDEX`（仍 14s，超上限，不可行）；c) 请 CRM DBA 加复合索引 `(expense_d_buyDate, expense_d_state, del_flag, expense_d_gdcode)` — 属改 CRM 库，**未经授权不动**。
4. **阴历生日的有效性**：① 覆盖率仅 0.44%，「未知」桶仍占绝对多数，需确认是否对外输出该分布；② 字段是**阴历**日期却与 `CURDATE()`（阳历）直接做 `TIMESTAMPDIFF`，周岁可能偏差约 ±1 个月，是否需要阴阳历换算需业务定；③ 若写入配置，`member_yin_birthday` 目前在 `piiColumns` 内但**不在 `piiComputeAllowedColumns`**，须先入白名单（该文件注释要求逐个 code review）。
5. **既有测试基线失效未修**：`MetricsAsCodeMigrationReconciliationTest`（228 / 5000 / 2,445,299.92 / 标杆旗舰店 117,906.50 / 890,272.00 / 3993 / 2,660,605.52）、`Phase2MetricsReconciliationTest`（三处 5000、AGE_DIST 断言 rowCount=1 与「未知」=5000）、`PHASE2_METRICS_LAYER_TECH_PLAN.md` §8.2 示例基线同样滞后。
6. **趋势类不可验证**：数据是离散月份块（info 近 26 月仅 2024-12 / 2025-06 / 2025-10 / 2026-01 / 2026-06 有数据），环比/趋势在测试库上无法验证。
7. 行政交付表格 `docs/ai-agent/PHASE1_SCHEDULE.xlsx` 的 Day4 基准数字与 R5/R6 已失真，待基线定稿后同步。
8. `scripts/probe/` 探测脚本去留待定（可复用的基线采集工具，或用完即删）。〔已答复（同日）：**固定保留**〕

### 风险
- 数据权限仍未接入（延续既有状态，仅限测试环境）；真实库含 62.9 万会员的个人信息列，任何明细输出风险都比样本库高一个量级。
- 未跑 Java 测试即改基线，可能把「口径错误」误写成「新基线」→ 必须以业务确认过的 oracle SQL 交叉校验，不能只把探测数字当真相。
- 商品排行等长窗口指标若不设上限，模型侧会稳定收到超时错误，表现为「工具能力缺失」而非配置问题，易误判。

### 下一步
- 用户确认固定区间与上述 1~4 项后：改名启用 baseline yaml → 结构性断言保留、数值断言改读 yaml → 跑 `mvn -o -pl mcp-server test` 记录真实结果。
- 年龄指标按阴历生日重写（需先动 `column-types.yaml` 白名单）；性别分布按第 3 问的步骤走配置化新增，见 BUSINESS_METRICS §五。

## 2026-10-30（续）：四项口径决策落地 + 基线外置 + 真库测试全绿

### 本次目标
用户对本节前半所列待拍项给出四项决策，本轮职责是把决策改为代码、配置与测试的闭环，并真实执行回归。

1. **不再支持 `member_tenant` 及其他 tenant 的筛选和统计**，统计关注门店。
2. **「异常 SKU」按真实商品统计**，不过滤、不合并、允许进榜。
3. **商品排行采用方案 a**：给 `PRODUCT_RANKING` 设指标级跨度上限（本节前半已声明：选 a 必须新增框架能力，不是纯配置改动）。
4. **接受周岁偏差**：年龄改由阴历生日计算。

### 架构或技术决策
- **框架新增 `maxRangeDays`（指标级时间跨度上限）**：`MetricDefinition` 新增字段，`MetricDefinitionParser` 显式映射、`MetricDefinitionValidator` 校验（0~全局上限之间才合法）、`QueryPlanValidator` 计算时取 `min(maxRangeDays, MetricsLimits.MAX_RANGE_DAYS)`：**显式越界 → 拒绝**（错误文案带“指标 xxx 的窗口上限”），**缺省窗口越界 → 收敛**。`0` 表示沿用全局 550 天，对存量指标零影响。`PRODUCT_RANKING` 同时声明 `maxRangeDays: 92` + `defaultWindowMonths: 3`。
- **tenant 下线采用三层结构**：配置层（`metrics/*.yaml` 不再声明任何 tenant 维度/`optionalFilters`）→ 校验层（`QueryPlanValidator.normalizeFilters` 对未声明编码抛 `INVALID_PARAM`，因此模型传 `TENANT` 是**显式失败**而非静默忽略；`SqlCompiler` 不认的 code 不进 SQL，属二道兜底）→ 工具层（`tenant` 入参非空直接返回 `MSG_TENANT_UNSUPPORTED` 且不触库）。保留 Request DTO 的 `tenant` 字段作入参兼容；`MetricQueryService.columnHint/filterLabel` 删 tenant 分支；响应体 `scope` 注释去“租户”。
- **Phase 1 手写 Mapper 不拆 tenant 形参**：`ExpenseStatMapper`/`MemberStatMapper` 仍作 `MetricsAsCodeMigrationReconciliationTest` 的**独立对账 oracle**，为保持两边写法独立、不破坏逐值对账，只加 javadoc 标明“已废弃、调用方必须传 null、不再被 MCP 工具调用”。
- **对账基线外置**：新增测试作用域类 `BaselineFixtures`（SafeConstructor 读 `classpath:baseline/crm_dev-baseline.yaml`）+ JUnit `Assumptions` 库名守卫（连非 crm_dev 库时**跳过**而不是误报口径失败）。硬编码数值（旧 5000 样本口径）全部改为读基线。
- **AGE_DIST 改阴历**：`member_yin_birthday` 入 `piiComputeAllowedColumns`（可作计算输入、禁原值输出，裸列仍拒）；`expr` 改用 `STR_TO_DATE(列,'%Y-%m-%d')` 后再 `TIMESTAMPDIFF`，阴历日期与阳历 `CURDATE()` 直比的 ±1 月误差已获业务接受。同时修正 YAML 写法：`>-` 折叠块续行必须同缩进才会折成单行 SQL。

### 同日补充决定（用户答复本轮收尾的两个待确认项）
1. **基线改为入库**。我曾建议不提交（理由是“数值属数据不属口径”），用户定为入库。实际顾虑更直接：不入库则新人克隆后真库测试直接报错、CI 无法复现。已执行：
   - `.gitignore` 删掉 `mcp-server/src/test/resources/baseline/` 规则，并在原位留注释说明“故意入库 + 不要重新加忽略 + 禁写个人级字段/凭证”；`git check-ignore` 已验证不再被忽略。
   - 入库前做了内容安检：基线只有聚合数值、门店编码/名、SKU/商品名，**无任何个人级字段**（无生日原值、无手机号、无地址），符合 AGENTS 第八节；重跑生成脚本后**业务数值逐项不变**（只有 `probeLatencyMs` 波动）。
   - 把“本文件随仓库提交 + 只允许聚合值”的约束写进生成脚本的头部输出，保证以后重生成不会丢这段说明。
2. **`scripts/probe/` 七个探测脚本全部保留**（含 `step0_baseline_full.py` 基线采集器）。已核对：**凭证均运行时从 `mcp-server/src/main/resources/.env` 读取，脚本内无任何硬编码密码/内网地址**，因此可安全入库；全程 `START TRANSACTION READ ONLY` + `rollback`。

### 业务口径变化
- 租户维度取消（不再支持 ≠ 不可用）；统计范式聚焦门店。
- 商品排行窗口：默认 18 个月 → **默认 3 个月、上限 92 天**；异常 SKU 正式入榜。
- 年龄：`age` 列与阳历生日均不可用 → **阴历生日**分桶。

### 修改文件
- 主代码：`mcp/tool/CrmAnalyticsTool.java`、`metrics/{MetricDefinition,MetricDefinitionParser,MetricDefinitionValidator,QueryPlanValidator,MetricQueryService}.java`、`entity/request/{MemberOverview,StationPerformance,ProductRanking,FilterItem}Request.java`、`entity/response/{MemberOverviewResult,StationPerformanceResult}.java`、`mapper/{ExpenseStatMapper,MemberStatMapper}.java`、`resources/metrics/{member,order}.yaml`。
- 测试：`SqlCompilerTest`、`MetricDefinitionValidatorTest`、`QueryPlanDefaultWindowTest`、`CrmAnalyticsToolTest`、`MetricsAsCodeMigrationReconciliationTest`、`Phase2MetricsReconciliationTest`、`Step0DataProbeTest`、`mapper/CrmAnalyticsMapperIntegrationTest`；新增 `metrics/BaselineFixtures.java`。
- 脚本/配置/文档：`scripts/probe/step0_baseline_full.py`、`.gitignore`、`docs/ai-agent/{BUSINESS_METRICS,PHASE1_TECH_PLAN,DEVELOPMENT_LOG}.md`。

### 测试与验证结果（均已实际执行）
本轮发现：**本机虽无独立 `mvn`，但 IDEA 自带 Maven 发行版可直接离线构建**，因此上一节“本轮没有跑任何 Java 测试”的限制已不成立。
```
JAVA_HOME=corretto-21.0.13
& "<IDEA>/plugins/maven/lib/maven3/bin/mvn.cmd" -o -B -pl mcp-server test
→ BUILD SUCCESS，Tests run: 75, Failures: 0, Errors: 0, Skipped: 0
```
分类：`CrmAnalyticsToolTest` 14｜`MetricToolInjectionTest` 10｜`MetricDefinitionValidatorTest` 16｜`QueryPlanDefaultWindowTest` 8｜`SqlCompilerTest` 12（以上不触库）；真库四个类：`MetricsAsCodeMigrationReconciliationTest` 3、`Phase2MetricsReconciliationTest` 5、`CrmAnalyticsMapperIntegrationTest` 3、`Step0DataProbeTest` 4（共 15）。
新增/改写的关键用例：`SqlCompilerTest.tenantFilter_noLongerCompiled`、`MetricDefinitionValidatorTest.noMetricReferencesTenantColumns`（遍历注册表断言无 tenant 列/编码）与 `maxRangeDays_validated`、`QueryPlanDefaultWindowTest.metricLevelMaxRangeDays_capsProductRanking`、`CrmAnalyticsToolTest.{station_rejectsTenantWithoutQuery,product_rejectsRangeOverMetricCap,product_notesTenantIgnored}`、`Phase2MetricsReconciliationTest.ageDist_matchesLunarOracle`（逐分桶比对阴历 oracle + 分桶求和=总数 + 非未知=阴历填充 2,766 + 阳历仍 100% 为空）。
本轮过程中真实发现并修复的两处问题（不是一次通过）：
1. **基线采集口径错**：`storeGroupCount` 用 `COUNT(DISTINCT member_store)` 会忽略 NULL，与指标/测试的 `GROUP BY member_store` 行数不等（**260 vs 261**，多出的正是 `member_store` 为 NULL 的一组）→ 改为直接数 `GROUP BY` 结果行，并新增 `distinctStoreCodes` 区分两个语义。测试先红后绿，不是改断言迁就数字。
2. **工具提前返回时 `items` 为 null**：`CrmAnalyticsToolTest` 一处 `isEmpty()` 断言失败 → 改 `isNullOrEmpty()` 并注释说明返回结构。
Step 0 只读探测新增事实（生日只输出粗粒度计数，不输出生日原值）：`age` 列仅 32 人有值且其中 30 人与生日计算偏差 >1 岁；阴历生日可解析值中年份最小为 0、<1900 共 26 行（均归“未知”桶），`future_dated`=0；`member_created` 最大值 2026-06-27、近 3 个月新增 **0** → 印证对账必须用固定历史区间而非“当前时间”。

### 未解决问题
1. 性别枚举 `0`（2 行）含义仍待确认；带性别维度的分组指标尚未新增（本轮未做）。
2. 会员消费次数、毛利：10-29/10-30 两轮均未答复，仍挂起。
3. 端到自然语言链路（模型选工具）仍未验证；数据权限（服务端身份→门店范围）仍未接入，仅限测试环境。
4. `docs/ai-agent/PHASE1_SCHEDULE.xlsx` 的 Day4 基准与 R5/R6 仍失真（本轮只同步了 markdown）。
5. ~~`scripts/probe/` 探测脚本去留待定~~ → **已定：保留**（并核对无硬编码凭证，可入库）。
6. ~~基线不入 Git 后需自行跑脚本~~ → **已定：基线入库**，新人克隆即可直接跑真库测试；口径变更时仍应用 `step0_baseline_full.py` 重生成并同一提交入库。

### 风险
- 92 天上限意味着“今年上半年商品排行”这类问题会被**确定性拒绝**，模型需向用户解释原因；若业务需要长窗口商品榜，只能走 DBA 加复合索引（需授权）或预聚合，不能靠放宽上限。
- `AGE_DIST` 可用比例仅 0.44%（未知 99.56%），作为对外指标的解释成本很高；是否继续对外输出仍建议业务再确认。
- 工具层拒绝 `tenant` 依靠入参非空判断；若模型改用自然语言描述租户（如“只算标杆酒企”），服务端不会报错而是按全部统计输出，需靠提示词与 `explainMetric` 口径文案引导。

### 下一步
- 等用户对基线数值（尤其 Top10 与合计）做业务复核；确认后本轮基线即为正式验收标准答案。
- 如需补性别维度分组指标，按配置化新增流程（新 YAML + golden SQL 断言 + 真库对账）执行。
- 启动端到端验证：mcp-client 自然语言 → 工具选择 → 结果解释，重点看超窗拒绝与 tenant 拒绝两条错误路径的话术。

## 2026-10-30（再续）：会员总览超时定位 + 演示阶段放宽超时上限

### 本次目标
`queryMemberOverview` 在真实库稳定报 error 3024（`MAX_EXECUTION_TIME(8000)` 被服务端到点终止）。先实测再决策，本次为纯分析→小改闭环。

### 探测与结论（已实际执行，`scripts/probe/step0_probe_member_overview_perf.py`，只读事务+rollback）
- 总览全量聚合真实耗时：**冷跑 9,291ms / 热跑 2,247ms**，旧 8s hint 上限刚好卡在冷跑临界，故表现为“偶发稳定失败”。
- 索引事实修正：`member_info` 的 `index_allot_store` 实际是 **`(del_flag, member_allot_store)`**（索引名误导），`del_flag` 去重值仅 1，选择性为零，全量聚合无法避免扫 62.8 万行；视图（非物化）对此类查询**零收益**，慢的根源是聚合必须读全量行，不是 SQL 写法。
- hint 生效验证：`MAX_EXECUTION_TIME(1)` 确实触发 3024，排除“保护机制失效”假设。
- 探测脚本 E 项遗留：单店过滤样本选中了 `member_allot_store IS NULL` 的分组（52,648 行门店为空，`NULL = NULL` 不成立返回 0 行），单店过滤的真实耗时**尚未测到**，后续如需引用应重测。

### 架构或技术决策
- **内部演示阶段放宽超时**（用户 2026-10-30 决策）：`MetricsLimits.QUERY_TIMEOUT_SECONDS` 10→30，`MAX_EXECUTION_TIME_MS` 8000→25000，保持旧值 8s/10s 的≈80% 比例（服务端 hint 先杀、客户端兜底）。未改用缓存/预聚合（备选方案仍在桌上，需要时再启动）。
- 同步变更：`SqlExecutor` 日志不再截取前 80 字符，改为打印**完整 SQL+参数**，并新增异常路径的 error 日志（超时查询此前无任何 SQL 留痕）。

### 业务口径变化
- 无。仅执行层超时上限变化，所有指标口径不变。

### 修改文件
- `metrics/MetricsLimits.java`（两个常量+注释）、`metrics/SqlExecutor.java`（日志）；新增 `scripts/probe/step0_probe_member_overview_perf.py`。均不涉及 CRM 库结构。

### 测试与验证结果（均已实际执行）
- `mvn -o -B -pl mcp-server test` → **BUILD SUCCESS，Tests run: 75, Failures: 0, Errors: 0, Skipped: 0**（含真库四个类）。
- 日志抽样可见 `MAX_EXECUTION_TIME(25000)` 已注入、完整 SQL 与参数正常输出；参数均为日期/门店编码，无 PII，符合 AGENTS 第八节。

### 未解决问题
1. `PRODUCT_RANKING` 的 92 天指标级上限**未因本次放宽而动**：18 个月窗口实测 75.8s，放宽到 30s 仍不够，如需长窗口商品榜只能走复合索引（需授权）或预聚合。注：该 75.8s 是在旧上限 10s 时代用放开超时的探测脚本测得，**尚未在 30s/25s 新上限下复测**；且当时根因是执行计划翻转（`index_state` ref + `Using temporary; Using filesort`），不是单纯窗口过大，不能假定放宽后能跑进 30s。
2. 会员总览的**首声仍要 ~9s**（模型侧表现为工具调用慢），演示阶段可接受；若要彻底消除需应用层缓存或本项目库预聚合（后者需遵守 AGENTS 第四条先过安全评估）。

### 风险
- 30s 上限 × 20 连接池极限并发时 DB 压力放大；演示场景并发低，风险可控，不上生产。
- 放宽后错误语义变化：原本 `QUERY_FAILED` 超时的查询可能返回慢结果，模型回复时间变长，需向业务说明“累计口径首查较慢属预期”。

### 下一步
- 若演示反馈“总览太慢”，优先上应用层缓存（TTL 5min）而非继续放宽超时。
- 生产化前重估：本决策仅对“内部演示+测试库”成立，量级/并发变化后需重新评估。

## 2026-10-30（再续）：路线 C —— 框架升级支持受控 LEFT JOIN，首个 join 指标 GENDER_CONSUMPTION_COMPARE

### 本次目标
回答“男的买的多还是女的买的多”，需把性别（`member_info`）作为分组维度跨表统计消费（`expense_calendar_info`）。经三路线对比（A 子查询/B 会员累计字段/C 框架升级），用户选**路线 C**：升级 `SqlCompiler` 支持受控 LEFT JOIN，“写对一次、全指标共享”。

### 修改文件
- 框架：`model/MetricDefinition.java`（新增 `Join` record）、`MetricDefinitionParser.java`（解析 join + YAML `on` 布尔键陷阱兼容）、`SqlCompiler.java`（`fromClause` 渲染 LEFT JOIN、`NUM_CALL` 正则支持限定列）、`MetricDefinitionValidator.java`（join 表/别名/mainAlias/ON 整串/PII 与限定列校验）。
- 配置：`resources/metrics/order.yaml`（新增 `GENDER_CONSUMPTION_COMPARE`）、`column-types.yaml`（`member_mobile` 入 `piiComputeAllowedColumns`）。
- 测试：`SqlCompilerTest`（join golden SQL）、`MetricDefinitionValidatorTest`（controlledJoin/关联列白名单用例）、`MetricToolInjectionTest`（计数 8→9）、`MetricsAsCodeMigrationReconciliationTest`（真库 join 对账）、`MetricQueryService.columnHint`。
- 脚本：新增 `scripts/probe/step0_probe_gender_join_perf.py`（保留为可复用工具）。

### 架构或技术决策
1. **受控 JOIN 而非自由 SQL**：只允许单一限定等值 ON、表白名单、别名不得顶替表名；声明 join 后列引用强制带限定符（mainAlias/alias），解决两表同名 `del_flag` 歧义。`member_mobile` 作关联键必须先入 PII 计算白名单，作为总闸。
2. **合计查询共用同一 FROM**：度量引用关联表列时合计 SQL 也必须带 join，不能走单表 FROM。

### 业务口径变化
- 新增一个**待业务复核**的对比口径（实付金额/订单数/去重买家数三分组）；由开发侧代拟，未擅自当作已确认事实。

### 探测与验证结果（均已实际执行）
- 不触库单测：`mvn -o -B -pl mcp-server test -Dtest=SqlCompilerTest,MetricDefinitionValidatorTest,QueryPlanDefaultWindowTest,MetricToolInjectionTest,CrmAnalyticsToolTest` → **BUILD SUCCESS，Tests run: 63**（golden SQL 已验证 `NUM(e.expense_realamt)` 正确展开为 `CAST(...)`）。
- 真库只读探测（`step0_probe_gender_join_perf.py`）：`member_mobile` 全局唯一（重复组=0）→ LEFT JOIN **零行放大**；固定窗口（06-01~06-27，185,228 单）join 分组冷跑 2,290ms / 热跑 2,037ms；三组求和=合计 113,923,128.62。EXPLAIN：会员侧 `index_mobile` 唯一回表 1 行，订单侧 `index_state`（低选择性）带 temporary+filesort。
- 真库回归：`mvn -o -B -pl mcp-server test -Dtest=MetricsAsCodeMigrationReconciliationTest` → **BUILD SUCCESS，Tests run: 4**（含新增 `genderComparison_joinMatchesOracleAndInvariant`：与独立 oracle SQL 逐组一致 + 三组求和=合计不变式全部命中）。
- 固定窗口标准答案（入 `baseline/crm_dev-baseline.yaml` 的 `genderComparison` 段）：男 57,034,077.17 / 88,914 / 44,791；会员性别缺失 52,426,862.92 / 89,923 / 38,283；女 4,462,188.53 / 6,391 / 3,043。

### 未解决问题
1. 本指标三度量选型（实付/订单数/去重买家）需业务复核是否为老板想要的对比维度。
2. **已定档（2026-10-30）**：未知桶保留且不拆分，未关联会员 + 匹配会员但 sex 非 1/2 合并标‘会员性别缺失’（依 §1.5 #9）；CASE ELSE 标签、golden SQL、oracle、基线注释与文档已同步。“会员性别缺失”占比 46% 仍属数据质量前提，“男的买的多”结论必须声明。
3. “会员数按性别分组”（单表）尚未新增，本轮只交付消费侧 join 对比。

### 风险
- 编译器仍为单点，join 渲染属新增高风险区域——靠 golden SQL 快照 + 真库逐组对账 + 行放大不变式三重缓解。
- 声明 join 后所有列引用必须带限定符，否则命不中同名列保护；配置书写约束变严，已由加载期校验拦截。

### 下一步
- 向业务复核三度量选型（实付/订单数/去重买家）；如需“会员数按性别分组”，在 `member.yaml` 新增单表带维度指标（不依赖 join）。

## 2026-10-30（再续）：`draftMetric` 确定性指标脚手架（降低“指标即配置”编写门槛）

### 本次目标
- 手写一段合规指标 YAML 门槛偏高（要记得 NUM()、PII 禁列、grain、join 限定符、写完还得重启才知对错）。新增一个**开发期** MCP 工具，把结构化的“指标意图”确定性地组装成 YAML 片段，即时跑一遍**加载期同一套校验 + 干跑编译**，产出 `yaml` + 样例 SQL + `validationErrors`，替代“写完到重启才知道哪里错”。

### 架构或技术决策
- **走确定性脚手架、不引入大模型生成**（用户拍板）：判定依据只有既有 `ColumnTypeRegistry` 与 `MetricDefinitionValidator`，全程**不连数据库**、不落盘、不注册、不生成运行期 SQL——与“运行期模型现场生成 SQL 未经校验不得执行”红线保持隔离，只是把 §8.1“开发期生成配置→干跑自检→对账→提交”自动化。
- **复用而非新造校验**：脚手架组装 `MetricDefinition` 后直接调用 `MetricDefinitionValidator.validate(...)`，产出等价于“能否启动”，杜绝规则漂移。
- **`status` 缺省一律 `UNCONFIRMED`**（除显式 CONFIRMED）：起草不自动“看起来权威”，符合 AGENTS 第五节口径不能猜。`caliber` 必填、由人工提供，脚手架不臆造业务语义。
- **确定性“做对”的部分**：VARCHAR 金额命中登记表自动包 `NUM()`；`grain` 缺省按主表硬映射推断（表决定行粒度，非业务猜测）；join 的 ON 由 `mainAlias.onLeftCol = joinAlias.onRightCol` 组装并在 YAML 给 `"on"` 键加引号（规避 SnakeYAML `on` 布尔键陷阱）。**join 场景的其它列引用不猜归属表**，要求调用方自行带限定符，越界写法由校验器拦截。
- **受控 YAML emitter**：安全标识符（表/列/别名/编码）直通、其余加引号并转义，保证产出可被 `MetricDefinitionParser` 再解析（闭环由单测断言）。

### 修改文件
- 新增 `entity/request/MetricDraftRequest.java`（结构化草稿入参 + 嵌套 spec）。
- 新增 `metrics/MetricDrafter.java`（组装 + 自动 NUM + join ON 组装 + status 缺省 + 复用校验 + 干跑编译 + YAML 产出）。
- `mcp/tool/MetricTool.java` 增 `@Tool draftMetric`，构造新增注入 `MetricDrafter`（`ToolConfiguration` 无需改动，方法自动暴露）。
- 测试：新增 `metrics/MetricDrafterTest`（12 项，不连库）；`MetricsFixtures` 加 `drafter()` 工厂；`MetricToolInjectionTest` 适配新构造签名（2 处）。

### 测试与验证结果（均已实际执行，不触库）
- `mvn -o -B -pl mcp-server test -Dtest=MetricDrafterTest,MetricToolInjectionTest,SqlCompilerTest,MetricDefinitionValidatorTest` → **BUILD SUCCESS，Tests run: 53**。
- 全量回归 `mvn -o -B -pl mcp-server test` → **BUILD SUCCESS，Tests run: 91**（真库对账/探测在 Spring 上下文加载 `MetricDrafter` Bean 后不受影响）。
- 脚手架闭环用例：产出的 YAML 经 `MetricDefinitionParser` 再解析并过 `Validator` 无错（单表 + join 两例覆盖）；PII 原值维度、未知表、caliber 缺失、编码重名、非法 NUM 均被 `validationErrors` 拦下。

### 未解决问题
- `draftMetric` 的入参 JSON schema 较重，模型侧一次填全有难度；后续可考虑“先列字段清单再分步补”的引导，但属体验优化，非正确性缺口。
- 脚手架只保证“配置合法 + 可编译”，**不保证口径正确**：产出默认 `UNCONFIRMED`，仍需人工补 caliber 语义并写对账测试后才可 CONFIRMED 提交。

### 风险
- YAML emitter 是新写代码路径，转义/流程上下文边界（如值含 `,`/`:`/引号）依赖“非安全即加引号”规则；已由再解析闭环单测兜底，后续扩展 emitter 须同步补测。

### 下一步
- 若要批量/离线生成，可加薄封装让 `MetricDrafter` 直接落盘到指定 yaml（当前刻意不落盘，保持人审闸门）。

## 2026-11-02：同步要客（VIP）业务定义与会员池体系

### 本次目标
- 从 CRM 会员池脑图（`docs/CRM-会员池.xmind`）读取要客定义及相关业务规则
- 将 `member_info_view` 表结构（技术提供）整理入档
- 更新项目文档，为后续要客相关工具开发提供业务依据

### 已完成事项
1. 解析脑图获取完整会员定义体系：
   - **普通会员**：没有消费或消费不满29.9元，系统记录但不显示在手机中
   - **有效会员/非要客**：1年半内消费29.9元以上
   - **要客**：由店员或店长在个人池中主动点击跟踪变成要客，每人有数量上限（运营部后台配置）

2. 获取要客踢出规则（满足任意一条即踢出）：
   - P1：同一导购名下370天内未出单的要客
   - P2：35天内没有30秒以上电话沟通或无微信有效沟通
   - P3：50天内没有30秒以上电话沟通或无微信3次以上有效沟通

3. 获取会员数据池流转规则：
   - 门店池 → 个人池 → 要客/废弃池
   - 区域池：非要客 + 20天内无20秒以上呼出 + 历史消费>500 + 注册>7天 + 7天以上未被跟踪
   - 获客池：代驾池/外卖池/地推池/车辆采集池

4. 识别 `member_info_view` 表中与要客相关的核心字段：
   - `track`（0=未跟踪/1=跟踪）、`track_user_id`（跟踪人ID）、`track_time`（追踪时间）
   - `track_cancel_time`（踢出时间）、`track_cancel_reason`（踢出原因）
   - `importan_pond_user_type`（0=正常/1=池中）

### 修改文件
- `docs/ai-agent/DATABASE_SCHEMA.md`：新增 §3.4 `member_info_view` 表说明，含要客相关字段、会员池字段、归属分配字段、消费统计字段、回访记录字段分组说明
- `docs/ai-agent/BUSINESS_METRICS.md`：新增 §六 会员定义体系（含要客定义、踢出规则、字段映射、数据池流转、获客池定义、会员周期营销方案），后续章节编号顺延

### 业务口径变化
- **要客判定**：`track = 1` 即为要客，非通过消费金额自动判定
- **要客归属**：通过 `track_user_id` 关联到具体导购/店员
- **踢出规则**：370天未出单 / 35天无有效沟通 / 50天无充分沟通，满足任一即踢出
- **数量上限**：每个导购的要客数量上限由运营部后台配置，非数据库字段

### 未解决问题
1. P2/P3 踢出规则中的"电话沟通"和"微信有效沟通"记录存储在哪张表？需要 CRM 技术侧确认
2. "出单"的判定标准是什么？是否等同于有效消费订单（`expense_state='10'`）？
3. 要客数量上限的配置存储位置？是否有对应的配置表？
4. `importan_pond_user_type`（获客池标记）与 `track`（要客标记）的关系？是否互斥？
5. 区域池的"20天内无20秒以上呼出"记录来源？是否有通话记录表？

### 风险
- 踢出规则 P2/P3 涉及沟通记录，如果沟通记录表未接入，则无法在 Agent 中完整实现踢出状态查询
- `member_info_view` 通过触发器与 `member_info` 同步，需确认触发器是否覆盖所有要客相关字段

### 下一步
- 确认沟通记录相关表结构（通话记录、微信沟通记录）
- 确认"出单"判定标准
- 设计要客查询工具（如：查询某导购名下要客列表、要客踢出风险分析）
- 考虑是否将要客状态变化纳入会员活跃/流失分析体系

## 2026-11-02：实现要客统计指标配置（Phase 3）

### 本次目标
- 实现要客总数、新增/踢出数、按导购/门店统计、消费分析等指标配置
- 将 `member_info_view` 添加到表白名单
- 更新业务指标文档

### 已完成事项
1. 在 `resources/metrics/member.yaml` 新增 6 个要客指标配置：
   - `VIP_OVERVIEW`：要客总览（快照）- 总数、累计消费、近一年半消费、人均消费
   - `VIP_NEW`：新增要客数（按 track_time 时间窗口）
   - `VIP_CANCELLED`：踢出要客数（按 track_cancel_time 时间窗口）
   - `VIP_BY_STAFF`：按导购统计要客数
   - `VIP_BY_STORE`：按门店统计要客数
   - `VIP_CONSUMPTION_BY_STORE`：按门店统计要客消费

2. 在 `resources/metrics/column-types.yaml` 添加 `member_info_view` 到表白名单

3. 更新 `docs/ai-agent/BUSINESS_METRICS.md` §6.7 记录已实现的要客指标

### 修改文件
- `mcp-server/src/main/resources/metrics/member.yaml`：新增 6 个 VIP 指标定义
- `mcp-server/src/main/resources/metrics/column-types.yaml`：添加 member_info_view 表白名单
- `docs/ai-agent/BUSINESS_METRICS.md`：新增 §6.7 已实现的要客统计指标

### 架构或技术决策
- **框架限制**：每个指标仅支持一个 `timeColumn`，因此要客总数（快照）、新增（按 track_time）、踢出（按 track_cancel_time）必须拆分为独立指标
- **数据来源**：统一使用 `member_info_view`（由触发器从 `member_info` 同步），而非直接查询 `member_info`
- **状态标记**：所有新指标初始状态为 `UNCONFIRMED`，待真库验证后升级为 `CONFIRMED`

### 测试与验证结果
- 尚未执行真库验证，指标状态为 `UNCONFIRMED`
- 需验证：`member_info_view` 表在测试库是否可访问、`track` 字段数据填充情况

### 未解决问题
1. `member_info_view` 在测试库中是否存在？是否与 `member_info` 结构完全一致？
2. `track` 字段的数据填充率如何？有多少会员当前被标记为要客？
3. `track_time` 和 `track_cancel_time` 的历史数据是否完整？
4. 导购与门店的关系如何确定？`track_user_id` 是否需要关联员工表获取导购姓名？

### 下一步
- 连接测试库验证 `member_info_view` 可访问性
- 执行 `VIP_OVERVIEW` 指标的真库对账测试
- 验证 `track` 字段数据填充情况
- 考虑是否需要添加导购姓名维度（需关联员工表）

## 2026-11-02：要客指标真库验证完成

### 本次目标
执行 Phase 3 要客（VIP）指标的真库对账验证，确认所有指标在 crm_dev 测试库上的计算结果与 oracle SQL 一致。

### 已完成事项
1. 修复 `VIP_CONSUMPTION_BY_STORE` 指标配置错误（`agg: SUM` → `agg: COUNT`）
2. 编写并执行 `VipMetricsReconciliationTest` 对账测试（12 个测试用例）
3. 更新 `MetricToolInjectionTest` 和 `MetricDefinitionValidatorTest` 以反映新增的 7 个 VIP 指标
4. 全部 103 个测试用例通过

### 修改文件
- `mcp-server/src/main/resources/metrics/member.yaml`：修复 VIP_CONSUMPTION_BY_STORE 的 VIP_COUNT 聚合类型
- `mcp-server/src/test/java/com/itzixi/metrics/VipMetricsReconciliationTest.java`：新增对账测试类
- `mcp-server/src/test/java/com/itzixi/mcp/tool/MetricToolInjectionTest.java`：更新指标计数断言（6→13, 9→16）
- `mcp-server/src/test/java/com/itzixi/metrics/MetricDefinitionValidatorTest.java`：更新指标总数断言（9→16）
- `docs/ai-agent/BUSINESS_METRICS.md`：更新 §6.7 指标状态为 CONFIRMED 并添加验证结果

### 架构或技术决策
- **指标状态升级**：所有 7 个 VIP 指标从 `UNCONFIRMED` 升级为 `CONFIRMED`
- **测试覆盖**：采用 `@SpringBootTest` + `JdbcTemplate` oracle SQL 对比模式，确保编译层结果与手写 SQL 一致
- **基线管理**：通过 `BaselineFixtures.assumeMatchesCurrentDb()` 确保测试仅在 crm_dev 库上执行

### 测试与验证结果
**测试环境**：crm_dev 测试库
**测试用例**：12 个对账测试全部通过
- VIP_OVERVIEW：2 个测试（快照数据 + 派生指标）
- VIP_NEW：1 个测试（时间窗口计数）
- VIP_CANCELLED：1 个测试（踢出计数）
- VIP_BY_STAFF：2 个测试（分组计数 + 合计一致性）
- VIP_BY_STORE：2 个测试（分组计数 + 合计一致性）
- VIP_CONSUMPTION_BY_STORE：2 个测试（分组消费 + 合计一致性）
- CrossMetricConsistency：2 个测试（跨指标一致性校验）

**实测数据（crm_dev）**：
- 要客总数：10,779
- 累计消费总额：98,485,021.43 元
- 近一年半消费总额：66,848,372.22 元
- 人均消费：9,136.75 元
- 涉及导购：484 人
- 涉及门店：192 家

**回归测试**：全量 103 个测试用例通过，无现有功能回归

### 未解决问题
1. `member_info_view`（428,930 行）与 `member_info`（628,528 行）行数差异约 20 万，需确认触发器 `ff_update_member_info_view` 的同步逻辑是否存在延迟或过滤条件
2. VIP 指标的消费数据与订单域指标的消费数据是否可比对？（口径不同：会员表累计字段 vs 订单表实付金额）

### 风险
- member_info_view 与 member_info 行数不一致可能影响数据完整性，需与 CRM 技术侧确认
- 要客踢出规则中的"沟通记录"（P2/P3）尚未接入，当前仅统计 track_cancel_time 非空的记录

### 下一步
- 确认 member_info_view 与 member_info 的数据同步机制
- 考虑是否需要添加导购姓名维度（需关联员工表）
- 探索要客流失预警分析（基于踢出规则 P1/P2/P3）
- 更新 crm_dev-baseline.yaml 添加 VIP 指标基线数据

## 2026-11-02：会员数据源口径变更（member_info → member_info_view）

### 本次目标
根据产品和技术对齐会议结论，将所有会员相关指标的数据源从 `member_info` 表统一切换为 `member_info_view` 视图。

### 已完成事项
1. 更新 5 个会员域指标的 `table` 配置：
   - MEMBER_OVERVIEW（会员总览）
   - MEMBER_NEW（新增会员数）
   - MEMBER_LEVEL_DIST（会员层级分布）
   - AGE_DIST（年龄分布）
   - GENDER_EXPENSE_TOTAL（按性别累计消费总额，状态 UNCONFIRMED）

2. 更新 1 个订单域指标的 JOIN 表配置：
   - GENDER_CONSUMPTION_COMPARE（男女消费对比）：JOIN 表从 `member_info` 改为 `member_info_view`

3. 更新对应的 `caliber` 说明，标注 2026-11-02 口径变更

4. 更新测试代码中的 oracle SQL：
   - Phase2MetricsReconciliationTest：3 处 oracle SQL 改为查询 `member_info_view`
   - MetricsAsCodeMigrationReconciliationTest：1 处 oracle SQL 改为查询 `member_info_view`
   - SqlCompilerTest：2 处 golden SQL 断言更新为 `member_info_view`

5. 更新手写 Mapper XML：
   - MemberStatMapper.xml：memberOverview 查询改为 `member_info_view`

6. 更新基线数据文件：
   - crm_dev-baseline.yaml：所有会员相关基线数值更新为 `member_info_view` 数据
   - 新增 genderComparison 段落（3 个性别分组的销售额基线）

7. 更新探测脚本：
   - step0_baseline_full.py：所有 `member_info` 查询改为 `member_info_view`

### 修改文件
**指标配置**：
- `mcp-server/src/main/resources/metrics/member.yaml`：5 个指标的 table 和 caliber 更新
- `mcp-server/src/main/resources/metrics/order.yaml`：GENDER_CONSUMPTION_COMPARE 的 join.table 更新

**测试代码**：
- `mcp-server/src/test/java/com/itzixi/metrics/Phase2MetricsReconciliationTest.java`：3 处 oracle SQL
- `mcp-server/src/test/java/com/itzixi/metrics/MetricsAsCodeMigrationReconciliationTest.java`：1 处 oracle SQL
- `mcp-server/src/test/java/com/itzixi/metrics/SqlCompilerTest.java`：2 处 golden SQL 断言

**Mapper**：
- `mcp-server/src/main/resources/mapper/MemberStatMapper.xml`：memberOverview 查询表

**基线数据**：
- `mcp-server/src/test/resources/baseline/crm_dev-baseline.yaml`：会员相关基线数值 + genderComparison 段落

**脚本**：
- `scripts/probe/step0_baseline_full.py`：所有 member_info 查询改为 member_info_view

### 架构或技术决策
- **数据源统一**：所有会员相关统计统一从 `member_info_view` 取值，不再直接查询 `member_info` 表
- **变更原因**：产品+技术对齐会议确认，`member_info_view` 是业务标准数据源
- **向后兼容**：框架层面无变化，仅修改指标配置和测试代码

### 业务口径变化
- **会员总数**：从 628,527（member_info）变为 428,929（member_info_view），差异约 20 万行
- **新增会员**：窗口内从 23,487 变为 16,863
- **性别消费对比**：JOIN 表变更，但订单表数据不变，仅会员信息来源于 view

### 测试与验证结果
- 全量 103 个测试用例通过
- 基线数据已重新采集（通过更新后的 step0_baseline_full.py 脚本）
- 所有对账测试的 oracle SQL 已同步更新

### 未解决问题
1. `member_info_view`（428,930 行）与 `member_info`（628,528 行）行数差异约 20 万的具体原因需 CRM 技术侧确认
2. 触发器 `ff_update_member_info_view` 的同步逻辑是否存在过滤条件或延迟

### 风险
- 数据源切换后，历史基线数值全部失效，已重新采集
- 如有外部系统依赖 member_info 表的指标结果，需通知相关方数据已变更

### 下一步
- 与 CRM 技术侧确认 member_info_view 与 member_info 的行数差异原因
- 监控切换后的指标数据质量
- 考虑是否需要添加数据源切换的审计日志

## 2026-11-03：Ad-hoc 语义查询引擎交付（Task 1-8）

### 本次目标
在既有固化指标之外，交付受控的 Ad-hoc 自助分析能力：语义层（metadata.yaml）+ 白名单校验 + 确定性 SQL 编译 + 3 个 MCP 工具 + 真库交叉对账，对齐 `.qoder/rules/crm-data-agent.md` §九 交付要求。

### 已完成事项（Task 1-7，提交 4e40600 → 164ed07）
1. **语义元数据与加载器**（Task 1）：`mcp-server/src/main/resources/semantic/metadata.yaml` + `SemanticRegistry`（含加载期表白名单/别名/标识符硬校验，Task 1b 补齐）。覆盖 2 个实体：
   - `member`（`member_info_view`，别名 mv）：5 维度（STORE_CODE/REG_STORE/STAFF/GENDER/STORE_NAME）、3 度量（MEMBER_COUNT/TOTAL_EXPENSE/YEAR_EXPENSE）、3 时间列（CREATED/TRACK_TIME/TRACK_CANCEL_TIME）、3 条件模板（IS_VIP/HAS_EXPENSE/CANCELLED）、基线过滤 `mv.del_flag='0'`；
   - `order`（`expense_calendar_info`，别名 e）：3 维度（STATION_CODE/STATION_NAME/ORDER_GENDER）、3 度量（ORDER_COUNT/SALES/BUYER_COUNT）、1 时间列（BUY_DATE）、基线过滤 `expense_state='10' AND del_flag='0'`；
   - 双向 LEFT JOIN 关系声明（关联键 `member_mobile` + joinFilters 口径注入）；全局约束 defaultLimit 100 / maxLimit 1000 / maxDimensions 5 / maxMeasures 10 / maxFilters 10。
2. **请求模型与校验器**（Task 2）：`AdhocQueryRequest`/`FilterSpec`/`MeasureSpec`/`TimeRangeSpec`/`AdhocResult` + `AdhocValidator`（操作符白名单 = != > < >= <= LIKE IS NULL IS NOT NULL、PII 禁输出、实体作用域检查、重复实体拒绝、derived 公式四则校验、显式 entity 必须已声明）。
3. **SQL 编译器**（Task 3-4）：`AdhocSqlCompiler`（值参数化、VARCHAR 数值自动 `NUM()` 展开、时间窗口左闭右开、`/*+ MAX_EXECUTION_TIME(25000) */`、LIMIT；跨实体 JOIN 仅按元数据声明方向渲染 joinFilters）。
4. **服务编排**（Task 5）：`AdhocQueryService`（validate→compile→execute→derived 度量 Java 补值），所有失败收敛为 errorCode + 中文 errorMessage 的 `AdhocResult`。
5. **MCP 工具**（Task 6）：`AdhocTool` 三个工具——`adhocQuery` / `listSemanticCatalog`（目录含 PII 标注）/ `explainAdhoc`（dry-run，不触库）。
6. **真库交叉对账**（Task 7）：`AdhocReconciliationIT` 五对用例与固化指标逐值相等（VIP 计数、VIP 消费+派生人均、192 门店分组整表相等、性别 join 分组相等）。
7. **交付文档**（Task 8，本条目）：四份文档更新（见下）。

### 修改文件
- 主代码：`com.github.yingli.adhoc` 包 11 个文件（`SemanticRegistry`/`AdhocValidator`/`AdhocSqlCompiler`/`AdhocQueryService`/`AdhocException`/`AdhocRegistryConfiguration` + `model` 下 5 个）+ `com.github.yingli.mcp.tool.AdhocTool`
- 配置：`mcp-server/src/main/resources/semantic/metadata.yaml`（新增）
- 测试：`com.github.yingli.adhoc` 包 7 个文件（6 个测试类 + `AdhocFixtures`）
- 文档：`docs/ai-agent/BUSINESS_METRICS.md`（新增 §十）、`SUPPORTED_METRICS.md`（新增"五、Ad-hoc 自助分析"）、`docs/ai-agent/PROJECT_CONTEXT.md`（已知能力 + 关键文件）、本日志

### 架构或技术决策
- **语义层一次构建，扩展只改 YAML**：新增可查列/维度/度量/条件模板（如"非要客" `IS_NOT_VIP`）= 改 `metadata.yaml` 一行，列必须在 DDL 真实存在（加载期硬校验），Java 零改动。
- **AI 与业务逻辑分离**：模型只做意图理解与结构化参数组织；名字解析、权限边界、口径（joinFilters/baselineFilters）、确定性计算全部在 Java 服务端。
- **条件聚合只引用 conditionPresets**：不允许模型自造条件表达式，新增模板走 YAML 声明。
- **派生度量（人均/占比）由 Java `DerivedCalculator` 计算**（scale=2），不进 SQL。
- **与固化指标并存不替代**：已确认口径的业务问题仍走 `queryMemberMetric`/`queryOrderMetric`；等价性由真库对账证明。

### 测试与验证结果
**单元测试**（不连库，`@Test` 计数，无禁用用例）：
- `SemanticRegistryTest` 7 个、`AdhocValidatorTest` 11 个、`AdhocSqlCompilerTest` 12 个、`AdhocQueryServiceTest` 6 个、`AdhocToolTest` 4 个，共 40 个。

**真库集成对账**（crm_dev，`AdhocReconciliationIT`）：5 个测试全部通过，耗时约 13 秒——
1. VIP 总数：Ad-hoc（基线过滤 + IS_VIP 条件计数）== VIP_OVERVIEW `COUNT(*)`
2. VIP 消费与人均：条件聚合 + Java 派生 == 指标口径（逐值相等）
3. VIP_BY_STORE：192 门店分组行数 + 归一化映射整表相等（含空门店归并）
4. 性别 join：ORDER_GENDER × SUM(SALES) 与 GENDER_CONSUMPTION_COMPARE 分组键集 + 逐组金额相等（窗口取基线末端 7 天，见 11-03 下午条目「冷缓存」一项）
5. 工具注册：adhocQuery / listSemanticCatalog / explainAdhoc 三个工具已在 Spring 上下文注册

**全量回归**（Task 7 实跑记录，`./mvnw test -pl mcp-server`）：`Tests run: 143, Failures: 2`——141 通过；2 个失败为**既有已知问题**（`MetricDefinitionValidatorTest.realConfigsPassValidation`、`MetricToolInjectionTest.catalogScopedByDomain`，自 4e40600 基线起即存在，系此前独立提交新增第 17 个固化指标后断言计数未同步，与本 Ad-hoc 功能无关，另行修复）。

### 未解决问题
1. `member_info_view` 与 `member_info` 行数差异约 20 万仍未定论（沿 2026-11-02 条目，待 CRM 技术侧确认触发器同步逻辑）。
2. `member_sex=0` 的枚举含义待业务确认（DDL 仅定义 1 男/2 女）；Ad-hoc GENDER 维度当前将 0 与 NULL 一并归入"未知"组。
3. 既有 2 个测试失败（见上）待单独修复同步断言。

### 风险
- Ad-hoc 放开组合后，慢查询风险面扩大：join 聚合在无索引验证的情况下可能出现长查询。已有约束（LIMIT 1000 / MAX_EXECUTION_TIME 25s）兜底，但**上线前应对高频组合用 `explainAdhoc` 预演执行计划**。
- 测试库为离散月份块，Ad-hoc 时间窗口缺省值可能返回空结果，联调须显式传历史区间（与固化指标相同限制）。

### 下一步
- 端到端自然语言对话链路验证（mcp-client + 模型选择 adhoc 工具）。
- 与产品确认是否补充常用条件模板（如 IS_NOT_VIP）与高频组合清单。
- 服务端身份→数据权限接入后，评估 Ad-hoc 的门店范围过滤落点。

## 2026-11-03（下午）：边界修复 + 明细表（detail）实体接入与查询守卫

### 本次目标
把上午交付的 Ad-hoc 引擎推到"能回答明细表问题且不越界"：先修用户实测暴露的三处边界缺陷，再按业务裁定接入 `expense_calendar_details`。

### 已完成事项

**1. 空结果边界（提交 `8eb3ce9`）**
- 现象：5 月无记录时，模型自行联想改查其他月份/整年，输出用户没问的数字。
- 修法（三层，服务端事实优先）：`AdhocQueryService` 在结果为空时把口径标记补成"本区间无匹配数据（0 条）"；`MetricTool`/`AdhocTool` 描述要求"0 条即为答案，不得改窗口重查"；`ChatServiceImpl` 系统提示词固化边界。

**2. 门店名过滤 + filterGuidance（提交 `907eb6d`）**
- 现象：模型把"哈市长江路店"塞进 `expense_stationCode`（编码列），恒 0 条。
- 修法：`order` 实体 `filterableColumns` 增列 `expense_stationName`；引入实体级 `filterGuidance`（意图→列约定，随 `listSemanticCatalog` 透出，只指导选列、不进 SQL、不做权限）；IT 增加"名称过滤必须命中真实门店"的防回归断言。
- 同时评估：扩展 `filterableColumns` 是纯加法，不影响既有金额查询路径（金额列能否过滤由 `ColumnTypeRegistry` + NUM 展开决定，与过滤列清单无关）。

**3. 指标↔Ad-hoc 路由边界（提交 `e12ba08`）**
- 现象：一个"男性要客消费"问题先跑了两条 `VIP_BY_STORE` 指标 SQL（含合计行），再拉 500 门店清单反查编码，才走到正确查询。
- 修法：系统提示词第 7 条——口径不完全匹配就一次指标工具都不调，直接按目录走 `adhocQuery`；禁止为把名称换成编码而拉全量清单反查。

**4. detail 实体接入（本提交，TDD Task 12-15）**
- 业务裁定（2026-11-03，约束性）：① 允许 `expense_d_mobile` 参与关联查询；② 查明细行必须带时间范围或手机号或订单流水号，时间窗不得大于 92 天。
- `metadata.yaml` 新增 `detail` → `expense_calendar_details`（别名 ed）：6 维度 / 5 度量（`DETAIL_LINES`/`DETAIL_SALES`/`DETAIL_STD_AMT`/`DETAIL_QTY`/`DETAIL_BUYERS`）/ 时间列 `BUY_DATE` / 6 个可过滤列 / 3 条 filterGuidance；基线过滤列名**带别名限定**（`ed.del_flag`，否则与 member 同名列在 join 中歧义）。
- 关系只声明 **detail→member 单向** LEFT JOIN（`ed.expense_d_mobile = mv.member_mobile` + `joinFilters (mv.del_flag='0' OR mv.member_mobile IS NULL)`）。反向不开放：明细为中心反向 join 会把会员级快照金额按行乘翻（fan-out 有前科）。
- 新增 **`queryGuard`**（实体级查询守卫）：`requiredAnyOf: [timeRange, expense_d_mobile, expense_d_orderId]` + `constraints: {maxTimeRangeDays: 92, defaultLimit: 500}`。
  - `SemanticRegistry` 解析守卫（列名走加载期标识符校验）；
  - `AdhocValidator.validateQueryGuard` 在编译前裁决，只作用于主实体 `entities[0]`，preset 过滤按其声明列参与满足判定，违反返回可读 `INVALID_PARAM`；
  - `constraintInt(entity, key, fallback)` 让实体级约束覆盖全局（时间窗、缺省行数）；
  - `listSemanticCatalog` 透出 `queryGuard`，工具描述明示"服务端强制裁决"。
- PII：`column-types.yaml` 的 `piiComputeAllowedColumns` 增 `expense_d_mobile`（JOIN 键 / 定向条件 / `COUNT(DISTINCT)`，原值禁止输出与落日志）。
- **Java 侧零新增类**：除守卫解析与裁决外，明细表统计完全复用既有白名单/编译/执行链路——验证了语义层的扩展性。

**5. 两处执行层缺陷修复（本提交）**
- `SqlExecutor.withExecutionTimeHint` 改为幂等：编译器已自带 `/*+ MAX_EXECUTION_TIME */` 时不再重复注入——**重复 hint 在 MySQL 8 是语法错误 1064**，真库 IT 首跑即因此全线失败。
- MySQL 保留字：`AS lines` 在 crm_dev（8.0.21-5）直接 1064，对账测试别名统一改为 `line_cnt`（只读探测逐值验证：`alias_lines ERR / alias_line_cnt OK`）。
- 慢查询复核：`genderJoinMatchesMetric` 在整类同跑时顶到 25s 服务端上限（热缓存 ~10s、冷缓存 >25s），与口径无关。该例（及 detail join 例）窗口改为**基线末端 7 天**，两侧同窗口、逐值断言不变，让外部库缓存波动不再伪装成口径失败。未放宽生产侧 25s 上限。

### 修改文件
- 主代码：`adhoc/SemanticRegistry.java`（QueryGuard + 实体级约束）、`adhoc/AdhocValidator.java`（守卫裁决）、`adhoc/AdhocSqlCompiler.java`（实体级 defaultLimit）、`mcp/tool/AdhocTool.java`（守卫与 guidance 透出 + 描述）、`adhoc/model/AdhocQueryRequest.java`（limit 描述）、`metrics/SqlExecutor.java`（hint 幂等）
- 配置：`semantic/metadata.yaml`（detail 实体 + 守卫 + filterGuidance）、`metrics/column-types.yaml`（expense_d_mobile 计算授权）
- 测试：`SemanticRegistryTest`(9) / `AdhocValidatorTest`(16) / `AdhocSqlCompilerTest`(13) / `AdhocToolTest`(6) / `AdhocReconciliationIT`(9)
- 文档：`BUSINESS_METRICS.md` §十（3 实体、安全边界 #9 守卫与 #10 filterGuidance、已知限制）、`SUPPORTED_METRICS.md` §五（4 条明细示例 + 守卫说明）、`PROJECT_CONTEXT.md`（关键文件）、本日志

### 测试与验证结果
- `./mvnw test -pl mcp-server`：**Tests run: 154, Failures: 0, Errors: 0**（上午基线 143 跑 2 失败；那 2 处既有断言计数已在 `ab9b6b2` 随指标总数 16→17 同步修复，本批 154 全绿）。
- `AdhocReconciliationIT` 9/9（22s）：detail 单表行数/实付金额 vs 同刻独立 oracle（NUM 展开逐值相等）、detail→member LEFT JOIN 分组求和 == 单表（不乘行）、守卫负例（无定向条件被拒 + 181 天窗被拒"181 天 > 上限 92 天"）。
- 全程只读，无写操作；输出仅汇总数字，无行级个人数据。

### 未解决问题
1. `detail` 行级金额（`expense_d_realamt`/`expense_d_stdtotal`）求和是否等于订单级实付，**未经业务确认**，暂不对外引用。
2. VARCHAR 金额列作为**过滤条件**（`>`/`<` 比较）仍需 filterSql NUM 展开补齐（`expense_d_realamt` 当前不可作范围过滤）。
3. `member_info_view` vs `member_info` 约 20 万行差异仍未定论（沿 11-02 条目）。

### 风险
- 明细表 1,529 万行，守卫窗口内单查询实测 1~8s；92 天上限是服务端强制，但守卫只认主实体，若后续把 detail 放到非主位需重新评估落点。
- 冷/热缓存导致的 2~3 倍耗时波动是外部测试库固有属性，25s 上限下仍可能出现偶发超时；生产化前应用 `explainAdhoc` 对高频明细组合预演执行计划。

### 下一步
- 端到端复测：明细类自然语言问题（含守卫负例）在 mcp-client 对话链路上的表现。
- 与业务确认明细金额口径后，再决定是否把 `DETAIL_SALES` 升为固化指标。
- 分支 `crm_agent_dev` 累计提交未推送，等待用户决定 push/PR/演示。

## 2026-11-03（晚）：多组合查询支持（member→detail、粒度守卫、批量查询）

### 本次目标
让 Ad-hoc 引擎支持用户提出的三类组合（会员+明细算人均明细消费、会员+订单按门店、会员+明细按商品），同时保留"会员+订单+明细三表信息"的可用路径，且不因 fan-out 放大指标。

### 业务/交互决策（用户当日确认）
1. 多个组合的结果形态：**多组独立结果**（`adhocQueryBatch`），不做跨粒度同排拼接。
2. 会员身份：**按会员属性分组**（门店/性别/导购等），不输出手机号等原值。
3. 销售额口径：**明细行实付** `DETAIL_SALES`（`expense_d_realamt`），不是订单头 `SALES`。
4. `expense_d_orderId = expense_orderId` 的唯一性：**未确认，先只读核查**；核查通过前不声明 `detail→order` 关系。

### 已完成事项

**1. 语义元数据（`semantic/metadata.yaml`）**
- 所有含 `COUNT(*)` 的实体新增 `countKey`（member=`member_mobile`、order=`id`、detail=`id`）。
- 所有关系新增 `cardinality`：member→order/detail 为 `to_many`，order→member、detail→member 为 `to_one`。
- 新增 **member→detail** 关系：`mv.member_mobile = ed.expense_d_mobile` + `joinFilters (ed.expense_d_state='10' AND ed.del_flag='0')`。
- `detail→order` 暂不声明，元数据内注明待流水号唯一性核查；三表信息由批量拆块提供。

**2. `SemanticRegistry` 加载期硬校验**
- 解析并校验 `countKey`；含 `COUNT(*)` 度量但缺 `countKey` → 拒绝启动。
- 解析 `cardinality`，只接受 `to_one` / `to_many`。
- 关系目标实体必须存在，否则拒绝启动（避免运行期才暴露写错的实体名）。

**3. 粒度守卫（`AdhocValidator.validateMeasureGrain`）**
- 实体链必须**先一对多、后一对一**；出现"先 `to_one` 再 `to_many`"（如 detail→member→order）直接拒绝，提示改链顺序或拆块。
- 一次查询只能使用**行粒度实体**的度量：member→detail 只能选 detail 度量，member→order 只能选 order 度量；混粒度返回 `INVALID_PARAM` 并提示 `adhocQueryBatch`。

**4. 链上查询守卫（`validateQueryGuard`）**
- 守卫从"只检查主实体"扩展为**检查链上每个带 `queryGuard` 的实体**。
- 因此 `entities=[member, detail]` 时 detail 仍必须满足时间范围/手机号/流水号之一，且时间窗 ≤92 天；时间范围需显式写 `entity=detail`。

**5. 编译器（`AdhocSqlCompiler`）**
- **ON/WHERE 分离**：主实体基线、用户过滤、主实体时间窗进 WHERE；关系 `on`、`joinFilters`、**被 join 实体的时间窗**进 ON，保持 LEFT JOIN 语义（主表无匹配行不被吃掉）。
- **join 侧计数修正**：可空侧的 `COUNT(*)` 会把 NULL 扩展行计成 1，改为 `COUNT(alias.countKey)`；主实体 `COUNT(*)` 不变。
- 参数顺序与最终 SQL 文本顺序一致。

**6. 批量查询工具**
- 新增请求模型 `AdhocBatchRequest` 与工具 `adhocQueryBatch`：一次最多 5 个查询块，按顺序返回等长结果；单块失败只影响该块（`errorCode` + 中文原因），块之间不 join。
- `listSemanticCatalog` 的度量增加 `grain`、关系增加 `cardinality`，模型可据此判断能否同块。
- `ChatServiceImpl` 系统提示词新增第 8、9 条：单块单粒度；member 关联 detail 必须显式落 detail 守卫；三表信息拆块（明细+会员用 `DETAIL_SALES`，订单+会员用 `SALES`）。

### 修改文件
- 主代码：`adhoc/SemanticRegistry.java`、`adhoc/AdhocValidator.java`、`adhoc/AdhocSqlCompiler.java`、`adhoc/AdhocQueryService.java`、`adhoc/model/AdhocBatchRequest.java`（新增）、`mcp/tool/AdhocTool.java`、`mcp-client` 的 `ChatServiceImpl.java`
- 配置：`semantic/metadata.yaml`
- 测试：`AdhocValidatorTest`(24)、`AdhocSqlCompilerTest`(16)、`AdhocQueryServiceTest`(12)、`AdhocToolTest`(9)、`SemanticRegistryTest`(13)

### 测试与验证结果
- 已实际执行（不连库）：
  - `./mvnw -pl mcp-server test -Dtest='Adhoc*Test,MetricDefinitionValidatorTest,MetricDrafterTest,QueryPlanDefaultWindowTest,ReadOnlySqlGuardTest,SqlCompilerTest,CrmAnalyticsToolTest,MetricToolInjectionTest'` → **Tests run: 175, Failures: 0, Errors: 0, Skipped: 0**，BUILD SUCCESS。
  - `./mvnw -q -DskipTests compile`（全模块）→ 成功。
- 覆盖点：member→detail 守卫缺失被拒、detail 时间窗放行、**守卫只认等值定向（LIKE/IS NULL/!= 不放行）**、混粒度拒绝、复合链拒绝、join 侧 `COUNT(countKey)`、时间窗 ON/WHERE 落点、批量顺序/错误隔离/空与超限、口径行时间范围、目录 grain/cardinality、加载期 cardinality/countKey/关系目标校验、**MySQL 3024 超时识别与错误码收敛**。
- **未执行**：`@SpringBootTest` 真库测试（`AdhocReconciliationIT` 及各 Reconciliation/Mapper 测试）需要连接配置与授权；本次未连接数据库、未执行任何 SQL、未读取凭证。

### 未解决问题
1. **`expense_d_orderId` 唯一性未核查**：在核查通过前不声明 `detail→order`。建议只读验证 SQL（需授权后执行，先跑近期窗口再看是否全量）：
   ```sql
   -- ① 订单头流水号是否唯一（有效订单）
   SELECT COUNT(*) AS dup_keys, SUM(cnt) AS rows_in_dup
   FROM (
     SELECT expense_orderId, COUNT(*) AS cnt
     FROM expense_calendar_info
     WHERE expense_state = '10' AND del_flag = '0'
       AND expense_orderId IS NOT NULL AND TRIM(expense_orderId) <> ''
     GROUP BY expense_orderId
     HAVING COUNT(*) > 1
   ) t;

   -- ② 近期窗口内明细流水号能否在订单头唯一命中（有界扫描）
   SELECT COUNT(*) AS detail_rows,
          SUM(CASE WHEN h.expense_orderId IS NULL THEN 1 ELSE 0 END) AS orphan_rows
   FROM expense_calendar_details d
   LEFT JOIN expense_calendar_info h
     ON d.expense_d_orderId = h.expense_orderId
    AND h.expense_state = '10' AND h.del_flag = '0'
   WHERE d.expense_d_state = '10' AND d.del_flag = '0'
     AND d.expense_d_buyDate >= '2026-10-01'
     AND d.expense_d_buyDate <  '2026-11-01';
   ```
   ① 若 `dup_keys=0` 且 ② 孤儿率可接受，才可把 detail→order 声明为 `to_one`。
2. `member→detail` 中会员属性按明细行重复展开：只能用于"按会员属性分组"，不得同时选会员快照度量（已由粒度守卫拦截）。
3. member→detail 使用 LEFT JOIN + detail 时间窗在 ON：窗口内无明细的会员会以 detail 计数 0 的行出现；模型需按 0 如实转述，不得据此改窗口。

### 风险
- 批量最多 5 块会放大连接与执行压力；单块仍受 LIMIT/60s 上限约束。
- 明细表 1500 万+ 行，member→detail 的 join 聚合在冷缓存下可能接近 60s；上线前用 `explainAdhoc` 预演高频组合。
- 守卫与粒度规则均为服务端强制；模型侧提示词只降低试错概率，不承担安全职责。

### 下一步
- 获得授权后执行上述唯一性核查 SQL；通过后再声明 `detail→order` 并补对账测试。
- 端到端复测三类组合的自然语言问题（含守卫负例、空结果、批量中单块失败）。
- 真库集成测试需在确认连接配置与授权后运行。

### 补充：查询超时语义（同日）

**现象**：真库联调出现 `error code [3024] Query execution was interrupted, maximum statement execution time exceeded`。这是 MySQL 执行层按编译产物中的 `/*+ MAX_EXECUTION_TIME(25000) */` 主动杀查询，不是连接/凭证故障；25 秒是既有服务端硬上限。

**修改**
- `SqlExecutor.isExecutionTimeout`：识别 MySQL `3024`/`1317`、`SQLTimeoutException` 及驱动超时文案（含 cause 链）。
- `AdhocQueryService`：超时收敛为 `ADHOC_QUERY_TIMEOUT`，消息明确"超过 25 秒上限，请缩小时间范围或增加等值过滤后重查；不要用相同参数重复查询"；其它 DB 异常仍是 `ADHOC_EXEC_FAILED`。
- `MetricQueryService`：超时返回 `QUERY_FAILED` + 超时专用消息（缩小范围），与通用 DB 故障区分。
- `SqlExecutor` 日志：超时按 warn 记录完整 SQL/参数/耗时，不再打印整段堆栈；非超时错误仍按 error 带堆栈。
- `AdhocTool` 描述与 `ChatServiceImpl` 系统提示词第 10 条：收到超时只能缩小窗口/加等值过滤后重查一次，禁止原参数重复或用其他口径凑数。

**验证**
- 新增 3024 超时单测（`AdhocQueryServiceTest.executorTimeout`，含 cause 链识别与错误码断言）；非数据库单测 **175 个全部通过**；全模块 compile 成功。
- **未验证**：未连真库复跑超时场景；未查看实际超时 SQL 的执行计划（需从日志 `完整SQL=` 取得）。若宽窗口（如明细 92 天）在测试库仍超时，属库容量/访问路径问题，需要在 `explainAdhoc` + `EXPLAIN` 上单独评估，不能靠放宽 25 秒上限掩盖。

### 补充：超时上限 25s → 60s（同日，用户决策）

**决策**：用户要求把服务端执行上限拉长到 60 秒。

**修改**
- `MetricsLimits.MAX_EXECUTION_TIME_MS`：`25000` → `60000`（编译产物与执行层 hint 同步生效）。
- `MetricsLimits.QUERY_TIMEOUT_SECONDS`：`30` → `65`（客户端兜底略大于服务端，仍由 hint 先杀）。
- 超时错误消息、`AdhocTool` 描述、`ChatServiceImpl` 系统提示词、golden SQL 与超时断言同步为 60 秒。

**影响与风险**
- 单条慢查询占用连接与数据库资源的时间上限翻倍；批量 5 块最坏情况会显著拉长总耗时。
- 这是放宽兜底，不是性能修复；宽窗口明细查询仍应通过缩小范围/加等值过滤控制扫描量。生产前需按真实负载重估该值。

**验证**
- 非数据库单测 **175 个全部通过**；全模块 compile 成功。
- 未连真库复跑；60 秒上限下的实际耗时与执行计划仍未验证。

### 补充：MCP 客户端请求超时 20s → 340s（同日）

**现象**：拉长服务端上限后，客户端先报
`java.util.concurrent.TimeoutException: Did not observe any item or terminal signal within 20000ms in 'source(MonoDeferContextual)'`。
这不是服务端 3024，而是 Spring AI MCP Client 的请求超时默认 20 秒，先于服务端 60 秒上限掐断了工具调用。

**修改**
- `mcp-client/src/main/resources/application-dev.yml`：`spring.ai.mcp.client.request-timeout: 340s`。
- 取值依据：单块最坏为 hint 60s + JdbcTemplate 兜底 65s；`adhocQueryBatch` 一次调用串行最多 5 块，最坏 5×65s=325s，加回传余量取 340s。不能用 75s——那仍会截断合法的批量结果。
- 三档关系：编译产物 hint 60s < JdbcTemplate 兜底 65s < MCP 请求 340s，保证服务端总能先返回语义化错误而不是被客户端静默截断。

**验证与限制**
- 配置项已通过 Spring AI 1.0.0 配置元数据核对（默认 20s，属性名 `spring.ai.mcp.client.request-timeout`）。
- 未连真库复跑；批量 5 块全慢时用户最长等待约 5 分半，交互体验未验证。生产 profile 当前未启用 MCP client，若后续启用需同步该值。

### 补充：门店过滤编码优先——info/details 移除名称过滤白名单（同日，用户决策）

**决策**：用户指出 `expense_calendar_info`/`expense_calendar_details` 的门店过滤应尽量用门店编码列（有 `index_stationCode`），名称模糊查会出慢 SQL。

**根因**：两个大表的 `expense_stationName`/`expense_d_stationName` 均无索引，`LIKE '%x%'` 与等值都是全表扫描（明细表 1,529 万行最危险）；此前 filterGuidance 还引导"名称→名称列 LIKE"，等于教模型踩慢查询。

**修改**
- `metadata.yaml`：order/detail 的 `filterableColumns` 移除名称列（服务端硬拒绝）；member 的 `filterableColumns` 增加 `member_allot_stationName`（有 `index_allot_stationName`，供名称→编码解析）；三实体 `filterGuidance` 重写为"编码等值优先，名称→编码先走 member 解析，解析不到/多候选向用户索取编码"。
- `AdhocValidator`：已声明维度但不在过滤白名单时，拒绝话术区分"仅可输出、不可过滤"并回读该实体可过滤列（维度按物理列匹配，不是按维度名）。
- `AdhocTool.adhocQuery` 描述与 `ChatServiceImpl` 系统提示词：新增"门店过滤一律编码优先"边界（原第 7 条"名称条件直接用名称列过滤"作废，规则重新编号为 8/9/10/11）。
- 门店名称列仍可作维度输出（`STATION_NAME`/`DETAIL_STATION_NAME`，MAX 展示），只是不可过滤。

**测试**
- `AdhocValidatorTest`：`stationNameFilterable` 改为 `stationNameNotFilterable`（断言拒绝并提示 `expense_stationCode`），新增 `memberStationNameFilterable`（member 名称过滤放行）。
- `AdhocToolTest`：目录过滤列断言改为 order 只有 `expense_stationCode`、member 含 `member_allot_stationName`；filterGuidance 断言同步。
- `AdhocReconciliationIT`：原"名称 LIKE 命中真实门店"冒烟改为三段式——① order 名称过滤被拒 `INVALID_PARAM`；② member 按 `member_allot_stationName` 解析出编码；③ 用编码等值查 order 命中真实门店。**真库 9/9 通过（50.6s）**。
- 非数据库单测 76/76（Adhoc*/SemanticRegistry）。

**已知限制**
- member 的 `member_allot_stationName` 与 expense 表 `expense_stationName` 的取值空间是否完全同源未做核查：member 只覆盖有会员归属的门店，纯交易无会员的门店可能解析不到编码——届时按指引向用户索取编码，不猜测。
- 名称解析用 `LIKE '%x%'` 时 member 视图仍会扫描（前缀匹配可用索引）；指引要求等值/前缀优先。

### 补充：过滤列 indexed 标注进语义目录（同日）

**背景**：用户问"把'adhoc 组 SQL 尽量靠索引字段'写进提示词能否大概率避免慢 SQL"。结论：提示词只降低概率（本项目历次经验是模型侧约束最后都要服务端兜底），更可靠的是把索引事实结构化进目录——模型组参时读的是 `indexed` 布尔，不是散文。"走不到索引就让用户等"已是现状（60s hint 兜底 + `ADHOC_QUERY_TIMEOUT`）。

**修改**
- `metadata.yaml`：三实体新增 `indexedColumns`（以 `DATABASE_SCHEMA.md` 原始 DDL 为准）——member 全部 5 个过滤列（member_info 有 index_allot_store/index_store/index_allot_stationName/index_track/index_sex）；order 仅 `expense_stationCode`；detail 为 stationCode/gdcode/mobile/orderId，**`expense_d_commCodeName` 不在索引清单**（目录标 false，提示模型优先时间窗/编码收敛）。
- `SemanticRegistry`：解析 `indexedColumns`，加载期硬校验——标识符合法 + 必须 ⊆ `filterableColumns`（给白名单外的列标索引既无意义也会误导）；`EntityDef.indexed(column)` 便捷判定。
- `AdhocTool.listSemanticCatalog`：`filterableColumns` 逐列附 `indexed: true/false`；工具描述说明"true=优先用它收敛扫描量，false=可能扫全表，仅在守卫/定向条件兜住时使用"。
- 纯标注，不参与服务端裁决：编译/校验行为不变。

**验证**
- `SemanticRegistryTest` +2（真实元数据 indexed 与 DDL 一致性断言；indexedColumns 引用白名单外列拒绝加载）；`AdhocToolTest` 目录断言升级为 name+pii+indexed 三键。非数据库单测 **78/78**；全模块 compile 通过。
- **未验证**：member 侧索引按 `member_info` 的 DDL 推断（视图由触发器同步，`member_info_view` 自身索引清单未在文档中，需真库 `SHOW INDEX FROM member_info_view` 复核）；模型实际选择行为未端到端复测。

### 补充：性别第三桶展示名“会员性别缺失”→“未知性别”（同日，用户决策）

**决策**：仅改展示标签，分组语义不变（未关联会员 + 匹配会员但 sex 非 1/2 仍合并第三桶、不拆分，2026-10-30 定档继续有效）。

**修改**
- 固化指标：`metrics/order.yaml`（`GENDER_CONSUMPTION_COMPARE` caliber/expr/注释）、`metrics/member.yaml`（两条性别指标 caliber/expr/`nullDimensionLabel`）。
- 语义元数据：`semantic/metadata.yaml` 的 `ORDER_GENDER`/`DETAIL_GENDER` expr ELSE 分支。
- 测试与基线：`SqlCompilerTest` golden、`MetricsAsCodeMigrationReconciliationTest` oracle SQL 与断言键、`AdhocReconciliationIT` 断言文案、`crm_dev-baseline.yaml` 注释（基线数值存 `unknown` 键，不受影响）。
- 文档：`BUSINESS_METRICS.md`/`METRICS_MAPPING.md` 活文档同步并标注变更日期；本日志历史条目与 PHASE2/plan 历史文档保留旧名不改。

**验证**
- 非数据库单测全绿；`MetricsAsCodeMigrationReconciliationTest`（真库）随全量测试复核性别桶数值与基线逐值一致。

### 补充：全量文档对齐（同日晚）

将当日全部改动维护进活文档，并核对与技术方案的偏差：
- `PROJECT_CONTEXT.md`：已交付能力补 `adhocQueryBatch`（工具 3→4）与当日四条关键约定（detail 守卫/门店编码优先/indexed 标注/超时三档）；关键文件描述同步（AdhocBatchRequest、countKey/cardinality/indexedColumns）。
- `METRICS_CONFIGURATION.md`：超时阈值"执行层 10s 上限，hint 25s"→"hint 60s / JdbcTemplate 65s"（旧值自最初执行层 10s 起已两轮过期）。
- `BUSINESS_METRICS.md` §10.2 性能注：25s/30s 上限表述 → 60s/65s。
- `plans/2026-11-03-adhoc-semantic-query-engine.md`：不回改正文（保留编写时状态），在 Global Constraints 后新增"实施后演进对齐"一节，列出 6 项实现与计划的差异（批量工具、超时三档、detail 守卫、ON/WHERE 分离、门店编码优先+indexed、性别标签）。
- 历史日志与 PHASE2/PHASE1 计划正文按约定不回改；活文档以本日志各条目为变更依据。

### 补充：门店名称解析流程细化——先模糊查、多候选问用户（同日晚，用户决策）

**约定**：用户给的门店名称不完整/不确定时，先用 member 实体按 `member_allot_stationName` 模糊解析（前缀 `'xx%'` 可用索引优先，必要时 `'%xx%'` 包含匹配——扫一次会员表可接受，LIMIT 兜底）；**恰好 1 个候选→直接用其编码并在回答中说明实际匹配到的门店名；多个候选→停止查询，把候选门店的编码+名称列给用户，问清要查哪家店，不得自行挑选或全部汇总；0 个候选→请用户提供门店编码或更准确的名称，不得猜**。

**修改**
- `ChatServiceImpl` 系统提示词第 8 条：三档解析流程（1 候选用 / 多候选问 / 0 候选要编码）。
- `metadata.yaml` 三实体 `filterGuidance` 同步（member 为解析入口，order/detail 指回该流程）。
- `AdhocTool.adhocQuery` 描述同步。

**验证**
- 非数据库单测 78/78（filterGuidance 关键词断言兼容新文案）；全模块 compile 通过。
- **未验证**：模型实际对话中的多候选追问行为需端到端复测（如"长江路店"类模糊名称）。

## 2026-11-04：P0 多会话 + 多轮记忆隔离（sessionId 贯穿前后端）

**目标**：按用户拍板的 P0 范围，实现前端生成 `sessionId`、后端按会话隔离记忆、SSE 按 `userId:sessionId` 分通道，修掉多标签串流与旧连接误删问题；本轮不做追问卡片、不做 Redis 持久化。

**修改**
- 后端 `mcp-client`
  - `ChatEntity` 新增可空 `sessionId`；`ChatResponseEntity` 新增 `sessionId`，finish 回包带上会话归属。
  - 新增 `ChatSessionKeyUtils`：conversationId 三档回退（`sessionId` → `legacy:{userId}` → `ChatMemory.DEFAULT_CONVERSATION_ID`）；channelKey 规则（有 sessionId 用 `userId:sessionId`，否则用 `userId`）。
  - `ChatServiceImpl.doChat`：显式 `.param(ChatMemory.CONVERSATION_ID, conversationId)`；ADD/FINISH 均发往 channelKey；新增进程内 `ConcurrentHashMap<String, ReentrantLock>` 按 conversationId `tryLock` 快速失败，冲突时回一条 finish 提示。
  - `ChatServiceImpl` 的 RAG/联网方法同步改发 channelKey（仍不挂记忆，行为不变）。
  - `SSEServer`：connect/send 全部改用 channelKey；新增旧连接替换时 `complete()`；`remove` 改为 compare-and-remove，避免旧 emitter 回调误删新连接。
  - `SSEController.connect` 新增可选 `sessionId` 参数。
- 前端 `spring-ai-mcp-frontend`
  - `types/chat.ts`：`ChatRequest` 增加 `sessionId`，新增 `ChatFinishResponse`。
  - `stores/chat.ts`：`initUserId` 改为 localStorage 持久化（`crm_agent_uid`）；新增 `sessionId`、`initSessionId`（sessionStorage `crm_agent_active_session`）、`newSession()`。
  - `ChatInput.vue`：标准对话/知识库/联网请求体均带 `sessionId`。
  - `useSSE.ts`：`initSSE(userId, sessionId)`，URL 带 sessionId，建连前先 `closeSSE()`；finish 校验 `sessionId`，非当前会话忽略。
  - `ChatView.vue`：初始化 userId 与 sessionId 后再建连。
  - `vite.config.ts`：代理默认目标 9009 → 9090，与 `.env` 对齐（未改动 `.env`）。

**验证**
- 后端：`mvnw -pl mcp-client -am compile` 通过（进程级 `JAVA_HOME` 指向 corretto-21.0.13，未修改系统环境变量）。
- 后端：新增纯单元测试 `ChatSessionKeyUtilsTest`，`mvnw -pl mcp-client test -Dtest=ChatSessionKeyUtilsTest` 7/7 通过。
- 前端：`npm run build`（vue-tsc + vite build）通过。
- **未验证**：多标签并发、刷新后 SSE 重连、断线看门狗、并发 tryLock 的实际用户可见行为；未启动依赖 Redis/LLM 的端到端流程。

**已知限制**
- P0 记忆仍为进程内、20 条窗口，重启清空；会话列表与历史恢复、Redis 持久化留待 P1。
- SSE 仍为 `add` 纯文本（不带 botMsgId），同一会话串行化已由 tryLock 兜住；跨会话已由双键通道隔离。
- 前端无“新建会话”UI 入口，`newSession()` 已就绪待 P1 会话列表接入。

## 2026-11-04：P1 完整交付 — Spring AI 1.1.8 升级 + Redis ChatMemory + Redisson + 规约整改 + GuessTool + 追问卡片

### 本次目标：

P1 完整落地：ChatMemory 从进程内切 Redis、Redisson 分布式锁、规约整改、GuessTool 模糊查询、追问卡片 UI。

---

### 一、Spring AI 1.0.0 → 1.1.8 升级

#### 修改文件

| 文件 | 改动 |
|---|---|
| `mcp-client/pom.xml` | `spring-ai-bom 1.0.0 → 1.1.8` |
| `mcp-server/pom.xml` | `spring-ai-bom 1.0.0 → 1.1.8` |

#### 破坏性变更

| 变更 | 影响 | 修复 |
|---|---|---|
| `ChatMemory.DEFAULT_CONVERSATION_ID` 在 1.1.6+ 被移除 | `ChatSessionKeyUtils` 编译不过 | 硬编码 `"default"`（原常量值就是这个） |

**其他 API 零改动**：ChatClient、ChatModel、EmbeddingModel、RedisVectorStore、MCP starter、ChatMemoryAdvisor、ToolCallbackProvider — 全部向后兼容。

#### Redis ChatMemory 实现

**Spring AI 1.x 官方未提供 Redis starter**（2.0.0+ 才有），自行实现：

| 文件 | 作用 |
|---|---|
| `mcp-client/.../repository/RedisChatMemoryRepository.java` | `ChatMemoryRepository` 接口的 Redis 实现：LIST 存消息 + Jackson 序列化 + 可配 TTL（默认 7 天） |
| `mcp-client/.../config/ChatMemoryConfig.java` | 提供 `MessageWindowChatMemory(20)` + `RedisChatMemoryRepository` bean |

**序列化坑**：Spring AI 1.1.x 的 `UserMessage`/`AssistantMessage` 是不可变 POJO（无无参构造、无 `@JsonCreator`），Jackson 默认多态反序列化直接不支持。**修复**：序列化继续用 Jackson，反序列化手动路由 — 解析 JSON 里的 `messageType` 字段，直接调单参 public 构造器 `new UserMessage(text)` / `new AssistantMessage(text)` / `new SystemMessage(text)`。

#### Redisson 分布式锁

| 文件 | 作用 |
|---|---|
| `mcp-client/pom.xml` | 新增 `redisson:3.45.1`（纯核心库，**不引 starter**） |
| `mcp-client/.../config/RedissonConfig.java` | 手动初始化 `RedissonClient` bean（复用 `spring.data.redis.*` 配置） |
| `mcp-client/.../utils/RedisLockUtils.java` | 分布式锁声明式封装：`execute`（无返回值）+ `executeWithResult`（带返回值） |
| `mcp-client/.../service/impl/ChatServiceImpl.java` | `ConcurrentHashMap<String, ReentrantLock>` 本地锁 → Redisson `RLock` 分布式锁 |

**坑**：`redisson-spring-boot-starter`（最新 4.8.0 对应 Boot 4）会覆盖 Spring Boot 原生 `RedisConnectionFactory`，导致 `RedisVectorStore` / `RedisChatMemoryRepository` 装配失败。**解决**：换纯 `redisson` 核心库 + 手动写 `RedissonConfig`。

---

### 二、规约整改（阿里嵩山版）

#### mcp-client

| 项 | 数量 |
|---|---|
| `@Resource` / `@Autowired` 字段注入 → 构造器注入 | 3 Controller + ChatServiceImpl + EmailTool = 5 个类 |
| 依赖字段声明为 `final` | 所有注入字段 |
| 完整 Javadoc | 所有 Controller / Service / Config / Tool 类 + 每个 public 方法 |
| 模板注释清理（`@ClassName` / `@Date` / 空 `@Description`） | ~68 个文件 PowerShell 批量 sed |
| UTF-8 BOM 修复 | ~67 个文件（PowerShell `Set-Content` 带 BOM → 清 `\xEF\xBB\xBF` 头） |
| 常量集中 | 新增 `ChatConstants.java`（8 个常量，替换散落的 `KEY_PREFIX` / `LOCK_PREFIX` / `SUMMARY_MAX_LEN` 等） |
| 日志规范 | `log.*` 全扫无 `"xxx=" + var` 拼接，全部 `{}` 占位符 ✅ |

#### mcp-server

| 项 | 数量 |
|---|---|
| MyBatis Plus 模板注释重写 | 5 Mapper + 3 Service 接口 + DateTool = 9 个文件 |
| EmailTool 异常处理 | 吞异常 → 返回错误字符串 + 构造器注入 + `covert` → `resolveContent`/`markdownToHtml` |

---

### 三、P1 会话管理完整交付

#### 后端新增

| 文件 | 端点 / 说明 |
|---|---|
| `SessionController.java` | `GET /api/session/list`（返回 `[{conversationId, summary, messageCount}]`）、`GET /api/session/{id}/messages?limit=20`、`DELETE /api/session/{id}` |
| `ChatConstants.java` | Redis key 前缀、窗口大小、TTL 等 8 个常量 |
| `ChatSessionKeyUtils.java`（P0 已加） | 会话键解析三档回退 + channelKey 生成 |

#### 前端

| 文件 | 作用 |
|---|---|
| `ChatSidebar.vue` | 会话列表组件：新建按钮、列表（后端拉 + 10s 自动刷新）、点击切换（重建 SSE + 清空视图）、删除会话（Redis 删 key）；配色升级：active 态用 `linear-gradient(135deg, #4a90e2→#357abd)` 实色渐变 + shadow + accent border；hover 微位移 + cubic-bezier 过渡；删除按钮 active 态直接可见 |
| `chat.ts` | 新增 `restoreFromHistory` / `restoreCurrentSessionHistory` — 后端 markdown 原文 → `parseBotMarkdown()`（提取 `<clarify>` 标记 + 移除 marker + `marked.parse`），与流式 finish 走同一条链路 |
| `useSSE.ts` | finish 事件直接传 markdown 原文给 `finishBotMessage`，不提前 parse（把 parse 责任集中到 chat.ts） |
| `ChatMessages.vue` | 渲染 `item.clarifyData` → `<ClarifyCard>` 组件 |
| `ClarifyCard.vue`（新增） | 追问卡片：deep-indigo gradient header（呼应 sidebar active）+ 微妙斜线网纹 texture overlay + 弹性入场动画（`cubic-bezier(0.34, 1.56, 0.64, 1)` spring scale）；stationCode pill（等宽字体）+ label/meta + 箭头；hover accent border + translateX + 箭头变色位移；可访问性（role="dialog" / tabindex / @keydown.enter） |
| `ChatInput.vue` | `defineExpose({ setTextAndSend })` — 追问卡片选中后自动发送 code |
| `ChatView.vue` | onMounted/onBeforeUnmount 监听 `clarify-select` CustomEvent（document 级别派发） |
| `types/chat.ts` | 加 `ClarifyData` / `ClarifyCandidate` / `rawContent` / `clarifyData` 字段 |

---

### 四、mcp-server GuessTool 模糊查询 + 追问闭环

#### 新增文件

| 文件 | 说明 |
|---|---|
| `GuessTool.java` | MCP Tool：`guessStation(keyword)` + `guessMemberByMobile(keyword)`，JSON 带 `actionHint` 决策字段（`unique_match` / `multiple_matches` / `no_match` / `invalid_input`） |
| `MemberInfoMapper.java` | 3 个 `@Select`：前缀匹配 → 包含匹配兜底 + GROUP BY + LIMIT 20 |

#### 匹配策略

先前缀匹配（`keyword%`，走索引友好路径）→ **0 条结果时**再用包含匹配兜底（`%keyword%`，覆盖"哈市长江路店"里的"长江路"这种中间关键词）。

#### 业务口径修复（重要）

| 问题 | 修复 |
|---|---|
| SQL 里 `del_flag = 2`（瞎写的） | → `del_flag = '0'`（`MemberOverviewResult.java:15` 注释明确写"有效会员总数 del_flag='0'"） |
| 用 `FROM member_info` 表 | → `FROM member_info_view mv`（项目统一口径，SemanticRegistry/Adhoc 所有 SQL 全走 view） |

#### System Prompt 更新

`ChatServiceImpl` 第 8 条：multiple_matches 时输出 `<clarify>{JSON}</clarify>` 标记块，前端自动弹出交互卡片，用户点击候选后自动把 code 作为下一条 user message 发送。**前后端完全解耦**：标记格式约定在 system prompt 里，前端检测标记渲染卡片，选 code 自动发送。

---

### 五、kimi-k3 400 修复

`application.yml` `spring.ai.openai.chat.options` 加四行：

```yaml
temperature: 1.0       # kimi-k3 固定值，Spring AI 默认 0.8 会被 400 拒
top-p: 0.95            # kimi-k3 固定值
presence-penalty: 0.0  # kimi-k3 固定值
frequency-penalty: 0.0 # kimi-k3 固定值
```

根因：kimi-k3 是 thinking-only 模型，`temperature/top_p/presence_penalty/frequency_penalty` 必须精确等于官方固定值（或干脆不传），Spring AI 默认发 `temperature: 0.8` 被百炼 400 拒绝。

---

### 修改文件汇总

#### 后端（spring-ai-mcp-rag-dev）

| 类别 | 文件 |
|---|---|
| pom 升级 | `mcp-client/pom.xml`、`mcp-server/pom.xml` |
| mcp-client 规约 | ChatController / HelloController / SSEController / ChatServiceImpl / SessionController / 各 entity / SSEServer 等 ~20 个文件 |
| mcp-client 新增 | ChatConstants / ChatMemoryConfig / RedissonConfig / RedisChatMemoryRepository / RedisHelper / RedisLockUtils / ChatSessionKeyUtils / SessionController |
| mcp-server 规约 | DateTool / EmailTool / CrmAnalyticsMapper 等 ~40 个文件 |
| mcp-server 新增 | GuessTool / MemberInfoMapper 3 个 SQL 改写（表 + del_flag） |
| system prompt | ChatServiceImpl 第 8 条（追问输出 `<clarify>` 块） |
| kimi-k3 修复 | `mcp-client/src/main/resources/application.yml` |
| 日志 | `docs/ai-agent/DEVELOPMENT_LOG.md` |

#### 前端（spring-ai-mcp-frontend）

| 文件 | 作用 |
|---|---|
| `ChatSidebar.vue` | 会话列表 + 配色升级 |
| `ClarifyCard.vue`（新增） | 追问卡片组件 |
| `ChatMessages.vue` | 渲染 clarifyData |
| `ChatInput.vue` | `defineExpose({ setTextAndSend })` |
| `ChatView.vue` | clarify-select 事件监听 + onBeforeUnmount |
| `chat.ts` | `parseBotMarkdown()` + `finishBotMessage` 改签名 + `restoreFromHistory` 升级 |
| `useSSE.ts` | finish 事件传 markdown 原文 |
| `types/chat.ts` | ClarifyData / ClarifyCandidate / rawContent / clarifyData |

---

### 测试与验证结果

| 项 | 结果 |
|---|---|
| 后端全项目 compile | ✅ exit 0 |
| mcp-server 全量测试 | ✅ **219/219** |
| ChatSessionKeyUtilsTest | ✅ **7/7** |
| 前端 `npm run build` | ✅ |
| kimi-k3 400 修复 | ✅ 已验证 |
| GuessTool 业务口径 | ✅ member_info_view + del_flag='0' |
| `.env` 排除 | ✅ 未提交（敏感） |
| `dump.rdb` 排除 | ✅ 未提交（二进制 dump） |

---

### 未解决问题

- `RedisChatMemoryRepository` 反序列化失败当前只打 warn 跳过（expected case，Redis 旧存量可能有 Jackson 多态残留）；长期可考虑换 Kryo 或自定义序列化协议
- `GuessTool` 不支持 `track`（VIP）等筛选维度；后续可加 `track` 参数让模型查"长江路有多少 VIP"
- 会话元数据的 `lastActiveAt`（最后活跃时间）暂未做；Spring Data Redis 3.5 没直接暴露 `OBJECT IDLETIME`，需用 `RedisTemplate.execute(RedisScript)` 走 Lua
- `ChatMemoryConfig` 里 `MEMORY_WINDOW_SIZE=20` 和 `MEMORY_DEFAULT_TTL_DAYS=7` 还没从 ChatConstants 引入常量
- ChatSidebar 会话列表的 summary 用手写的 `extractString`，遇到引号有小概率解析错；可后续换 Jackson `readTree`

### 风险

- Redisson 3.45.1 未做完整分布式锁端到端测试（多实例部署场景）；当前只在单实例下验证过 `RLock` 基本功能
- RedisChatMemoryRepository 的手动反序列化路由（按 messageType 调构造器）**跳过了 metadata/media 字段**；如果 Spring AI 后续往 Message 子类加 final 字段需要同步更新

### 下一步

1. 端到端冒烟：重启两服务，测"长江路有多少用户"完整链路 — GuessTool → clarify 卡片 → 点选 code → 查数据
2. mcp-server 规约扫尾：剩余 Adhoc/Metric 模块的模板注释清理 + import 分组
3. 业务开发：P2 CRM 分析工具集（adhoc 语义查询 + 追问）

### 日期：

### 本次目标：

### 已完成事项：

### 修改文件：

### 架构或技术决策：

### 业务口径变化：

### 测试与验证结果：

### 未解决问题：

### 风险：

### 下一步：

## 2026-11-05：Windows Server 2022 测试环境部署（前后端 + nginx + 独立端口）

### 本次目标
完成 CRM Agent 在测试环境（Windows Server 2022，零 Docker）的完整部署链路，实现：可执行 fat jar 打包 → 外部化 `.env` → nginx 反向代理（含路径前缀隔离，与同域其他项目共存）→ 前端 API 统一前缀避免 location 冲突。**本轮未改任何 Java 业务代码，仅改配置/构建/前端路径。**

### 已完成事项
1. **可执行 fat jar 打包**：
   - `mcp-server/pom.xml` 加 `spring-boot-maven-plugin repackage` goal + `<finalName>${project.artifactId}</finalName>`。
   - `mcp-client/pom.xml` 同。
   - `mvnw.cmd clean package -DskipTests -B` → BUILD SUCCESS，产物 `mcp-server.jar` / `mcp-client.jar`（fat jar，几十 MB，含 MANIFEST `Main-Class: JarLauncher`）。
   - 解决了此前 `java -jar` 报"没有主清单属性"的根因（未 repackage 只出普通 jar）。
2. **外部化配置（`.env`）**：
   - 两模块保留 `spring-dotenv` 依赖，支持 jar 同目录 `.env` 自动加载。
   - 目录约定：`D:\crmAgent\server\`（jar + .env）、`D:\crmAgent\client\`（jar + .env）。
   - `mcp-client/src/main/resources/application.yml` 删掉 `redis.username: default`（普通 Redis 6+ 未开 ACL，Jedis 6 带 username 会发 `AUTH default pwd` 双参数格式被拒绝 → `ERR wrong number of arguments for 'auth' command`）。
3. **启动方式决策**：
   - A2 方案：`--spring.profiles.active=prod/test` 运行时指定 profile，jar 一次打出来通用。
   - JDK 路径指定：`D:\jdk-21.0.6\bin\java.exe`（非系统 JAVA_HOME），2G 堆硬卡 `-Xms512m -Xmx2g -XX:+UseG1GC -XX:MaxMetaspaceSize=512m`。
   - 拆成独立 start/stop bat（后简化为单行命令直接 cmd 里敲）。
4. **前端路径前缀 + nginx 配置**：
   - 新增 `vite.config.ts` `base: '/crmAgent/'` + `.env` `VITE_API_BASE_URL=/crmAgent-api`。
   - vite dev proxy 从 5 条散路径（/api /chat /sse /rag /internet）收敛为统一 `/crmAgent-api` + rewrite 剥前缀。
   - nginx 两个 server 块（80 + 443 HTTPS 都要加，浏览器默认自动升级 HTTPS 之前漏配导致"被重定向到其他项目"）：
     - `location /crmAgent/` → 静态文件（注意 nginx 用 `alias` 而非 `root`，且 try_files 回退路径也要带 `/crmAgent/` 前缀）。
     - `location /crmAgent-api/` → `rewrite ^/crmAgent-api/(.*)$ /$1 break` → proxy_pass mcp-client:9090。
   - SSE 代理必须加 `proxy_buffering off` + `proxy_read_timeout 600s`（DashScope 流式回复可能几分钟）。
5. **前端代码适配**（独立仓库 `spring-ai-mcp-frontend`）：
   - `ChatSidebar.vue` / `ChatView.vue`：`import axios from 'axios'` 裸调绕过 baseURL → 改走 `@/utils/request`；`resp.data?.xxx` → `resp?.xxx`（request 拦截器已 `return response.data`）。
   - `useRealtimeASR.ts`：WebSocket URL 硬写 `/api/audio/realtime` → 加 `/crmAgent-api` 前缀。
   - `src/utils/request.ts`：给 axios 实例加 `RequestInstance` 类型（Omit 原 AxiosInstance 方法 + 覆盖返回 `Promise<T>` 而非 `Promise<AxiosResponse<T>>`，解决 interceptors return response.data 后 TS 类型仍以为是 AxiosResponse 的误报）。
   - TypeScript 6 个 TS2339 错误全部清除，`npm run build` 通过。

### 修改文件（后端仓库）
- `pom.xml`、`mcp-server/pom.xml`、`mcp-client/pom.xml`：版本 1.0-SNAPSHOT → 1.1.0；`mcp-server` 加 repackage + finalName。
- `mcp-client/src/main/resources/application.yml`：删 `redis.username: default`（普通 Redis 6+ 无 ACL）。
- 未改：`application-dev.yml` / `application-prod.yml` / `application-test.yml`（`website.domain` 保持 `http://localhost:9173`，同域部署 CORS 自动不触发）。

### 本次暴露并修复的坑
| 坑 | 根因 | 修复 |
|---|---|---|
| `java -jar` 报"没有主清单属性" | pom 里没配 `spring-boot-maven-plugin repackage` goal | 加 repackage + finalName |
| 启动一闪而过（bat 脚本） | Windows cmd 引号嵌套/编码/环境不一致 | 放弃脚本，直接单行 cmd 命令 |
| Redis 报 `ERR wrong number of arguments for 'auth' command` | `application.yml` 写了 `username: default`，Jedis 6 按 ACL 双参数格式发 AUTH | 删 `username`，走单参数 `AUTH password` |
| 访问 `/crmAgent/` 被重定向到其他项目 | nginx 只在 80 server 块配了 location，443 HTTPS server 块漏配（浏览器自动升级 HTTPS 后落到 vue 项目的 `location /`） | 80 + 443 两块各加同一份 location |
| nginx 静态返回 404 | `root` vs `alias` 用法：root 会把 URI 整个拼到磁盘路径后（多套一层 `/crmAgent`），alias 才会剥前缀 | 改用 `alias ../crmAgent/dist/` + try_files 回退路径也带前缀 |
| API 403 拒绝访问 | 路径 `/api/session/*` 等裸路径和同域其他项目的 location 冲突 + 跨域 | 统一前缀 `/crmAgent-api` + nginx rewrite 剥前缀 |
| 前端 TS 报错 `Property 'messages' does not exist on type 'AxiosResponse'` | request 拦截器 return response.data，但 TS 类型仍以为是 AxiosResponse | 加 `RequestInstance` 类型覆盖方法返回值 |
| vite dev proxy 不再对齐 | 前缀统一后 dev 模式也要走 `/crmAgent-api` | proxy 从散路径收敛为统一前缀 + rewrite |

### 部署结构
```
D:\crmAgent\
├─ server\
│  ├─ mcp-server.jar              (fat jar, 无版本号)
│  ├─ .env                        (MYSQL_HOST/PORT/USER/PASSWORD + MAIL_*)
│  └─ start-mcp-server.bat        (title mcp-server + 单行 java -jar)
├─ client\
│  ├─ mcp-client.jar
│  └─ .env                        (DASHSCOPE_* + REDIS_*)
└─ frontend\
   └─ dist\                       (vite build 产物，base=/crmAgent/)
```

### 启动命令
```cmd
REM mcp-server (9060)
"D:\jdk-21.0.6\bin\java.exe" -Xms512m -Xmx2g -XX:+UseG1GC -XX:MaxMetaspaceSize=512m -jar "D:\crmAgent\server\mcp-server.jar" --spring.profiles.active=test --server.port=9060

REM mcp-client (9090)
"D:\jdk-21.0.6\bin\java.exe" -Xms512m -Xmx2g -XX:+UseG1GC -XX:MaxMetaspaceSize=512m -jar "D:\crmAgent\client\mcp-client.jar" --spring.profiles.active=test --server.port=9090
```

### nginx 核心片段（80 + 443 各一份）
```nginx
# CRM Agent 前端
location /crmAgent/ {
    alias   ../crmAgent/frontend/dist/;
    index   index.html;
    try_files $uri $uri/ /crmAgent/index.html;
}
# CRM Agent 后端反代
location /crmAgent-api/ {
    rewrite ^/crmAgent-api/(.*)$ /$1 break;
    proxy_pass         http://127.0.0.1:9090;
    proxy_http_version 1.1;
    proxy_buffering    off;    # SSE 必需
    proxy_read_timeout 600s;   # DashScope 流式回复可能几分钟
}
```

### 访问链路
| 浏览器请求 | nginx 处理 | 到达后端 |
|---|---|---|
| `demo.19wo.cn/crmAgent/` | alias 静态文件 | — |
| `demo.19wo.cn/crmAgent-api/api/session/list` | rewrite → `/api/session/list` → proxy | mcp-client GET `/api/session/list` |
| `demo.19wo.cn/crmAgent-api/chat/doChat` | rewrite → `/chat/doChat` → proxy | mcp-client POST `/chat/doChat` |
| `demo.19wo.cn/crmAgent-api/sse/connect?userId=1` | rewrite → `/sse/connect?...` → proxy | mcp-client GET `/sse/connect?...` |

### 测试与验证结果（均已实际执行）
- `mvnw.cmd clean package -DskipTests -B` → **BUILD SUCCESS**，两模块各产出 fat jar（确认 jar 内含 MANIFEST `Main-Class: JarLauncher` 且体积 > 20MB）。
- `java -jar` 直接启动 → Redis AUTH 单参数修复后 mcp-client 启动成功，端口 9090 监听。
- nginx `-t` 语法检查通过 + `-s reload` 热重载成功（80 和 443 两块各加 location 后）。
- 前端 `npm run build` → 通过，无 TS 错误，dist 产物资源路径前缀 `/crmAgent/` 正确。
- 浏览器访问 `https://demo.19wo.cn/crmAgent/` → 页面正常，API 调用走 `/crmAgent-api/*` 前缀，不再与同域其他项目的 `/api/`、`/chat/` 等 location 冲突。

### 版本与发布
- 后端仓库提版本 **1.0-SNAPSHOT → 1.1.0**，打 git tag `v1.1.0`（本次提交之后执行）。
- 前端仓库独立，本次改动另存 commit，版本号不在本仓库管理。

### 未验证 / 风险
- 端到端自然语言对话链路（mcp-server + mcp-client + DashScope kimi-k3）本次未实测（后端刚能启动、前端刚能访问 nginx）。下一步需验证 SSE 流式输出、MCP SSE 连接、MySQL CRM 只读库查询全链路。
- Redis ChatMemory 实测过单参数 AUTH 修复后可连接，但 Redis ConnectionFactory 同时用于 Redisson 分布式锁（mcp-server）和 StringRedisTemplate LIST 存储对话历史（mcp-client），本轮未分别验证两边都通。
- `.env` 路径约定：`spring-dotenv` 4.0.0 读取 jar 同目录 `.env`；用户手工放置时若路径错误会静默降级（属性值为 null 导致 NPE），启动后需看日志里是否有 Redis/Mail 连接失败线索。
- nginx `/crmAgent-api/` 的 rewrite 规则对 `mcp-client` 里所有路径生效，但 `mcp-server` 并不暴露在 nginx（mcp-client 内部 MCP SSE 直连 localhost:9060），所以 nginx 层面不会出现 mcp-server 的 location 需要 `/crmAgent-api/` 前缀的情况——但如果未来前端要直连 mcp-server，记得也加 `/crmAgent-server-api/` 前缀隔离。
- 两个 bat 脚本文件本身（start-mcp-server.bat / start-mcp-client.bat）在 `.env` 拷进 jar 目录之前未实际创建，属"单行命令的 bat 化"过程，本轮最终交付是直接单行 cmd 命令而非 bat 脚本。

### 下一步
- 端到端冒烟：前端对话 → mcp-client ChatController → GuessTool → mcp-server MCP SSE → adhocQuery → SQL → 结果返回。重点看 SSE 流式输出、crmAgent 前端在 nginx 下与后端跨端口问题是否全解决。
- Redis ChatMemory 单独验证：启动后发几条对话，再刷新页面看会话历史是否还在（StringRedisTemplate LIST 读写）。
- 未来如需"一键 bat"，在 D:\crmAgent\server\ 和 client\ 下各写一份 start.bat，标题 `title mcp-server` / `title mcp-client`，命令行硬编码即可，参考本次的单行命令。
- 前端仓库 commit：Vite base + request 封装 + TS 类型 + WSS URL，单独版本号。

## 2026-11-06：已知限制 — 多标签页首次并发打开时 userId 可能短暂不一致

- **现象**：多个标签页几乎同时首次打开、localStorage 为空时，各标签页可能各自生成 `userId`，后写覆盖先写；极端并发下两个标签页的会话归属可能短暂不一致。
- **当前处理**：生成后回读 localStorage 最终落盘值做收敛，刷新一次即可统一。
- **决策**：暂不解决（用户已确认），不引入 Web Locks / `storage` 事件同步。
- **影响范围**：仅首次访问的并发窗口；已存在 `crm_agent_uid` 的正常使用不受影响。

## 2026-11-06：会话按用户隔离 + 构建体系统一（mcp-common / 根 pom）+ 模型接入改回 OpenAI 兼容端点

### 本次目标
1. 修复线上「多用户会话可以互相看到、互相串话」：会话列表、历史读取、删除、写入全部按 `userId` 做归属校验。
2. 修复前端子组件先于父组件挂载导致 `userId` 为空、会话接口 400 的问题。
3. 统一构建：新增纯资源模块 `mcp-common`，依赖版本上收根 pom，修复 JDK 21 下 Lombok 注解处理器报错。
4. 模型接入从 DashScope 原生 API 改回 OpenAI 兼容端点（需求变化，见「架构或技术决策」）。

### 已完成事项
1. **会话归属与用户隔离（mcp-client）**
   - 新增 `SessionOwnershipRepository`：`chat:session:owner:{sessionId}`（String=userId）登记归属，`chat:user:sessions:{userId}`（SET）维护用户会话索引；TTL 与 ChatMemory 一致（默认 7 天）。
   - 写入路径 `ChatServiceImpl#doChat` 先 `claimOrVerify`：首次写入用 SETNX 抢占登记（先到先得），归属为他人时直接 SSE 返回「会话不存在或无权访问」并结束，不写入记忆。
   - 读取/删除路径 `SessionController`：`/api/session/list`、`/api/session/{id}/messages`、`DELETE /api/session/{id}` 的 `userId` 改为必填——缺失 400、归属他人 403、无归属 404；删除时同步清理归属 key 与用户索引。
   - 全新会话尚无历史时，`GET /{id}/messages` 返回空历史（200）而不是 404，避免刷新页面弹错误。
   - 兼容旧数据：`legacy:{userId}` 会话按前缀推导归属并惰性补登记；升级前创建且无归属记录的 UUID 会话按「拒绝访问」处理。
   - `RedisChatMemoryRepository#findConversationIds` 由 `KEYS` 改 `SCAN`（count 500），避免 key 膨胀时阻塞 Redis；会话列表改走用户索引 SET，不再全库扫描。
   - `RedisHelper` 新增 `setIfAbsent` / `addToSet` / `removeFromSet` / `members`；`ChatConstants` 新增两组 key 前缀常量。
2. **前端 userId 时序修复（`spring-ai-mcp-frontend` 仓库）**
   - `chat.ts#initUserId()` 改为幂等且同步返回非空字符串，写入 localStorage 后回读最终落盘值收敛多标签竞争。
   - `ChatSidebar.vue`（列表/新建/切换/删除）、`ChatView.vue`（挂载时 SSE + 历史恢复）、`ChatInput.vue`（各对话请求）统一在请求前取返回值，不再使用可能为空的 `currentUserName`。
   - 列表、切换、删除请求统一携带 `userId`。
3. **构建体系**
   - 新增 `mcp-common` 模块：只含 `src/main/resources/logback-spring.xml`，无任何依赖、不做 repackage；`mcp-client` 与 `mcp-server` 都依赖它。
   - 根 `pom.xml`：`spring-boot-starter-parent` 改为 import `spring-boot-dependencies:3.5.14`；`spring-ai-bom:1.1.2` 与 12 个坐标（lombok / mybatis-plus / mybatis-plus-jsqlparser / mysql-connector-j / flexmark-all / spring-dotenv / commons-lang3 / spring-boot-starter-test / okhttp / hutool-all / dotenv-java / redisson）统一进根 `dependencyManagement`；子模块删除裸 `<version>`；`spring-boot-maven-plugin` 收进根 `pluginManagement`；新增 `flatten-maven-plugin` + `revision` 版本占位；`maven-compiler-plugin` 使用 `annotationProcessorPaths` + `-parameters`；`dev/test/prod` profile 提供 `logging.level`，resources 对 `application*/bootstrap*/banner*` 开启 filtering。
   - `mcp-server` 的 `spring-boot-starter-test` 补 `test` scope；`flexmark-all` 排除 `commons-logging`（与 spring-jcl 冲突）。
   - Lombok 注解处理器 1.18.24 → 1.18.46，修复 JDK 21 下 `NoSuchFieldError: JCImport.qualid`。
4. **日志**
   - `logback-plus.xml` 改名 `logback-spring.xml` 并移入 `mcp-common`；用 `<springProperty>` 读取 `spring.application.name`，日志目录 `${LOG_PATH}/${APP_NAME}`，`LOG_PATH` 默认 `./logs`；滚动策略补 `totalSizeCap`。
   - 两模块 `application.yml` 的 `logging.config` 同步指向 `classpath:logback-spring.xml`，包级别改为 `com.github.yingli: @logging.level@`。

### 修改文件（后端仓库）
- `pom.xml`、`mcp-client/pom.xml`、`mcp-server/pom.xml`
- 新增 `mcp-common/pom.xml`、`mcp-common/src/main/resources/logback-spring.xml`
- 新增 `mcp-client/.../repository/SessionOwnershipRepository.java`
- `mcp-client/.../controller/SessionController.java`、`service/impl/ChatServiceImpl.java`、`repository/RedisChatMemoryRepository.java`、`utils/RedisHelper.java`、`constants/ChatConstants.java`
- 新增测试 `mcp-client/src/test/.../controller/SessionControllerTest.java`、`.../repository/SessionOwnershipRepositoryTest.java`
- `mcp-client/src/main/resources/application.yml`（OpenAI 兼容端点 + 日志）、`mcp-server/src/main/resources/application.yml`（日志）、`mcp-client/src/main/resources/.env.example`（变量名 `DASHSCOPE_*` → `OPENAI_*`）
- 文档：本文件、`.qoder/rules/crm-data-agent.md`、`docs/DELIVERY.md`、`README.md`

### 架构或技术决策
1. **会话归属独立存储而不是从 conversationId 推导**：sessionId 是前端 UUID，不携带用户信息，Spring AI 的 `ChatMemoryRepository` 只认 conversationId，归属必须单独存 Redis。
2. **列会话走用户索引 SET + SCAN**：`KEYS chat:memory:*` 在 key 增多时会阻塞 Redis；改为按 `chat:user:sessions:{userId}` 读取，`findConversationIds()` 继续用 SCAN 兜底。
3. **模型接入改回 OpenAI 兼容端点（需求变化）**：移除 `spring-ai-alibaba-starter-dashscope` 与 SAA BOM 1.1.2.2，改用 `spring-ai-starter-model-openai`，`base-url` 指向 `/compatible-mode/v1`，删除 `multi-model: true`。原因：① 长期使用纯文本模型 `qwen3.8-flash`，不需要多模态；② 残留 `multi-model` 会把纯文本请求路由到 DashScope 原生多模态端点，导致 429；③ 百炼 MaaS 专属推理 endpoint 的 key 只支持 OpenAI 兼容端点，原生 API 报 `url error`。
4. **构建版本集中管理**：根 pom 统一 `dependencyManagement` + `pluginManagement`，子模块不写版本；`mcp-common` 只做资源复用、不引依赖，避免循环依赖。

### 业务口径变化
- 会话可见范围从「全库所有会话」变为「仅当前用户自己的会话」；跨用户读取/删除返回 403（有归属但非本人）或 404（无归属）。
- 没有其他指标口径变更。

### 测试与验证结果
- 新增 `SessionControllerTest`（standalone MockMvc + mock 仓储）：覆盖缺失 `userId` 400、列表按用户过滤、读取/删除越权 403、无归属 404。
- 新增 `SessionOwnershipRepositoryTest`（mock RedisHelper）：覆盖归属登记与复用、越权拒绝、legacy 兼容、按用户列表清理。
- 两个测试类都是纯单测（不依赖真实 Redis），**本轮文档沉淀与提交阶段未重新执行** `mvn test` / `mvn package`，不能视为已通过。

### 未解决问题
- 升级前已存在、无归属记录的 UUID 会话无法访问（按 404 处理），需要用户新建会话或人工补归属数据。
- 多标签页首次并发打开时 `userId` 仍可能短暂不一致（见上一条 2026-11-06 记录），已确认暂不解决。
- `mcp-server` 的 `ToolPromptSnippets` 抽取与 `metadata.yaml` 时间列过滤白名单扩展仍在工作区，未包含在本次提交内。

### 风险
- 归属 key / 用户索引与记忆 key 的 TTL 都取自 `app.chat.memory.redis.ttl-days`，只调其中一处会出现「记忆还在但列表不显示」。
- 前端所有会话请求都必须带 `userId`，未带参数的旧版前端会直接收到 400。
- `.env.example` 已改为 `OPENAI_*`，现有部署 `.env` 需同步改名，否则 `base-url` / `api-key` 解析为空。

### 下一步
- 部署时同时更新 `mcp-client`、`mcp-server`、`mcp-common` 三个产物，并把服务器 `.env` 的 `DASHSCOPE_*` 改为 `OPENAI_*`。
- 用两个不同浏览器或隐身窗口验证会话隔离：A 创建的会话在 B 的列表中不可见，B 直接删除 A 的会话返回 403。

## 2026-11-09：修复三个实测问题（多门店解析 / 消费金额取错字段 / 开放两个会员字段）

### 一、问题与根因

1. **多门店被误判成单门店模糊匹配**
   - 现象：问「哈市长江路与鸿翔路分别有多少要客？」，模型只按「长江路」模糊匹配，命中「哈市长江路店」+「辽宁海城区长江路店」，且丢掉「鸿翔路」。
   - 根因一：`GuessTool.guessStation` 只接受**单个** `keyword` 字符串，一句话两个门店无法一次解析，模型只能取其一。
   - 根因二：工具只有一种匹配策略（`LIKE '%kw%'` 包含匹配），没有精确/前缀优先，截短后的「长江路」必然跨城市命中多家。
   - 根因三：客户端系统提示写「不要加城市前缀（包含匹配会自动覆盖）」，直接诱导模型把「哈市长江路」截短。
2. **消费金额取错字段**
   - 根因：`MEMBER_OVERVIEW` / `VIP_OVERVIEW` / `GENDER_EXPENSE_TOTAL` 与 adhoc member 度量 `TOTAL_EXPENSE`/`YEAR_EXPENSE` 均直取会员视图快照 `member_info_view.member_expense_totalsum(_year)`；用户已确认应取消费流水 `expense_calendar_info.expense_realamt`。会员视图是会员粒度、订单表是订单粒度，直接换字段名会把金额按关节乘翻。
3. **adhoc 未开放两个字段**
   - 根因：`member_allot_staffName`、`plus_type` 未登记进 `metadata.yaml` 的 dimensions / filterableColumns / indexedColumns，模型无法作为维度或过滤条件使用。

### 二、实际修改

1. 多门店
   - `GuessTool.guessStation` 改为接收 `List<String> keywords`（最多 5 个），按 精确 → 前缀 → 包含 三级匹配，命中即停；每个关键词独立返回 `matchedBy` / `candidates` / `actionHint`，汇总 `all_unique` / `needs_clarify` / `partial_no_match`。
   - `MemberInfoMapper` + XML 新增 `guessStationByExact`（`= kw` 或 `= kw.'店'`）。
   - 客户端 `ChatServiceImpl` 提示词改为：保留城市/区前缀、多门店传 `keywords` 数组逐店解析、逐店分别输出，禁止截短、禁止只答一家。
   - 新增 `GuessToolTest`（5 例：多关键词分别解析、顿号拆分、精确命中不再走模糊、跨城市多候选要求澄清、过短输入不查库）。
2. 消费金额
   - `metrics/member.yaml`：删除 `MEMBER_OVERVIEW` 的金额度量与派生、删除 `GENDER_EXPENSE_TOTAL`、`VIP_OVERVIEW` 只留要客数；新增 `MEMBER_CONSUMPTION`（订单实付 + 1:1 关联会员），`VIP_CONSUMPTION_BY_STORE` 重指订单实付。
   - `semantic/metadata.yaml`：删除 member `TOTAL_EXPENSE`/`YEAR_EXPENSE` 度量，filterGuidance 明确禁止快照金额字段、统一改走 order 实体 `SALES`。
   - `MemberStatMapper.xml` 的 `memberOverview` 改为派生表 LEFT JOIN 预聚合（按 `expense_mobile` 先 GROUP BY 再关联），保持会员粒度且不重复累计。
   - `CrmAnalyticsTool.queryMemberOverview` 人数取 `MEMBER_OVERVIEW`、金额二次查询 `MEMBER_CONSUMPTION`，失败时返回可读 message 不编造数字。
3. 开放字段
   - `metadata.yaml` member 新增维度 `ALLOT_STAFF_NAME`、`PLUS_TYPE`，并把两列加入 `filterableColumns`（`member_allot_staffName` 同时加入 `indexedColumns`，DDL 有 `index_allot_staffName`），filterGuidance 补充空值与编码说明。
   - 两字段均为非数值 varchar，不需要 `column-types.yaml` 登记。

### 三、口径文案

- `SUPPORTED_METRICS.md`：MEMBER_OVERVIEW 只讲人数、GENDER_EXPENSE_TOTAL 段替换为 MEMBER_CONSUMPTION、VIP_OVERVIEW/ VIP_CONSUMPTION_BY_STORE 口径与速查表同步、adhoc 门店解析与性别消费示例同步。
- `BUSINESS_METRICS.md`：VIP_OVERVIEW / VIP_CONSUMPTION_BY_STORE 口径与 conditional 编译示例同步。

### 四、测试

- 定向单测：`GuessToolTest`、`CrmAnalyticsToolTest`、`MetricToolInjectionTest`、`MetricRegistryLoaderTest`、`MetricsAsCodeMigrationReconciliationTest`、`VipMetricsReconciliationTest` 等全绿；契约快照（catalog-metric / catalog-adhoc / tool-definitions）已重新生成。
- 未执行：全量 `mvn test` 本轮未重新跑；`*IT`（真库对账）需要测试库环境，未执行；多门店端到端效果未接真实模型验证，仅工具层单测覆盖。

### 五、待业务确认

1. `plus_type` 除 DDL 注释的 `1=全年会员` 外无确认取值/空值含义，当前原样输出编码。
2. `MEMBER_CONSUMPTION` 的门店归属沿用「分配门店」、人均分母沿用「有消费会员数」，需业务复核。
3. adhoc 的 `HAS_EXPENSE` 条件模板仍基于快照字段 `member_expense_totalsum > 0`（只用于“是否消费过”布尔筛选，不参与金额汇总），是否改为按订单表判定需业务决定。
4. `VIP_CONSUMPTION_BY_STORE` 的“要客数”语义已变为“窗口内有消费要客数”，与当前要客总数不同，回答时必须声明。
5. 生产库 `memberOverview` 派生表聚合性能需按真实数据量复核 `expense_calendar_info(expense_mobile)` 索引与耗时。

## 2026-11-09：按 CRM 源码对齐字段语义与口径文案（仅描述性改动）

### 一、依据

CRM 源码 `d:\java_project\crm`：`MemberInfoDao.xml` 的 `findList`、会员实体注释、JSP 标签页字典、`access_record_info` 与 `set_level` 相关查询。逐条核对 `semantic/metadata.yaml`、`metrics/*.yaml`、`column-types.yaml`、客户端提示词与 `docs/ai-agent` 文档。

### 二、纠正的错误事实

| 项 | 原描述 | 对齐后 |
|---|---|---|
| `plus_type` | `1=全年会员`（DDL 注释） | `1=PLUS`；页面“否”实现为 `plus_type IS NULL`（不是 `=0`） |
| `channel_type` | `0 本部 / 1 饿了么 / 2 美团 / 3 京东到家 / 4 抖音`（DDL 注释） | 会员**注册来源渠道**，页面字典 `1 饿百 / 2 美团 / 3 京东到家`；DDL 注释与页面字典不一致，以 CRM 源码为准 |
| `track` | 仅 `0/1`，真库无其他值 | 页面筛选 `0 否 / 1 是`；取消要客流程存在 `track=2`，NULL 也可能存在，二者不等于“否” |
| `customer_cost` | 一般/重要 × 价值/发展/挽留/保护（未说明存储形态） | 库内直接存中文标签，固定 8 值 |
| 三个级别字段 | `GA~FC / AA~FC / AA~HC` | 取值来自 `set_level` 表按 `level_code` 分组，源码无枚举，原码表为推断，标注待核对 |
| `isAllotStaff` | 1 是 / 2 否 | 1=已分配员工，2=未分配，空=不限 |
| `member_allot_store` | 分配门店代码 | 存 `sys_office.HD_ID`（8 位），与 `office_id` 不同；CRM 按分配门店做机构数据权限隔离，空值会员在设置机构范围时被排除 |
| 商品名过滤 | 商品名 → `expense_d_commCodeName` | 商品名=`expense_d_commName`（仅维度，未开放过滤）；类别名=`expense_d_commCodeName`；SKU=`expense_d_gdcode` |
| 商品金额 | 未区分订单头/明细行 | CRM 商品维度用明细行 `expense_d_realamt`；整单优惠/抹零无分摊逻辑，不能与订单头实付等同 |
| 数据源 | 会员口径统一取 `member_info_view` | 补充：CRM 列表页固定条件仅 `del_flag='0'`，未排除废弃会员/区域池/测试门店；该表是 `member_info` 行更新后由触发器 `ff_update_member_info_view` 同步的物理表（不是 SQL 视图），即 CRM 列表页数据源（2026-11-10 修正） |

### 三、新增待确认/事实标注（不改实现）

- `metadata.yaml`：`reserved2/4/5/6/7/8/9` 与 `access_record_info` 的语义登记（当前未开放）；会员 `timeColumns` 注明 CRM “消费时间”绑定 `reserved2`，本系统消费时间走 `order.BUY_DATE`；`member_allot_store`、`plus_type`、`track`、级别码表、`HAS_EXPENSE` 快照说明。
- `column-types.yaml`：`member_name` 注明 CRM 用 `LIKE '%值%'` 且不脱敏，本项目仍未开放过滤/维度并禁止原值输出；`member_info_view` 注明 CRM 列表页固定条件。
- `DATABASE_SCHEMA.md`：新增“CRM 源码语义校对”12 条（数据源、plus_type、channel_type、customer_cost、级别、时间字段、回访、金额快照）。
- 客户端 `ChatServiceImpl` 提示词：累计消费额改指 `MEMBER_CONSUMPTION`（订单实付实时汇总），删除“近一年半消费 → member_expense_totalsum_year”的快照映射；补充金额口径差异提醒、商品名字段区分、track=2/NULL、级别码表待核对。

### 四、金额口径（明确不等价）

CRM 会员列表页 `strMoney/endMoney` 用 18 个月快照 `member_expense_totalsum_year`（由订单 `expense_realamt` 回写、非实时，快照子查询**未加** `expense_state='10'`，可能含已退单金额）；`MEMBER_CONSUMPTION` / `ANNUAL_AVG_CONSUMPTION` / `VIP_CONSUMPTION_BY_STORE` 用订单实付实时汇总（`expense_state='10' AND del_flag='0'`）。本轮**未改**任何 SQL 过滤逻辑或指标来源，只把“与 CRM 列表页等价”的暗示全部改为“可能不同、不得声称等价”。

### 五、测试

- 相关测试：`ContractSnapshot*`、`MetricCatalog*`、`SemanticCatalog*`、`MetricRegistryLoaderTest`、`MetricConfigValidatorTest`、`SqlGoldenSnapshotTest`（重生成 catalog-metric / catalog-adhoc 快照后通过）。
- 全量 `mvn test` 未重跑（仅文案与注释变更，SQL/指标逻辑未动）。

## 2026-11-09（二）：`set_level` 真库核实与 DDL 注释逐字段复核

### 一、`set_level` 取值（按 CRM 原样查询）

CRM 取值入口：`SetLevelDao.getGrade()`（`mappings/modules/apibase/setlevel/SetLevelDao.xml`），`MemberInfoController` 再按 `companyLevel / halfYearLevel / yearLevel` 拆成三个下拉。原样 SQL：

```sql
SELECT
  CASE WHEN level_code LIKE '%companyLevel%' THEN SUBSTRING(level_code, 1, 12) ELSE level_code END levelCode,
  GROUP_CONCAT(LEVEL) level
FROM `set_level`
WHERE level_code != 'companyLevel' AND is_del = 0
GROUP BY CASE WHEN level_code LIKE '%companyLevel%' THEN SUBSTRING(level_code, 1, 12) ELSE level_code END
```

对测试库（只读）执行结果：

| 分组 | 页面下拉 | 真库枚举 | 数量 |
|---|---|---|---|
| `companyLevel` | 公司级别（业务称毛利级别，18 个月） | AA AB AC BA BB BC CA CB CC DA DB DC EA EB EC FA FB FC GA GB GC | 21 |
| `halfYearLevel` | 一年半销售级别（18 个月） | AA AB AC BA BB BC CA CB CC DA DB DC EA EB EC FA FB | 17（无 FC） |
| `yearLevel` | 总销售级别（全部时间） | AA…HC（A~H × A/B/C） | 24 |
| `cityLevel` | 城市级别 | A~H | 8 |
| `storeLevel` | 门店级别 | A~H | 8 |

- `set_level` 无 NULL、无空串、无重复；**没有中文名称列**（只有 `level_code` 分组、`level` 编码、`ranking`），编码含义无法从库内映射。
- 原文档 `GA~FC`（company，集合实际等价于 AA~GC，但表述误导）、`AA~FC`（half，`FC` 不存在，属错误）、`AA~HC`（total，正确）本轮统一改为真库枚举。
- 真库 `member_info.company_level` 有 1 条游离值 `'A'`（不在 set_level 枚举内），属数据异常，待 CRM 确认。

### 二、描述改动

`semantic/metadata.yaml`（3 个级别维度注释 + levels `filterGuidance`）、`metrics/member.yaml`（`MEMBER_LEVEL_DIST` caliber）、客户端 `ChatServiceImpl` 提示词第 14 条、`ToolPromptSnippets.AMBIGUOUS_MEMBER_LEVEL`、`MemberInfo` 三个级别 Javadoc、`SUPPORTED_METRICS.md`、`DATABASE_SCHEMA.md` 均改为真库枚举，删除“推断/待核对”标注。

### 三、DDL 注释复核

`DATABASE_SCHEMA.md` 保留 DDL 原文，新增 3.5 节逐字段对照表（`plus_type`、`channel_type`、`track`、`customer_cost`、`member_allot_store`、`office_id`、`isAllotStaff`、`member_is_allot_store`、`member_expense_totalsum(_year)`、`reserved2`、`expense_d_commName`、`expense_d_commCodeName`）。

真库补充事实：
- `member_info_view` 的 `TABLE_TYPE=BASE TABLE`（触发器同步的独立表，不是 SQL 视图），428,930 行 vs `member_info` 有效会员 628,527 行；表中 `discard_type=1` 为 0 条。同步规则未知。
- `channel_type` 真库出现 `1/2/3/4`（`4` 仅 1 条），无 `0`；页面 CASE 只映射 1–3。
- `plus_type` 真库仅 `NULL`/`'1'`；`track` 真库仅 `0/1`；`isAllotStaff` 真库仅 `'1'/'2'`；`customer_cost` 为 8 个中文标签 + NULL。
- `member_info_view` 中三个级别列非空分别 428,800 / 428,799 / 428,798，去重 21 / 17 / 24，与 `set_level` 枚举一致，级别分布指标结构上可用；基础表 `company_level` 的 1 条游离值 `'A'` 未进入视图表。

### 四、查询逻辑复核

逐处核对 `MemberInfoMapper.xml`、`MemberStatMapper.xml`、`ExpenseStatMapper.xml`、`metrics/*.yaml`、`semantic/metadata.yaml`：未发现需要改 SQL 的确定性错误（无 `plus_type=0`、无 `office_id` 替代 `member_allot_store`、无 `track=2` 当“否”、无 `customer_cost` 按编码处理、商品名/类别名未再混用）。待业务确认项见报告。

### 五、测试

本轮改了 `metadata.yaml` 与 `ToolPromptSnippets`，需重生成契约快照后跑定向测试；结果见当次执行记录。

## 2026-11-10：七项口径确认落地

产品对上一轮 7 个待确认项给出明确答复，按答复执行（只读查库，未改 CRM 源码）。

1. `plus_type`：`1`=PLUS，其余（含 NULL）均非 PLUS；`PLUS_TYPE` 维度 `nullLabel` 改为“非PLUS”，guidance/实体注释/文档同步。
2. `channel_type`：只支持 `1` 饿百 / `2` 美团 / `3` 京东到家；真库 1 条 `'4'` 不做映射、按未知/空值处理。
3. `member_info_view`：统一表述为 `member_info` 行更新后由触发器 `ff_update_member_info_view` 同步的**物理表**（不是 SQL 视图），删除“视图定义不在源码仓库/是否内置过滤未知”。
4. 数据源对齐：CRM `MemberInfoController → MemberInfoService.findPage → MemberInfoDao.findList` 实际 `FROM member_info_view a`，固定条件仅 `a.del_flag='0'`，与 `MEMBER_OVERVIEW` 完全一致；行数差异（628,527 vs 428,930）只作背景，不再表述为“数量偏低”风险。
5. `MEMBER_LEVEL_DIST`：新增 `HAIDING_LEVEL`(reserved15)、`COMPANY_LEVEL`、`CITY_LEVEL`、`STORE_LEVEL`、`PERSONAL_LEVEL` 五个维度，层级字段覆盖追问候选全部 8 个级别维度；caliber 写明真库枚举（company 21 / halfYear 17 / totalYear 24 / city 8 / store 8 / personal 8 / haiding 9 个中文标签），空值按 `nullDimensionLabel='未填写'` 归并。
6. `HAS_EXPENSE`：条件模板只能绑定本实体字段，无法切到 `member_info` 基础表或订单表，原快照判定 `member_expense_totalsum > 0` 又非实时，故**删除该 preset**，需要时走 `MEMBER_CONSUMPTION`。
7. `company_level='A'`：`set_level` 中存在 `level_code='companyLevel'`、`level='A'`、ranking 0.2 的父级行，CRM `getGrade()` 明确排除该父级组；该会员不在 `member_info_view` 中。结论：父级码/数据异常，原样输出、不映射等级含义。

影响：`catalog-adhoc.json`（条件模板列表）、`catalog-metric.json`（层级维度）、`tool-definitions.json`（描述）需重生成；`sql-golden.json` 预期零 diff（未改 SQL 生成逻辑）。

## 2026-11-10（二）：按 CRM 会员列表页请求参数最大化对齐筛选能力

以一条完整会员列表页请求参数为样本，逐参数对照 `MemberInfoDao.findList` 归类 A/B/C/D，并直接落地 B 档（仍只读查库，未改 CRM 源码）。

1. B 档新增可过滤列：`member_name`（PII，LIKE 包含匹配，有 `index_name`）、`channel_type`（无索引，1 饿百/2 美团/3 京东到家）、`reserved2`（最近一次有效消费时间，有 `index_reserved2`）、`member_expense_totalsum_year`（decimal(20,2)，有索引；仅用于筛会员、禁止当金额输出）。
2. 新增 `CHANNEL_TYPE` 维度（其余值统一“未知渠道”）；`column-types.yaml` 的 `member_name` 注释改为“可过滤、禁止原值输出”。
3. `strMoney/endMoney` 因此不再需要 HAVING：CRM 的金额区间就是 `member_info_view` 上已按会员预聚合的 18 个月快照列，同一列两条过滤（`>=` / `<=`）即可等价；`consumptionBeginTime/EndTime` 用 `reserved2` 的 `>=` / `<` 两条过滤复刻右开窗口，也不占用唯一 `timeRange` 槽位。
4. 仍属 C 档（需引擎改造）：多值 IN、机构→门店集合（`office.id`）、商品名 `MATCH ... AGAINST`（RIGHT JOIN 明细存在性）、电话回访 `answerVisit` 子查询、按明细实时汇总的 HAVING；`answerVisitBegin/End` CRM 自身未使用，列 D 档。
5. `AdhocRequestValidatorTest` 中原“`member_name` 不可过滤”断言改为 `member_address`（PII 且仍未开放），保留“PII 列不在白名单即拒绝”的意图。

影响：`catalog-adhoc.json` 重生成（新增 1 个维度、4 个可过滤列、4 条 guidance）；`tool-definitions.json` 与 `sql-golden.json` 零 diff（未改工具描述与 SQL 生成逻辑）。

## 2026-11-10（三）：C1 多值 IN 与 C4 机构门店解析落地

1. `FilterSpec` 新增可选 `values`，操作符增加 `IN`/`NOT IN`；仅集合操作符可携带 `values`，`value` 必须为空，去重后最多 50 个（统一常量 `FilterSpec.MAX_IN_VALUES`），手机号三类列仍只允许 `=`。编译器按声明顺序展开 `IN (?,…)` 并绑定参数；不传 `values` 的旧请求 SQL 逐字不变。
2. 新增受控 MCP 工具 `resolveOfficeStores`：`officeId`/`officeName` 二选一；固定 MyBatis SQL 逐条对齐 CRM `OfficeDao.getHDStationIDs` 的本节点+`parent_ids` 下级、`type IN (6,7,13,14,11,22,28)`、8 位 `HD_ID`、`del_flag='0'` 条件。按 IN 集合语义对 `HD_ID` 去重，不开放 `sys_office` 自由查询。
3. 名称查询依次精确/前缀/包含；多候选或名称命中非门店机构返回 `needs_clarification`，不自行选择。`status=resolved` 的 `storeCodes` 可直接喂给 `member_allot_store IN`；超过 50 个返回 `too_many_stores` 且不给部分编码，禁止截断或分批求和。
4. 真库实际调用 `resolveOfficeStores(officeId='1')`：机构“集团总部”（officeType=2）、`storeCount=375`；用同一固定子查询统计 `member_info_view` 命中会员 426,332，均与既有实测一致。因 375>50，工具按约定返回 `too_many_stores`，未生成不完整会员筛选。
5. 工具注册加入服务端 `ToolCallbackProvider`；客户端通过 MCP 动态发现、通用 `ToolCallbackDecorator` 包装，无需逐工具登记。`ChatServiceImpl` 系统提示词、`AdhocTool` 描述及 metadata guidance 已补机构范围和 IN 使用说明。
6. 契约：`catalog-adhoc.json` +22/-1、`tool-definitions.json` +8/-4；`catalog-metric.json` 与 `sql-golden.json` 零 diff。旧 SQL 路径未改，既有 golden 用例无需变化。
7. 实际测试：定向 131 项、mcp-server 全量 287 项、mcp-client（排除 TimeTest）43 项均通过。

## 2026-11-10（四）：追问卡片缺选项（从后端侧修复提示词）

现象：用户问"他的销售额等级、顾客价值、是否是plus会员、从哪注册的、新会员等级是什么"，选完"18个月销售等级"后，模型第二次追问"从哪注册的"只输出"还需要确认口径，请选择："，前端没有可点选项。

根因（后端侧）：
1. 第 9 条硬映射表没有"从哪注册的/注册渠道"到 `CHANNEL_TYPE`、也没有"注册门店"到 `REG_STORE` 的映射，触发场景清单（第 10 条）也不含渠道。模型知道要问，但没有确定候选，只能自由发挥。
2. 提示词对 `<clarify>` 只有示例，没有统一硬约束：未要求 `candidates` 非空、每项必须有 `code`/`label`，也未禁止"只写引导语"。模型因此输出了无标记块的纯文本追问。
3. 第 17 条只写"先让用户确认机构"，未要求把 `resolveOfficeStores` 的 `officeId/officeName/officeType` 转成卡片 `code/label/meta`。
4. 前端解析不是根因：`utils/clarify.ts` 已兼容 `candidates/options/choices` 等字段名、JSON 损坏、块截断与空候选兜底卡，`ChatMessages.vue` 只在解析出候选后渲染选项；纯文本"请选择："不会生成卡片。

改动（`mcp-client/.../ChatServiceImpl.java` 系统提示词）：
1. 第 9 条映射表新增"从哪注册的/注册渠道 → `CHANNEL_TYPE`、注册门店 → `REG_STORE`"，并写明渠道 4 个确定取值（饿百/美团/京东到家/未知渠道）与过滤用 `channel_type='1'/'2'/'3'`；顾客价值直接按 `CUSTOMER_VALUE` 输出、不追问。
2. 第 14 条补 `PERSONAL_LEVEL` 只有 `a~f`、无中文映射：按编码原样输出，不得编造中文等级或就编码含义再追问。
3. 第 17 条要求 `needs_clarification` 必须输出 `type=station` 的 `<clarify>`，并把 `officeId→code`、`officeName→label`、机构类型→`meta`；`too_many_stores` 用普通文字请用户缩小范围。
4. 新增第 18 条"追问统一硬约束"：必须输出完整 `<clarify>`；`candidates` 非空、每项 `code+label`（`meta` 可选）；`type` 仅 `station/member/ambiguity`；输出前自检；无确定候选时不得编造也不得输出空卡片，改为普通文字索取完整手机号/门店名；会员候选涉及姓名/手机号时不得写进卡片。
5. 第 8 条字段说明同步为 `type=station`（会员多候选见第 18 条 f）。

未改：`ToolPromptSnippets`（工具 description 不含前端结构字段，结构与模型行为约束统一由系统提示词承载）；`metadata.yaml`（渠道枚举已在 `CHANNEL_TYPE` 维度与 guidance 中存在，无需重复）；契约快照零 diff。实际测试：`mvnw -pl mcp-client test -Dtest='!TimeTest'` 通过。

## 2026-11-10（五）：IN 上限 50 → 500，对齐 CRM 总部级机构范围

背景：CRM 侧总部级（`office.id=1`，375 个门店编码）用 `GROUP_CONCAT` + `in (${HDIDs})` 能正常查询；本项目此前 50 上限会直接拒绝，导致机构覆盖范围明显窄于 CRM。

改动：
1. `FilterSpec.MAX_IN_VALUES` 50 → 500（唯一常量）；`FilterSpec.values` 的 `@ToolParam` 描述同步为 500。
2. `OfficeStoreTool.MAX_STORE_CODES` 直接引用 `FilterSpec.MAX_IN_VALUES`，不另写数字；`@Tool` description 与 `too_many_stores` 的 `actionHint` 文案改为 500，并注明总部 375 店在上限内。
3. **修复隐性截断**：`OfficeMapper.xml` 的 `listOfficeStoreCodes` 原为硬编码 `LIMIT 50`，上限提到 500 后会造成 `status=resolved` 但只回 50 个编码的静默截断。改为 `LIMIT #{limit}`，由工具传入 `MAX_STORE_CODES`；`OfficeMapper.listOfficeStoreCodes` 增加 `limit` 参数。
4. `metadata.yaml` 机构 guidance 与 `ChatServiceImpl` 提示词第 17 条的 50 表述改为 500（总部 375 店在上限内）。
5. 测试：`AdhocRequestValidatorTest` 改为 500 通过 / 501 拒绝并保留去重与 `value` 互斥断言；`OfficeStoreToolTest` 新增 375 店 `resolved`（`storeCodes` 长度 375、`maxStoreCodes=500`、`truncated=false`），500 以上用 501 断言拒绝且不返回部分编码；`AdhocSqlCompilerTest` 新增 500 个 IN 值断言 500 个 `?`、参数数量与顺序一致且值不拼进 SQL；`OfficeMapperIntegrationTest` 的上限断言改用常量。
6. `docs/ai-agent/CAPABILITY_CHECKLIST.md` 同步：IN 上限 500、总部 375 店可查、测试问题预期结果更新。
7. 契约快照按新描述重生成：`tool-definitions.json`、`catalog-adhoc.json`；`sql-golden.json`、`catalog-metric.json` 零 diff。

真库实测：`resolveOfficeStores(officeId='1')` → `status=resolved`、`storeCount=375`、`maxStoreCodes=500`、`storeCodes` 长度 375、`truncated=false`；`member_info_view` 命中 426,332 会员的既有实测口径不变。

测试：定向 90 项通过；`mvnw -pl mcp-server -am test -Dprofiles.active=` 全量 289 项通过（`SqlGoldenSnapshotTest` 通过即 sql-golden 无 diff）。

## 2026-11-10（六）：CRM customer_cost 口径差异定位 + 权限范围过渡期方案

用户反馈："一般发展用户 + 重要挽留用户"本系统回答 138,931，CRM 会员列表页返回 136,769。差 2,162 人。

真库实测定位根因：**CRM 会员列表页永远带当前登录用户的机构范围（`HDIDs` → `in (${HDIDs})`），本系统无登录态，默认查全库**。两边 `customer_cost IN ('一般发展用户','重要挽留用户')` 和 `del_flag='0'` 的 SQL 条件完全一致，取值也完全一致（中文原样），差异纯粹来自机构过滤。

差的 2,162 人拆解：总部外门店（大连 02xx、长春 03xx、深圳 52xx、佛山 53xx 等大区/独立机构）共 2,129 人，未分配门店（`member_allot_store='00000000'`）33 人。这些门店不在总部 375 店列表中，CRM 总部用户查询被 `HDIDs` 过滤掉了。

CRM 实现方式（只读分析，不改 CRM 源码）：
- `MemberInfoController.findPage()` 无条件调 `UserUtils.getUser().getOffice()` → `officeDao.getHDStationIDs(officeId)` 拿到门店编码串 → 填进 `HDIDs` 字段。
- `OfficeDao.getHDStationIDs`：`GROUP_CONCAT(CONCAT('''', HD_ID, ''''))` 拼出 `'01010601','01010602',...` 格式的字符串（受 `group_concat_max_len` 默认 1024 限制，风险已知）。
- `MemberInfoDao.xml`：`a.member_allot_store in (${HDIDs})`（字符串直接拼接，无参数化；同页内重复 3~6 次）。
- 全库无一处 `member_allot_store in (select ... from sys_office ...)` 子查询写法。

方案评估（三个候选，最终选 C）：
- A. 让模型主动调 `resolveOfficeStores`：**绝对不能用**，依赖模型自觉、有安全漏洞（模型可能漏调、可能错调），不符合"数据权限是后端强制底线"的要求。
- B. 硬编码总部 office.id=1 进 AdhocQueryService：过渡成本高，未来接登录时要挖掉硬编码、换成动态 userId→officeId 链路。
- C. **保持现状 + 明确差异 + 预留服务层注入口子**：当前全库（无登录态、无可隔离），未来在 `AdhocQueryService.execute()` 入口强制注入当前用户权限内的 `member_allot_store IN (...)` 过滤（与模型显式指定的范围自动取交集，全局管理员豁免）。改动点固定在服务层、不进 metadata.yaml 的 baselineFilters（权限范围是动态的，不同用户不同范围）。选 C 的核心理由：**底层能力已全部就绪**（OfficeMapper 解析门店、FilterSpec/IN 参数化、AdhocSqlCompiler 生成占位符），未来接入登录只需要在服务层加 3 行代码，不动模型、不动提示词、不动 metadata。

实际改动：
1. `docs/ai-agent/CAPABILITY_CHECKLIST.md` 门店范围章节后加口径差异说明，明确"当前默认全库、CRM 列表页默认登录机构、未来服务层强制注入"。
2. `metadata.yaml` member.baselineFilters 旁加权限范围注释，说明"不进 baselineFilters、未来服务层注入"，给未来开发者留口子。

没做：代码逻辑零改动、不跑全量测试、不接登录、不注入任何默认范围。

验证：`SemanticCatalogContractTest` / `SemanticCatalogServiceTest` / `ToolContractSnapshotTest` 通过（契约加载正常）。

## 2026-11-10（七）：名称类查询统一为“精确优先、逐级降级、多候选才追问”

现象：用户问“我们集团总部的一般发展用户和重要挽留用户一共有多少？”——`集团总部` 是 `sys_office.name` 全名，却被系统走了追问路子。

根因（在工具实现，不在提示词）：`OfficeStoreTool.resolveByName` 虽然已按 精确 → 前缀 → 包含 逐级查询，但“唯一命中”后又加了一道 `STORE_TYPES` 类型校验：`集团总部` 的 `type=2`（总部，不在门店类型集合 6/7/13/14/11/22/28 内），于是返回 `needs_clarification / non_store_office`，要求用户确认后用 officeId 重查。而按 officeId 的路径本来就能解析非门店机构的下级门店，两条路径行为不一致 → 名称查询被凭空多绕一问。另：原“精确”档实际用的是 `LIKE #{officeName}`（无通配符），名称里若含 `%`/`_` 会退化成模糊匹配。

改动：
1. `OfficeMapper` 新增 `findOfficesByExactNames(names)`，SQL 用 `a.name IN (...)`（= 语义，不是 LIKE），只接受服务端生成的确定变形；`findOfficeCandidatesByName` 继续承担前缀/包含。
2. `OfficeStoreTool.resolveByName`：名称先归一化（`trim` + 全角空格转半角 + 连续空白压缩），再按 **精确（原名 / 原名+店 / 原名+门店，一次 IN 查询）→ 前缀 → 包含** 逐级降级，命中即停；**删除 `STORE_TYPES` 与非门店类型的 `non_store_office` 分支**——唯一命中一律 `resolveOffice`（总部/大区照常解析下级门店），只有多候选才返回 `candidates` 让用户选。
3. `@Tool` 描述、`metadata.yaml` 机构 guidance、`ChatServiceImpl` 第 17 条统一改写：resolved 直接查询，不得因“机构类型不是门店”再确认；只有 `needs_clarification`（降级后多候选）才输出 `<clarify>`。
4. 测试 `OfficeStoreToolTest`：新增全名精确命中总部（type=2）→ resolved/375 店、精确+后缀变形命中、包含唯一命中直接用、全角空格归一化；多候选用例改为断言精确档空才走前缀并出候选；删除原 `non_store_office` 断言。

真库实测（只读）：
- `resolveOfficeStores(officeName='集团总部')` → `status=resolved`、`officeId=1`、`officeType=2`、`storeCount=375`、`storeCodes` 长度 375、无 `candidates`。
- 目标答案：`member_info_view` 中 `del_flag='0'` 且 `member_allot_store IN (总部 375 店)` 且 `customer_cost IN ('一般发展用户','重要挽留用户')` = **136,769**（一般发展用户 125,810 + 重要挽留用户 10,959），与 CRM 列表页 136,769 一致。

回归：`guessStation` 本就是精确（含 +店）→ 前缀 → 包含、命中即停，行为未变；`GuessToolTest` 5 项通过。

测试：定向 99 项通过；mcp-server 全量 292 项通过；mcp-client 15 项通过。契约快照 `tool-definitions.json`、`catalog-adhoc.json` 按新描述重生成；`sql-golden.json`、`catalog-metric.json` 零 diff。

## 2026-11-10（八）：不考虑渠道店（type=18）+ 门店名查不到时兜底机构表

业务确认：**助手项目不考虑渠道店**（`sys_office.type=18`，HD_ID 非 8 位数字）；门店名/门店清单的数据源**继续用会员表**，不换成机构表，只补兜底。

为什么数据源不换（真库对比，排除渠道店）：
- 机构表可统计门店 375 个，会员表出现过的门店编码 255 个，交集 237、机构表独有 138、会员表独有 18。
- 机构表独有 138：97 家有订单但无任何会员记录（几乎全是 type=11，近 1 年仍有 7,392 单），41 家无订单无会员（29 正常空店 / 7 闭店停用 / 5 要客部与新媒体）。
- 会员表独有 18：16 家机构表完全查不到 + 2 家已删除，合计 **2,504 个会员**。
- 名称不一致：交集 237 个编码中有 **31 个（13.1%）** 两张表名称不同，覆盖 **34,387 个会员**（如 `03010109` 会员表“沈阳西江街店”vs 机构表“沈阳中海和悦府店”2,852 人）。
- 结论：换源会用“138 家 0 会员门店”换掉“18 个编码 2,504 会员 + 34,387 会员的名称命中”，净亏，故不换。

改动：
1. `GuessTool`：`member_info_view` 三级匹配（精确 → 前缀 → 包含）全空后，用 `sys_office` 同序兜底。兜底口径 `del_flag='0' AND LENGTH(HD_ID)=8 AND type IN ('6','11','22','28')`，排除要客部 13、新媒体 14、渠道店 18。唯一命中返回 `matchedBy=office_only`、`memberCount=0`、`memberRecord=false`、`stationCode`/`stationName`，并查有效订单（`expense_state='10' AND del_flag='0'`）给出 `hasOrders`；多命中沿用候选追问；仍无才 `no_match`。
2. `OfficeMapper` 新增 `findFallbackStoresByExactNames` / `findFallbackStoresByName` / `countOrdersByStationCode`；`OfficeMapper.xml` 新增 `fallbackStoreScope`（门店口径）与订单存在性 `SELECT EXISTS(...)`。
3. `OfficeStoreTool`：`resolveOfficeStores` 门店类型集合由 `6/7/13/14/11/22/28` 收敛为 `6/11/22/28`；`no_stores` 提示按机构类型区分“渠道/加盟类不纳入统计”“机构下没有可统计门店”，删除误导性的“请核对机构范围”。
4. `GuessTool` / `OfficeStoreTool` 的 `@Tool` 描述、`metadata.yaml` 门店与机构 guidance、`ChatServiceImpl` 第 8/17 条同步口径。
5. 测试：`GuessToolTest` 新增会员表命中不触发兜底、兜底唯一命中（有/无订单）、兜底多候选出卡片、排除类型仍 `no_match`；`OfficeStoreToolTest` 覆盖 369 店与 `no_stores` 语义；`OfficeMapperIntegrationTest` 校验兜底 SQL 排除 18/13/14 且订单存在性可执行。

真库实测（只读）：
- `office.id=1` 门店数 **375 → 369**（少的 6 个为要客部 13 ×5、新媒体 14 ×1；这 6 个编码上会员数为 **0**）。
- `guessStation("竹叶青长江路店")` → `matchedBy=none`、`count=0`、`actionHint=no_match`（type=18 被排除，符合预期）。
- `guessStation("哈尔滨尚志世纪店")` → `matchedBy=office_only`、`stationCode=51010211`、`memberCount=0`、`hasOrders=true`、`actionHint=unique_match`。
- “集团总部的一般发展用户和重要挽留用户一共有多少”：369 店口径 = **136,769**（一般发展用户 125,810 + 重要挽留用户 10,959），与收敛前 375 店结果完全一致。


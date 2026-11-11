# SpringAi-MCP-RAG-Dev：CRM 数据分析 Agent 项目背景

## 一、项目定位

`SpringAi-MCP-RAG-Dev` 是本项目的开发主体，是一个独立开发、独立运行的 AI 应用。项目计划在现有代码基础上增加 CRM 数据分析能力，通过直接连接外部 CRM MySQL 测试数据库读取业务数据。

本项目不是 CRM 源码项目的组成部分，不在 CRM 系统内部开发。CRM 仅作为外部数据源；除非获得明确授权，本项目不修改 CRM 源码、数据库表结构、触发器或业务数据。

## 二、业务目标

面向经过授权的企业管理者，支持用自然语言查询会员、消费订单、门店经营和商品销售等 CRM 业务数据。

建议的处理流程：

用户提问 → AI 理解意图 → 调用受控工具 → Java 服务验证参数和权限 → 查询 CRM MySQL → 计算并返回结构化结果 → AI 根据真实结果组织回答。

示例问题仅用于讨论，最终范围由产品经理确认：
- 指定时间范围内的消费金额是多少？
- 某个时间范围内有多少有效订单？
- 哪些门店的经营数据较高？
- 某位会员的消费摘要是什么？
- 哪些商品销售表现较好？

## 三、当前已知条件

- 现有项目：`SpringAi-MCP-RAG-Dev`
- 项目类型：独立 AI 应用
- 外部数据源：CRM MySQL
- 数据库连接方式：应用直接连接 CRM MySQL
- 数据库环境：已有测试数据库，应优先在测试环境验证
- 已知核心表：`expense_calendar_info`、`expense_calendar_details`、`member_info`、`member_info_view`
- 已知触发器：`ff_update_member_info_view`，在 `member_info` 更新后同步部分字段到 `member_info_view`
- 已知会员体系：普通会员、有效会员/非要客、要客（详见 `BUSINESS_METRICS.md` §六）
- 已交付查询能力：
  - **固化指标工具**：`queryMemberMetric`/`queryOrderMetric`/`listMetrics`/`explainMetric`，指标即配置（`resources/metrics/*.yaml`），详见 `SUPPORTED_METRICS.md`。
  - **Ad-hoc 自助分析工具**（2026-11-03 交付）：`adhocQuery` / `adhocQueryBatch`（多块独立查询，≤5 块）/ `listSemanticCatalog` / `explainAdhoc`，在语义白名单内自由组合维度×度量×条件模板×时间窗口的探索性查询；语义层唯一事实来源为 `mcp-server/src/main/resources/semantic/metadata.yaml`（扩展可查列 = 改 YAML），安全边界与口径详见 `BUSINESS_METRICS.md` §十。
  - **Ad-hoc 关键约定**（2026-11-03）：① 明细表 `detail` 带实体级查询守卫（必须时间窗≤92 天或手机号/流水号等值之一）；② 门店过滤一律编码优先——info/details 名称列无索引已移出过滤白名单，名称不完整/不确定时先模糊查 member 解析，多候选问用户确认、0 候选要编码；③ 可过滤列带 `indexed` 标注，模型组参优先索引列；④ 超时三档：SQL hint 60s < JdbcTemplate 65s < MCP 请求 340s（dev）。
- 关键文件：
  - `mcp-server/src/main/resources/semantic/metadata.yaml` —— Ad-hoc 语义元数据（实体/维度/度量/条件模板/JOIN 关系与基数/countKey/约束/实体级 filterGuidance、queryGuard、indexedColumns）
  - `mcp-server/src/main/resources/metrics/{member,order}.yaml` —— 固化指标配置
  - `mcp-server/src/main/java/com/itzixi/adhoc/` —— Ad-hoc 引擎包（`SemanticRegistry` 加载器、`AdhocValidator` 白名单校验、`AdhocSqlCompiler` 编译、`AdhocQueryService` 编排、`model/AdhocBatchRequest` 批量请求）
  - `mcp-server/src/main/java/com/itzixi/mcp/tool/AdhocTool.java` —— 4 个 Ad-hoc MCP 工具
- 业务需求仍需与产品经理进一步确认

## 四、现有仓库技术背景

本项目原有仓库包含 Spring AI、MCP、RAG 等探索性实现。具体依赖版本、模块结构、配置方式和可复用组件必须通过检查当前仓库确认，不要仅凭项目名称推断。

开始开发前，检查：
- Java、Spring Boot、Spring AI 的实际版本。
- 模块和包结构。
- 现有模型调用封装。
- 现有 Tool Calling / MCP Client / MCP Server 实现。
- 现有配置文件和环境变量加载方式。
- 数据访问依赖与异常处理方式。
- 测试框架和已有测试。
- 现有日志、鉴权和审计能力。

## 五、CRM 核心数据表

### 5.1 expense_calendar_info

消费订单或消费流水相关表。已知重要字段包括：
- `expense_mobile`：消费用户手机号
- `expense_tenant`：商户或租户相关字段
- `expense_stationCode`、`expense_stationName`：门店编码、名称
- `expense_orderId`：消费流水号
- `expense_buyDate`：消费时间
- `expense_stdtotal`：消费零售总金额，VARCHAR
- `expense_realamt`：消费实付总金额，VARCHAR
- `expense_reccnt`：商品种类数量，VARCHAR
- `expense_type`：注释为 1 线下、2 线上
- `expense_state`：注释包含 10 正常、20 已退单
- `del_flag`：删除标记
- `gross_margin`：毛利相关字段，VARCHAR
- `platform_type`：注释为 0 本部、1 饿了么、2 美团

### 5.2 expense_calendar_details

消费订单商品明细表。已知重要字段包括：
- `expense_d_mobile`：消费用户手机号
- `expense_d_stationCode`、`expense_d_stationName`：门店编码、名称
- `expense_d_orderId`：订单或消费流水号
- `expense_d_buyDate`：消费时间
- `expense_d_stdtotal`、`expense_d_realamt`：商品零售金额、实付金额，VARCHAR
- `expense_d_qty`：商品数量，VARCHAR
- `expense_d_itemno`：商品货号
- `expense_d_gdcode`：商品编码
- `expense_d_commName`：商品名称
- `expense_d_commCode`、`expense_d_commCodeName`：商品相关编码和名称
- `expense_d_state`：注释包含 10 正常、20 已退单

### 5.3 member_info

会员信息和部分会员累计统计表。已知重要字段包括：
- `member_name`：会员姓名
- `member_mobile`：会员手机号
- `member_created`：会员创建时间
- `member_cardno`：会员卡号
- `member_store`、`member_tenant`：会员所属门店、商户相关字段
- `member_expense_time`：消费次数相关字段
- `member_expense_totalsum`：消费总额相关字段
- `member_expense_time_lately`：近期消费时间相关字段
- `member_expense_time_year`：周期消费次数相关字段
- `member_expense_totalsum_year`：注释描述为近一年半消费金额
- `gross_margin_year`：周期毛利相关字段
- `del_flag`：删除标记，其默认值和注释含义需要核实

该表还包含个人信息字段。必须遵守数据最小化原则。

### 5.4 member_info_view

会员信息视图，由触发器 `ff_update_member_info_view` 在 `member_info` 更新时同步。字段结构与 `member_info` 基本一致，查询会员信息时优先使用此视图。

要客（跟踪会员）相关核心字段：
- `track`：跟踪状态，0=未跟踪，1=跟踪（要客）
- `track_user_id`：跟踪人ID（负责该要客的导购/店员）
- `track_time`：追踪时间
- `track_cancel_time`：被踢出要客的时间
- `track_cancel_reason`：未跟踪原因

会员池相关字段：
- `importan_pond_user_type`：0=正常，1=池中
- `importan_pond_user_date`：入池时间
- `discard_type`：是否废弃会员，1=废弃

完整字段说明见 `DATABASE_SCHEMA.md` §3.4。

## 六、已知数据风险

1. 订单和明细中的金额、数量字段有 VARCHAR 类型，需要检查空值、非数字内容和异常格式。
2. 不得只根据 `expense_state` 的注释决定所有统计的有效订单过滤规则。
3. `member_info.del_flag` 的默认值与注释存在歧义，需要确认实际业务含义。
4. `member_expense_totalsum_year` 的注释为近一年半消费金额，不能直接当作自然年度数据。
5. 订单表和明细表可能是一对多关系，联查时防止订单级金额重复累计。
6. `expense_orderId` 是否全局唯一、在哪个租户或门店范围内唯一，尚待确认。
7. 退款、退单、部分退款和重复流水的处理方式尚待确认。
8. 会员累计字段与订单实时汇总的口径和一致性尚待验证。
9. 数据库只读权限不代表可以无条件读取所有个人信息；仍需实施数据范围与字段级控制。

## 七、建议的技术方案

优先复用现有仓库能力，按实际代码决定实现细节。

建议分层：
1. 对话/Agent 层：理解自然语言、选择工具。
2. 工具层：提供清晰、有限的业务查询工具。
3. 业务服务层：校验参数、执行权限规则、统一业务口径。
4. 数据访问层：通过独立配置的只读连接访问 CRM MySQL。
5. 结果层：返回结构化数据，供模型生成解释。

不要一开始就开放任意 SQL 执行能力。优先实现经过审核的查询模板或 Mapper。金额、计数、排名和比例等由 SQL 或 Java 确定性计算。

## 八、MVP 候选范围

候选功能包括：
- 指定时间范围消费金额统计。
- 指定时间范围有效订单数量统计。
- 门店经营数据比较。
- 会员消费摘要。
- 商品销售排行。

以上均为候选项，必须由产品经理确认业务价值、指标定义、权限边界和验收标准。

## 九、待确认事项

### 业务
- 首批支持哪些真实业务问题？
- 目标用户和可访问的数据范围是什么？
- 实收金额、零售金额、营业额、毛利分别如何定义？
- 有效订单、退单、退款和部分退款如何处理？
- 时间默认值和统计边界如何定义？
- 是否按租户、门店、平台或渠道拆分？〔已答复（2026-10-30）：**只按门店拆分**，租户/商户维度已取消，不再支持筛选与统计；详见 `BUSINESS_METRICS.md` §五与 §七〕
- 结果需要表格、摘要还是图表？

### 数据和安全
- 测试数据库的只读账号是否已准备？
- 测试数据是否已脱敏？
- 数据库连接是否要求内网、VPN、跳板机或 IP 白名单？
- 哪些字段允许返回给模型和最终用户？
- 是否存在现成的数据权限规则或用户身份映射？

### 技术
- 当前项目使用的准确框架版本是什么？
- 使用 JDBC、MyBatis、MyBatis-Plus 还是已有数据访问组件更合适？
- 是否需要将查询能力封装成 MCP Tool，还是先使用 Spring AI 原生 Tool Calling？
- 如何执行集成测试并与标准答案对账？

## 十、开发约定

每次任务开始先读取本文件和 `AGENTS.md`。先检查现有仓库，再提出实现建议。

如果业务口径不明确，先列问题；如果用户要求只分析，不修改代码。所有测试结论必须基于实际执行结果。每次完成重要任务后更新 `DEVELOPMENT_LOG.md`。

# 安全设计

## 三层纵深

### 第一层：编译期闸门（覆盖 100%）

主路径 SQL 全部由 `SqlCompiler` 从 YAML 配置确定性生成，参数一律 `?` 占位。不存在"模型现场生成 SQL"的注入面。

| 闸门 | 机制 | 配置位置 |
|---|---|---|
| 指标白名单 | model 只能输出 YAML 里声明的 metric code | `metrics/*.yaml` `MetricDefinitionValidator` |
| 维度白名单 | dimension/filter/sortBy 必须在指标声明范围内 | `QueryPlanValidator` |
| grain 不跨粒度 | ORDER_HEAD 指标不能用明细表维度 | `MetricDefinitionValidator` 启动期硬校验 |
| VARCHAR 必须 `NUM()` | 金额列直接 SUM 会隐式截断，统一包装 | `column-types.yaml` 声明 + 编译器自动包 |
| 时间边界硬编码 | 左闭右开 `>= ? AND < DATE_ADD(?, INTERVAL 1 DAY)` | 编译器生成，配置不参与 |
| 时间跨度上限 | 全局 550 天，指标可显式收紧（如 PRODUCT_RANKING 92 天） | `MetricsLimits.globalMaxRangeDays` + `metric.maxRangeDays` |
| 参数化占位 | 所有动态值一律 `?`，字符串永不拼接 | `GeneratedSql.params[]` |

**关键认知**：防注入靠白名单不靠黑名单。白名单从源头排除，黑名单做最后防线。

### 第二层：执行层 Guard（最后防线）

`ReadOnlySqlGuard` 是不依赖上游正确性的 fail-closed 最后防线。任何进入 JDBC 的 SQL 必须通过：

| 校验项 | 实现 | 防什么 |
|---|---|---|
| 单条 SELECT | 正则 `^\s*SELECT\b` 开头 | 多语句 `;`、DDL/DML |
| 剥离字符串再扫描 | 字符串/反引号内容先置空 | 展示文案 `'delete'` 误伤 |
| 禁止关键词 | `FORBIDDEN` Pattern（insert/update/delete/drop/alter/truncate/union/sleep...） | 写操作、DDL、攻击函数 |
| 禁止系统对象 | `information_schema` / `mysql.*` / `performance_schema` | 探测表结构 |
| 禁止 INTO OUTFILE / LOAD_FILE | Pattern 直接匹配 | 文件读取/写入 |
| 唯一允许的注释 | `/*+ MAX_EXECUTION_TIME(N) */` MySQL 优化 hint | 绕过注释注入 |

### 第三层：运行期兜底

```java
JdbcTemplate template = new JdbcTemplate(dataSource);
template.setMaxRows(5000);           // 结果集硬上限
template.setQueryTimeout(10);        // 10 秒超时
// SQL 前缀加: /*+ MAX_EXECUTION_TIME(8000) */
// 数据库层: GRANT SELECT 独立只读账号
```

| 机制 | 防什么 |
|---|---|
| `MAX_EXECUTION_TIME` hint | 慢查询拖垮数据库 |
| `maxRows` 5000 | 结果集爆炸占内存 |
| `queryTimeout` 10s | JDBC 层强制断开 |
| 只读数据库账号 | 即使前两层全被绕过也写不进 |
| `Statement.setReadOnly(true)` | JDBC 层标记只读 |

## 防 JOIN 翻倍

订单头表 × 订单明细表 = 每单金额被明细行数放大 10 倍。这不是注入，是业务逻辑错误，模型不会自己发现。

**解法**：`grain` 配置化 + 编译期硬约束。ORDER_HEAD 指标声明明细表维度 → 启动直接失败。

## 防 JOIN 重复计数

订单头表 JOIN 会员表：一个会员对应多笔订单时 `COUNT(*)` 会被放大。

**解法**：`entities.<name>.countKey = member_mobile` → 编译器自动把 `COUNT(*)` 改成 `COUNT(countKey)`。

## VARCHAR 金额清洗

`expense_realamt` 是 VARCHAR(12)，历史脏数据可能有空串、`'abc'`、`'1,234.00'` 逗号分隔。

**解法**：`NUM(col) = CAST(NULLIF(TRIM(col),'') AS DECIMAL(20,2))` — 空串 → NULL → NULLIF → DECIMAL 隐式 0。`column-types.yaml` 声明后编译器自动包装，遗漏时启动直接失败。

## PII 保护

| PII 列 | 位置 | 处理 |
|---|---|---|
| `member_name`, `member_mobile`, `member_address` | member_info | 禁止作为 dimension/attribute 输出 |
| `member_birthday`, `member_cardno` | member_info | 同上 |
| `expense_mobile` | expense_calendar_info | 禁止作为输出维度；可作为 JOIN 键（内部使用） |
| `userCode`/`userName`（营业员） | expense_calendar_info | 禁止输出 |

**例外**：指标如需输出 PII，必须显式声明并通过数据安全评估。当前所有 CONFIRMED 指标均不输出 PII。

## 数据权限

| 状态 | 说明 |
|---|---|
| 当前 | 未接入真实鉴权 — 会话不区分用户，只读全部门店数据 |
| 设计预留 | `SubjectContext{ stationCodes }` 由会话/登录态确定，编译时无条件注入门店范围条件 |
| 生产前必须 | 接入真实鉴权 + SubjectContext 注入，否则拒绝部署 |

## 审计日志

- **开启**：默认开启，可通过 `crm.audit.enabled=false` 关闭
- **位置**：`com.github.yingli.shared.audit.AuditLogAspect`
- **格式**：`[AUDIT] service=X metric=Y sqlHash=Z costMs=N success=true`
- **不记录**：PII、完整结果集、模型原始 prompt
- **sqlHash**：`SHA-256(sql 折叠空白规范化后)` 前 16 位 — 任何数字都能反查到具体 SQL

## 已知不覆盖（M4 待实现）

| 项 | 状态 | 影响 |
|---|---|---|
| AST 校验（JSqlParser） | 未实现 | 当前只有正则黑名单，AST 可绕过复杂场景 |
| 两级聚合 preAggregateBy | 未实现 | 复购率等非可加指标无法纯配置化 |
| 逃生舱 rawFragment | 未实现 | 长尾 SQL 无法通过配置之外途径交付 |
| SubjectContext 数据权限 | 未实现 | 当前读全表，生产必须补 |

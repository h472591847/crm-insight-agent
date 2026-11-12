# 方法论资产：企业问数 Agent 落地 5 讲

> 这些文章来自真实踩坑。你可以用它们做咨询售前、对内培训、或者直接发知乎/公众号。

---

## 01 指标口径怎么确认（最容易被忽略的硬阻塞）

**核心观点**：Text2SQL 省的是写 SQL，省不掉定义指标。口径不确认就直接写 SQL，输出的是"权威但错误的数字" — 比无数字更危险。

### 检查清单（每个指标必须回答）

| # | 问题 | 为什么重要 |
|---|---|---|
| 1 | 用哪个金额字段？`expense_realamt`（实付）还是 `expense_stdtotal`（标价）？ | 选不同字段，门店 GMV 可能差 20% |
| 2 | 时间边界是啥？`[start, end)` 左闭右开还是两头都含？ | 差一天可能差 100 单 |
| 3 | 退单/退款怎么处理？负行抵消？还是直接过滤退单状态？ | 两种做法 GMV 差 5-15% |
| 4 | 有效订单状态集合？`expense_state IN ('10','20')` 还是只算 `'10'`？ | 状态集差一个值，数字就漂 |
| 5 | 统计粒度？订单头（1 单 1 行）还是订单明细（1 单 N 行）？ | 跨粒度 JOIN 直接翻倍 |
| 6 | VARCHAR 金额列脏值怎么处理？空串？`'abc'`？`'1,234.00'`？ | 直接 SUM 某些门店金额偏差 30% |
| 7 | 分母选什么？人均消费的分母是"总会员数"还是"有消费会员数"？ | 两个口径人均差几倍 |
| 8 | 租户/门店维度要不要？租户已取消 → 配置里写 TENANT 直接启动失败 | 口径冻结后禁止随意改 |

### 我们的做法

1. 产品给口径 → 写进 `BUSINESS_METRICS.md`，**状态必须为 ✅ 已确认**
2. 技术翻译成 YAML 配置 → `status: CONFIRMED` 才允许执行
3. 未确认的指标 → `status: UNCONFIRMED` → **工具明确拒绝**，不给可疑数字
4. 结果强制回显 caliber 正文 → 任何人看到数字就能看到口径

### 实战踩坑

> 我们曾不小心把退单负行算进 GMV，导致数字比 CRM 报表高 8%。直到客户问"为什么和财务对不上"才发现。从此 `baselineFilters` 里强制 `del_flag='0'`，退单负行靠 `SUM` 自然抵消，不手动过滤。

---

## 02 语义白名单怎么设计（不是 Text2SQL）

**核心观点**：模型输出枚举组合，框架确定性编译 SQL。同一问题两次结果完全一致 — 数字可复现、可对账、可进老板看板。

### 为什么不是纯 Text2SQL

| 问题 | 后果 |
|---|---|
| 模型每次现场生成 SQL | 同一问题两次可能不同 SQL、不同数字 |
| 注入风险 | `SELECT * FROM members; DROP TABLE orders` |
| 口径漂移 | 模型可能漏 `del_flag='0'`，数字飘了没人知道 |
| JOIN 翻倍 | 订单头 × 明细 = 金额被放大 10 倍 |

### 两层配置 = 两道闸门

**第一层：指标配置（metrics/\*.yaml）— 确定性编译**

```yaml
- metric: STATION_SALES
  dimensions: [{ code: STATION, expr: expense_stationCode }]
  measures:
    - { code: SALES, expr: "NUM(expense_realamt)", agg: SUM }
```

模型只能输出 `metric=STATION_SALES, dimensions=[STATION]`，框架从 YAML 取 `expense_stationCode`，编译器硬编码 `SUM(NUM(expense_realamt))`。

**第二层：语义注册表（semantic/metadata.yaml）— Ad-hoc 查询**

```yaml
entities:
  order:
    table: expense_calendar_info
    alias: e
    baselineFilters: ["expense_state = '10'", "del_flag = '0'"]
    filterableColumns: [expense_buyDate, expense_stationCode]
    measures:
      - { code: GMV, expr: "NUM(expense_realamt)", agg: SUM, unit: CNY }
      - { code: ORDERS, expr: "*", agg: COUNT }
```

### 启动期硬校验

YAML 加载时直接校验，任何失败 → **启动失败**，不等到运行时：

- VARCHAR 数值列必须包 `NUM()`
- `grain` 不允许跨粒度
- PII 列（`member_mobile`, `member_name`）不得作为 dimension 输出
- `optionalFilters` 里不能有 TENANT（已取消）

---

## 03 Text2SQL 的安全边界（三层纵深）

**核心观点**：防注入靠白名单不靠黑名单，但黑名单是最后防线。

### 第一层：编译期闸门（覆盖 100%）

框架只从配置取表/列/表达式，参数一律 `?` 占位。**主路径不存在注入面**。

### 第二层：执行层 Guard（最后防线）

`ReadOnlySqlGuard` 在 SQL 进入 JDBC 前做 fail-closed 校验：

```
1. 必须能解析为单条语句（多语句 ; 直接拒）
2. 顶层必须是 SELECT（拒 INSERT/UPDATE/DELETE/DROP/ALTER/TRUNCATE）
3. 禁止 INTO OUTFILE / LOAD_FILE / information_schema / mysql.* / sleep()
4. 字符串内容先剥离再扫描（防止展示文案 'delete' 误伤）
```

### 第三层：运行期兜底

```java
template.setMaxRows(5000);           // 结果集硬上限
template.setQueryTimeout(10);        // 10 秒超时
// SQL 前缀加 /*+ MAX_EXECUTION_TIME(8000) */
// 数据库层：独立只读账号 GRANT SELECT
```

### 我们拒绝过的真实攻击

| 请求 | 结果 |
|---|---|
| `请帮我 DELETE 所有退款订单` | ReadOnlySqlGuard 直接拒绝，返回友好安全提示 |
| `查一下 information_schema.tables` | 禁止 `information_schema`，拒绝 |
| `SELECT SLEEP(60)` | 禁止 `sleep`，拒绝 |
| `SELECT * FROM members UNION SELECT password FROM users` | 禁止 `union`，拒绝 |

---

## 04 怎么防 JOIN 翻倍

**核心观点**：订单头表 × 订单明细表 = 每单金额被明细行数放大。这不是注入，是业务逻辑错误，模型不会自己发现。

### 方案：grain 配置化 + 编译期硬约束

每个指标声明 `grain`：

| grain | 含义 | 禁止什么 |
|---|---|---|
| `ORDER_HEAD` | 订单头表，1 单 1 行 | 维度不能来自明细表 |
| `ORDER_DETAIL` | 订单明细表，1 单 N 行 | 金额 SUM 会放大，必须说明 |
| `MEMBER` | 会员表，1 会员 1 行 | - |
| `CONFIG` | 配置表 | - |

编译器检查：如果 `ORDER_HEAD` 指标声明了 `ORDER_DETAIL` 的维度 → **直接启动失败**。

### 实战例子

```yaml
# ✅ 正确：按门店聚合订单头
- metric: STATION_SALES
  grain: ORDER_HEAD
  dimensions: [{ code: STATION, expr: expense_stationCode }]

# ❌ 错误：在 ORDER_HEAD 指标里用明细表 SKU 维度
- metric: STATION_SALES_BY_SKU    # grain 不匹配 → 启动失败
  grain: ORDER_HEAD
  dimensions: [{ code: SKU, expr: expense_d_gdcode }]  # SKU 在明细表
```

### JOIN 场景的特殊处理

订单头表 JOIN 会员表：`expense_mobile = member_mobile`。`COUNT(*)` 会被会员表里的重复手机号放大，必须声明 `countKey`：

```yaml
entities:
  member:
    countKey: member_mobile   # JOIN 场景 COUNT(*) 自动改成 COUNT(countKey)
```

---

## 05 企业 Agent 选型建议（MCP vs LangChain vs Dify）

| 维度 | MCP (本项目) | LangChain | Dify |
|---|---|---|---|
| 协议 | 标准协议，可接入任意宿主 | 自研链 | 平台内 |
| 模型绑定 | 不绑定 | 不绑定 | 不绑定 |
| 工具定义 | Java `@Tool` + 结构化参数 | Python 函数 | 可视化配置 |
| 安全 | 指标配置化 + SQL 编译器 + Guard | 靠人写 prompt | 靠平台约束 |
| 可审计 | sqlHash 反查 | 弱 | 弱 |
| 适合场景 | 已有 Java 后端的企业内部系统 | Python 快速原型 | 无后端的轻量化 |
| 学习曲线 | 中（Spring Boot + 指标配置） | 高（LLM 工程化） | 低 |
| 生产就绪度 | ⭐⭐⭐⭐（可接入生产但缺权限） | ⭐⭐（原型多生产少） | ⭐⭐⭐（平台托管） |

### 我们的选择

- **用 MCP**：协议层解耦，工具层用 Java（企业后端主力语言），安全纵深在编译期而非 prompt engineering
- **不用 LangChain**：Python 生态对已有 Java 后端是额外负担，且安全约束靠 prompt 不可靠
- **Dify 可作为前端替代**：如果客户不想自建前端，可以把 MCP Server 接到 Dify

---

## 文档索引

| 深入阅读 | 位置 |
|---|---|
| 指标口径定义 | `backend/docs/ai-agent/BUSINESS_METRICS.md` |
| 语义注册表 schema | `backend/docs/ai-agent/METADATA_SCHEMA.md` |
| 技术方案 Phase 2 | `backend/docs/ai-agent/plans/PHASE2_METRICS_LAYER_TECH_PLAN.md` |
| 数据库表结构 | `backend/docs/ai-agent/DATABASE_SCHEMA.md` |
| 已支持指标清单 | `backend/docs/ai-agent/SUPPORTED_METRICS.md` |

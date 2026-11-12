Let me read the ai-agent documentation files first.



Let me also check the actual metrics configuration files to see the current implementation.



Based on the documentation and configuration files I've read, here's a detailed explanation of the metrics configuration system and what each role should provide.

---

## 指标配置体系详细说明

### 一、核心设计理念：指标即配置（Metrics-as-Code）

项目采用"**指标即配置**"模式，所有业务指标通过 YAML 配置文件定义，由 `SqlCompiler` 确定性编译生成 SQL，而非每次运行时由 AI 生成或开发人员手写。

**配置文件位置：**
- [mcp-server/src/main/resources/metrics/member.yaml](file:///D:/java_project/spring-ai-mcp-rag-dev/mcp-server/src/main/resources/metrics/member.yaml) — 会员域指标
- [mcp-server/src/main/resources/metrics/order.yaml](file:///D:/java_project/spring-ai-mcp-rag-dev/mcp-server/src/main/resources/metrics/order.yaml) — 订单/门店/商品域指标
- [mcp-server/src/main/resources/metrics/column-types.yaml](file:///D:/java_project/spring-ai-mcp-rag-dev/mcp-server/src/main/resources/metrics/column-types.yaml) — 列类型与 PII 登记表

### 二、每个指标配置包含的核心字段

| 字段                        | 必填 | 说明                                                         |
| --------------------------- | ---- | ------------------------------------------------------------ |
| `metric`                    | ✅    | 全局唯一编码，UPPER_SNAKE_CASE，如 `STATION_SALES`           |
| `title`                     | ✅    | 中文指标名，进入结果与 prompt                                |
| `domain`                    | ✅    | 域归属：`member` / `order` / `delivery` / `finance`          |
| `status`                    | ✅    | `CONFIRMED`（已确认口径，可执行）/ `UNCONFIRMED`（未确认，拒绝执行） |
| `caliber`                   | ✅    | **口径正文**：说明计算逻辑、数据来源、过滤条件、时间边界等   |
| `table`                     | ✅    | 主表名，必须在 `column-types.yaml` 白名单内                  |
| `grain`                     | ✅    | 统计粒度：`ORDER_HEAD`（订单头）/ `ORDER_DETAIL`（明细）/ `MEMBER`（会员） |
| `timeColumn`                | ◐    | 时间列；缺失表示累计口径（不注入时间条件）                   |
| `baselineFilters`           | ◐    | 恒定 WHERE 片段，只允许字面量（如 `expense_state = '10'`）   |
| `dimensions[]`              | ✅    | 分组维度：`{code, expr, alias}`                              |
| `attributes[]`              | ◐    | 非聚合描述列：`{expr, alias, agg}`，生成 `MAX(x) AS y`       |
| `measures[]`                | ✅    | 度量：`{code, expr, agg, alias, unit, zeroFill}`             |
| `optionalFilters[]`         | ◐    | 可选过滤：`{code, column}`，请求传值时生成参数化条件         |
| `derived[]`                 | ◐    | Java 端派生计算：`{code, formula, scale}`，只允许四则运算    |
| `sortable[]`                | ◐    | 允许排序的度量白名单                                         |
| `limitDefault` / `limitMax` | ◐    | 默认返回条数 / 上限                                          |
| `defaultWindowMonths`       | ◐    | 缺省时间窗口（需 `timeColumn`）                              |
| `maxRangeDays`              | ◐    | 指标级时间跨度上限                                           |
| `withTotalRow`              | ◐    | 是否生成"合计"行                                             |
| `nullDimensionLabel`        | ◐    | 空维度归并展示名（如"未知门店"）                             |
| `join`                      | ◐    | 受控 LEFT JOIN 配置（路线 C）                                |

### 三、安全与质量控制机制

1. **VARCHAR 金额/数量必须走 `NUM()`**：配置里写 `expr: NUM(expense_realamt)`，编译器展开为 `CAST(NULLIF(TRIM(x),'') AS DECIMAL(20,2))`
2. **时间边界统一左闭右开**：`>= ? AND < DATE_ADD(?, INTERVAL 1 DAY)`
3. **PII 列黑名单**：`member_name`、`member_mobile`、`member_address` 等禁止输出
4. **口径状态闸门**：`status=UNCONFIRMED` 的指标一律拒绝执行
5. **租户维度已全面取消**：传入 `TENANT` 会被显式拒绝

---

## 产品经理应提供的字段信息

产品经理负责定义**业务口径**，这是唯一无法自动化的环节。每个指标需要确认以下内容：

### 一、基础定义类

| 需提供信息     | 说明                       | 示例                                           |
| -------------- | -------------------------- | ---------------------------------------------- |
| **指标名称**   | 业务可读的中文名           | 门店销售业绩、新增会员数、活跃会员数           |
| **指标编码**   | 全局唯一标识（可协助命名） | `STATION_SALES`、`MEMBER_NEW`、`ACTIVE_MEMBER` |
| **业务含义**   | 该指标回答什么业务问题     | "哪个门店卖得最多"、"最近有多少新会员"         |
| **指标域归属** | 属于哪个业务域             | 会员域 / 订单域 / 商品域 / 财务域              |

### 二、计算口径类（核心）

| 需提供信息        | 说明               | 示例                                                        |
| ----------------- | ------------------ | ----------------------------------------------------------- |
| **统计对象**      | 统计的是什么       | 订单、会员、商品、门店                                      |
| **金额字段选择**  | 用实付还是零售金额 | 取 `expense_realamt`（实付）而非 `expense_stdtotal`（零售） |
| **有效订单条件**  | 哪些状态算有效     | `expense_state='10' AND del_flag='0'`                       |
| **退单/退款处理** | 退单如何影响统计   | 退单为负数行，SUM 时自然抵消                                |
| **时间字段**      | 用哪个时间列筛选   | 消费类用 `expense_buyDate`，新增用 `member_created`         |
| **默认时间范围**  | 不传时间时查多久   | 近一年半（18 个月）                                         |
| **时间跨度上限**  | 单次最多查多久     | 商品排行 92 天（防超时）                                    |
| **统计粒度**      | 按什么维度聚合     | 按门店、按 SKU、按性别                                      |
| **派生指标**      | 是否需要计算比率   | 客单价 = 销售额 / 订单数                                    |

### 三、业务规则类

| 需提供信息       | 说明                  | 示例                                   |
| ---------------- | --------------------- | -------------------------------------- |
| **会员身份判定** | 是否区分会员/散客     | 标杆酒企所有消费均计会员消费，无散客概念 |
| **归属门店定义** | 新增会员归属哪个门店  | 注册门店 `member_store`，非分配门店    |
| **活跃定义**     | 怎样算活跃会员        | 近 3 个月内有 ≥1 次有效消费            |
| **层级字段选择** | 用哪个字段统计层级    | 六个层级字段全部统计，查询时选一个     |
| **年龄计算方式** | 用生日还是 age 字段   | 由阴历生日计算，不用 age 列（不准）    |
| **异常数据处理** | 异常 SKU 是否过滤     | 按真实商品统计，不过滤不合并           |
| **维度支持范围** | 是否支持租户/平台筛选 | 租户维度已取消，只关注门店             |

### 四、确认状态类

| 需提供信息       | 说明                                       |
| ---------------- | ------------------------------------------ |
| **口径确认状态** | 已确认（CONFIRMED）/ 待确认（UNCONFIRMED） |
| **确认日期**     | 口径确认的时间，便于追溯                   |
| **确认依据**     | 会议纪要、邮件、IM 记录等                  |

---

## CRM 开发人员应提供的字段信息

CRM 开发人员负责提供**数据源信息**和**技术约束**，确保指标配置能正确映射到数据库。

### 一、表结构信息

| 需提供信息       | 说明                   | 示例                                            |
| ---------------- | ---------------------- | ----------------------------------------------- |
| **主表名**       | 指标基于哪张表         | `expense_calendar_info`、`member_info`          |
| **字段名与类型** | 涉及的字段及其数据类型 | `expense_realamt` VARCHAR、`member_sex` TINYINT |
| **字段注释**     | 字段的业务含义         | `expense_state`：10 正常，20 已退单             |
| **主键与唯一键** | 保证数据粒度的关键字段 | `expense_orderId` 是否全局唯一                  |
| **索引信息**     | 影响查询性能的索引     | `index_buyDate`、`index_stationCode`            |
| **关联关系**     | 表间如何 JOIN          | `expense_mobile = member_mobile`                |
| **关联基数**     | JOIN 是否会乘行        | `member_mobile` 全表唯一，1:1 关联              |

### 二、数据质量信息

| 需提供信息           | 说明                        | 示例                                        |
| -------------------- | --------------------------- | ------------------------------------------- |
| **VARCHAR 数值字段** | 哪些金额/数量字段是 VARCHAR | `expense_realamt`、`expense_d_qty`          |
| **空值/脏值情况**    | 字段的填充率和异常值        | `gross_margin` 有 322 条非数字              |
| **NULL 含义**        | NULL 代表什么业务含义       | `member_tenant` 100% 为空，租户维度无意义   |
| **默认值**           | 字段的默认值                | `del_flag` 默认 '0'                         |
| **枚举值分布**       | 状态字段的实际取值          | `expense_state` 实测只有 '10'，无 '20' 数据 |
| **数据覆盖率**       | 关键字段的有效数据比例      | 阴历生日填充 0.44%，年龄分布"未知"占 99.56% |

### 三、技术约束信息

| 需提供信息            | 说明                    | 示例                                             |
| --------------------- | ----------------------- | ------------------------------------------------ |
| **PII 列清单**        | 哪些字段包含个人信息    | `member_name`、`member_mobile`、`member_address` |
| **敏感字段**          | 哪些字段禁止输出        | 生日、手机号、家庭成员信息                       |
| **允许计算的 PII 列** | 哪些 PII 列可作计算输入 | `member_yin_birthday` 可算年龄，不可输出原值     |
| **删除标记语义**      | `del_flag` 的真实含义   | '0'=正常，'1'=已删除（与注释"1是2否"不一致）     |
| **触发器影响**        | 是否有触发器影响数据    | `ff_update_member_info_view` 同步会员数据到视图  |
| **视图可用性**        | 是否应查询视图而非基表  | `member_info_view` 的结构和用途                  |

### 四、性能约束信息

| 需提供信息       | 说明                 | 示例                                         |
| ---------------- | -------------------- | -------------------------------------------- |
| **表数据量级**   | 当前和预期的数据规模 | `expense_calendar_details` 1529 万行         |
| **查询超时阈值** | 可接受的单查询耗时   | 编译产物 hint 60s → JdbcTemplate 兜底 65s（2026-11-03 由 25s/30s 上调，用户决策；生产前需按真实负载重估） |
| **索引选择性**   | 哪些索引适合做过滤   | `index_state` 选择性低，长窗口走该索引会超时 |
| **建议时间跨度** | 避免慢查询的时间范围 | 商品排行建议 ≤92 天                          |

### 五、配置登记信息

需维护 `column-types.yaml` 文件，登记：

```yaml
# 1. PII 列黑名单（禁止输出）
piiColumns:
  - member_name
  - member_mobile
  - expense_mobile
  # ...

# 2. 允许参与计算的 PII 列（不可原值输出）
piiComputeAllowedColumns:
  - member_yin_birthday  # 可算年龄
  - expense_mobile       # 可 COUNT(DISTINCT)

# 3. 表白名单（未登记的表不允许引用）
tables:
  expense_calendar_info:
    varcharNumeric:      # VARCHAR 且语义为数值的列
      - expense_realamt
      - expense_stdtotal
      - gross_margin
  # ...
```

---

## 协作流程总结

```
产品经理                    CRM 开发人员                    AI 辅助
   │                           │                           │
   ├─ 1. 提出指标需求 ─────────►│                           │
   │                           ├─ 2. 提供表结构、字段信息 ──►│
   │                           ├─ 3. 登记 PII 列、VARCHAR 列 │
   │                           │                           ├─ 4. 生成配置草稿
   │◄──────────────────────────┼─────────────────────────── │   (draftMetric)
   ├─ 5. 确认口径 ─────────────►│                           │
   │                           ├─ 6. 校验配置（加载期自动）   │
   │                           ├─ 7. 干跑编译（explainMetric）│
   │◄──────────────────────────┼─────────────────────────── │
   ├─ 8. 确认数字对账 ─────────►│                           │
   │                           ├─ 9. 写对账测试              │
   │                           ├─ 10. 改 status=CONFIRMED   │
   │◄──────────────────────────┼─────────────────────────── │
   │   11. 指标上线，可被 AI 调用                            │
```

**关键原则：**
- 产品定义"算什么"（口径），开发定义"怎么算"（字段映射）
- 口径未确认一律 `status=UNCONFIRMED`，拒绝执行
- 所有配置变更需通过加载期校验，错误在部署期暴露而非查询期
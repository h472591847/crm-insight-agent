# metadata.yaml 配置说明

> **版本**：2026-11-05
> **位置**：`mcp-server/src/main/resources/semantic/metadata.yaml`
> **加载器**：`AdhocRegistryConfiguration` → `SemanticRegistry.fromYaml()`（SnakeYAML SafeConstructor，启动期）
> **消费者**：`AdhocValidator`、`AdhocSqlCompiler`、`AdhocQueryService`、`AdhocTool`
> **校验**：加载期硬校验（任何失败 → 启动失败），详见本文末尾「加载期硬校验清单」

## 一、角色定位

metadata.yaml 是 **Ad-hoc 查询引擎唯一事实来源**：模型只能引用这里声明的实体/维度/度量名，服务端解析成真实列。**一次性构建；新增可查列只改本文件 + `metrics/column-types.yaml`，不改 Java**。

## 二、加载链路

```
ClassPathResource("semantic/metadata.yaml")
    ↓ SnakeYAML SafeConstructor (maxAliases=50, nestingDepth=20, codePointLimit=1MB)
Map<String, Object> root
    ↓ SemanticRegistry.fromYaml(root, ColumnTypeRegistry)   ← 生产入口，两参重载
SemanticRegistry (单例 Bean，immmutable)
    ↓
AdhocValidator / AdhocSqlCompiler / AdhocQueryService
```

SafeConstructor 意味着 YAML 里 **不能写任意 Java 对象**（如 `!!com.example.Pojo`），只能是标量/Map/List。

## 三、顶层 schema

```yaml
constraints:                    # 全局默认约束（选填）
  maxLimit: 1000                 # SELECT LIMIT 上限
  defaultLimit: 100              # 未指定 LIMIT 时的默认值
  maxDimensions: 5               # GROUP BY 列数上限
  maxMeasures: 10                # SELECT 聚合列数上限
  maxFilters: 10                 # WHERE 条件数上限

entities:                       # 实体白名单（必填）
  <entityName>:                  # ← 实体名（合法标识符，不进 SQL）
    ...
  <entityName2>:
    ...
```

## 四、EntityDef 完整字段表

每个 `entities.<entityName>` 是一个实体定义，必填字段无 `(选填)` 标记。

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `table` | string | ✅ | 真实表名/视图名，必须在 `column-types.yaml` 白名单内 |
| `alias` | string | ✅ | SQL 里的表别名，**全局唯一**（跨实体重名 → 启动失败） |
| `description` | string | | 纯文档描述，不进 SQL |
| `countKey` | string | | JOIN 场景 `COUNT(*)` 会被 NULL 扩展行放大，此时必须声明非空主键列（如 `member_mobile`），编译器自动改成 `COUNT(countKey)` |
| `baselineFilters` | string[] | ✅ | 实体固定 WHERE 守卫（如 `del_flag='0'`），每次查询自动追加 |
| `dimensions` | list | | GROUP BY 维度，见下方 DimensionDef |
| `measures` | list | ✅ | SELECT 聚合度量，见下方 MeasureDef |
| `timeColumns` | map | | 时间过滤列：键是语义名（如 `CREATED`），值是真实列名（如 `member_created`） |
| `conditionPresets` | list | | 可复用条件片段，模型引用时只传 `id` |
| `filterableColumns` | string[] | ✅ | 允许作为 WHERE 条件的列（白名单），值一律走参数化不进 SQL |
| `indexedColumns` | string[] | | 上表索引的过滤列（事实标注，不参与裁决）；**必须是 filterableColumns 的子集** |
| `filterGuidance` | string[] | | 给模型的过滤策略提示（纯文本，永不进 SQL） |
| `relationships` | list | | JOIN 关系声明，见下方 RelationshipDef |
| `queryGuard` | map | | 大表守卫（如明细表强制带时间窗），见下方 QueryGuard |

### DimensionDef

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | ✅ | 维度名（模型引用用） |
| `column` | string | 与 expr 二选一 | 真实列名 |
| `expr` | string | 与 column 二选一 | 任意 SQL 表达式（如 `CASE WHEN ... END`） |
| `agg` | string | | 聚合函数（如 `MAX`）——有 agg 的维度是**描述列**，只用于 SELECT 输出不进 GROUP BY |
| `nullLabel` | string | | NULL 时的显示值 |
| `requires` | string | | 跨实体依赖（如 ORDER_GENDER 需要 member 实体存在） |

### MeasureDef

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | ✅ | 度量名 |
| `column` | string | 与 expr 二选一 | 真实列名（**VARCHAR 数值列编译器自动 `NUM(column)`**） |
| `expr` | string | 与 column 二选一 | 任意表达式（`*` 表示 COUNT(*)） |
| `agg` | string | ✅ | 聚合函数：`COUNT` / `SUM` / `COUNT_DISTINCT` / `AVG` / `MIN` / `MAX` |

### ConditionPreset

```yaml
conditionPresets:
  - { id: IS_VIP, column: track, op: "=",  value: "1" }
  - { id: CANCELLED, column: track_cancel_time, op: "IS NOT NULL" }   # IS NULL/IS NOT NULL 无 value
  # 条件模板只能绑定本实体列；示例仅示意结构，HAS_EXPENSE 已于 2026-11-10 移除
```

### RelationshipDef（粒度守卫事实来源）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `target` | string | ✅ | 目标实体名（必须在 entities 里声明过） |
| `type` | string | ✅ | JOIN 类型：`LEFT JOIN` / `INNER JOIN` |
| `cardinality` | string | | `to_one` 或 `to_many`（默认 `to_many`，保守策略） |
| `on` | string | ✅ | ON 条件（如 `mv.member_mobile = e.expense_mobile`） |
| `joinFilters` | string[] | | JOIN 时自动追加的子表守卫（如 `e.expense_state='10' AND e.del_flag='0'`） |

**cardinality 决定了跨实体查询的安全边界**：
- `to_many`：一个 A 对应多个 B → **A 粒度度量会被 B 行数放大**，服务端会拒绝跨粒度选取
- `to_one`：一个 A 对应一个 B → JOIN 不放大，A/B 度量可自由组合

### QueryGuard（实体级守卫）

仅大表需要（如明细表 1500 万+ 行）：

```yaml
queryGuard:
  requiredAnyOf: [ timeRange, expense_d_mobile, expense_d_orderId ]   # 至少满足其一
  constraints:
    maxTimeRangeDays: 92                                              # 时间窗上限
    defaultLimit: 500                                                 # 覆盖全局 defaultLimit
```

- `"timeRange"` 是关键字不是列名
- `constraints` 里的键覆盖全局 `constraints` 对应值

## 五、加载期硬校验清单

以下任何一项**失败 → 启动失败**（坏配置不带病上线）：

| 校验 | 位置 | 报错消息 |
|---|---|---|
| 缺少 `entities` 段 | `fromYaml` | `metadata.yaml 缺少 entities 段` |
| 实体别名跨实体重名 | `fromYaml` | `实体别名跨实体重名：X（实体 A 与 B 共用别名）` |
| 关系目标不存在 | `fromYaml` | `实体 X 声明了未知关系目标：Y` |
| 实体表不在 `column-types.yaml` 白名单 | `parseEntity` | `实体 X 的表 Y 不在列类型登记表白名单内` |
| 表名/别名/列名不是合法标识符 | `checkIdentifier` | `不是合法标识符：仅允许字母、数字、下划线，且不能以数字开头` |
| 维度缺 column/expr | `parseEntity` | `实体 X 维度 Y 缺 column/expr` |
| 度量缺 agg | `parseEntity` | `实体 X 度量 Y 缺少必填字段 agg` |
| 实体含 COUNT(*) 但没声明 countKey | `parseEntity` | `实体 X 含 COUNT(*) 度量，必须声明 countKey` |
| indexedColumns 引用了非 filterableColumns | `parseEntity` | `实体 X 的 indexedColumns 引用了未声明为可过滤的列：Y` |
| relationships 缺 type/on | `parseEntity` | `实体 X 的关系 Y 缺少 type/on 声明` |
| cardinality 不是 to_one/to_many | `parseEntity` | `cardinality 只能是 to_one/to_many` |

## 六、维护 Checklist

### 新增实体

1. 在 `entities:` 下加新节点，确保 **table 在 column-types.yaml 里登记过**
2. 声明 `countKey`（如果 measures 里有 COUNT(*)）
3. `baselineFilters` 必须包含所有业务删除标记和状态过滤（如 `del_flag='0' AND state='10'`）
4. `dimensions` 里的描述列（有 agg 的）标注 `agg: MAX`
5. `filterableColumns` 白名单严格控制——**用户能引用的过滤列必须在这里声明，否则服务端会拒绝**
6. `indexedColumns` 必须是 filterableColumns 的子集
7. `relationships` 的 target 必须是已声明实体
8. 大表加 `queryGuard`（requiredAnyOf + maxTimeRangeDays）
9. 启动应用验证加载成功，跑 `mvn -pl mcp-server test` 确认 219/219

### 新增维度/度量

1. 在已有实体的 dimensions/measures 列表末尾加新条目
2. 维度必须有 column 或 expr
3. 度量必须有 agg（大小写不限，加载时统一大写）
4. 度量的 column 如果是 VARCHAR 数值（如金额），编译器会自动 NUM()，不用写 expr
5. 如需过滤此列，同步加到 filterableColumns（必要时 indexedColumns）
6. 如有跨实体依赖，dimension 加 `requires: <targetEntity>`

### 新增关系

1. 加在源实体的 relationships 列表里
2. cardinality 保守：不确定就 `to_many`（宁可拒绝跨粒度，也不静默放大）
3. joinFilters 必须包含目标表的 baselineFilters（如 `del_flag='0' AND state='10'`）
4. 启动应用验证别名不重名、关系目标存在

## 七、踩坑快查

| 症状 | 检查点 |
|---|---|
| 启动报 `缺少必填字段 table` | YAML 缩进是不是被编辑工具乱改了 |
| 启动报 `COUNT(*) 必须声明 countKey` | measures 里的 `{ name: XXX, expr: "*", agg: COUNT }` 必须有 countKey |
| 启动报 `indexedColumns 引用了未声明为可过滤的列` | filterableColumns 没加这一列 |
| 查询结果行数翻倍/放大 | relationships 里 cardinality 声明错了（应是 to_one 但写成 to_many） |
| 模型说"未知列"或"不允许过滤" | 列名没加进 dimensions / measures / filterableColumns |
| JOIN 查询 COUNT 结果偏高 | countKey 没声明（LEFT JOIN 扩展行把 NULL 计成 1） |
| 明细表全表扫（超时/OOM） | queryGuard 没加 requiredAnyOf 或 maxTimeRangeDays 太大 |

## 交叉引用

- **模型获取 Schema 的完整链路**（端到端数据流 + 三种文档分工 + 安全闸门 + metadata.yaml 与 column-types.yaml 协作）：见 `plans/2026-11-03-adhoc-semantic-query-engine.md` 末尾「模型获取 Schema 的完整链路」章节。
- **metrics/*.yaml 固化指标体系**：见 `METRICS_CONFIGURATION.md`。
- **业务口径（有效订单、del_flag、门店编码优先等）**：见 `BUSINESS_METRICS.md`。

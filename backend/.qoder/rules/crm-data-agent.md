# CRM 数据分析 Agent 专项规则

**规约层级 · Level 3（业务铁律）** —— 优先级最高。

本文件定义的是 CRM 业务数据查询**不可变更的口径与边界**，不属于通用编码规范。通用编码规范见 `backend-dev-rules.md`，未覆盖场景默认遵守《阿里巴巴 Java 开发手册（嵩山版）v1.8.1》。

## 一、项目边界

本规则适用于 `SpringAi-MCP-RAG-Dev` 中新增的 CRM 数据分析 Agent。

- 这是一个独立 AI 应用。
- CRM MySQL 是外部数据源。
- 当前优先使用 CRM 测试数据库。
- 不在 CRM 源码项目中开发。
- 未经授权不得修改 CRM 源码、数据库结构、触发器或业务数据。

## 二、技术栈锁定（2026-11-06 版本）

**这些版本已通过编译与既有全量测试（mcp-server 219/219 + ChatSessionKeyUtilsTest 7/7 + compile exit 0），不要随意升级。**

| 组件 | 版本 | 约束 |
|---|---|---|
| 模块结构 | **3 个模块：`mcp-common`（纯资源）+ `mcp-client` + `mcp-server`** | `mcp-common` 只含 `logback-spring.xml`，无任何依赖、不做 repackage；两个业务模块都依赖它 |
| Spring Boot | **3.5.14** | 不要升 Boot 4.x（Spring Framework 6→7 大版本跳跃，双重 breaking）；根 pom **不再继承 `spring-boot-starter-parent`**，改为在 `dependencyManagement` import `spring-boot-dependencies:3.5.14` |
| Spring AI BOM | **1.1.2** | **只在根 pom `dependencyManagement` import 一次**，子模块不再各自声明；ChatMemory.DEFAULT_CONVERSATION_ID 在 1.1.6+ 已移除，后续代码硬编码 `"default"` |
| **模型 Starter** | `spring-ai-starter-model-openai` | **2026-11-06 起改回 OpenAI 兼容端点**（`/compatible-mode/v1`），不再使用 `spring-ai-alibaba-starter-dashscope`，SAA BOM 1.1.2.2 已一并移除 |
| Lombok | **1.18.46** | 从 1.18.24 升级，修复 JDK 21 下 `NoSuchFieldError: JCImport.qualid`；依赖与 `annotationProcessorPaths` 共用同一版本 |
| Redisson | **3.45.1** | **只能用纯 `redisson` 核心库，绝对不能引 `redisson-spring-boot-starter`**（starter 4.x 对应 Boot 4，会覆盖原生 RedisConnectionFactory 导致 RedisVectorStore 装配失败） |
| MyBatis Plus | **3.5.14** | 根 pom 统一管理 |
| MySQL Connector | **9.1.0** | 根 pom 统一管理 |
| Java | **21** | corretto-21.0.13；编译参数 `-parameters`；进程级设置，不改系统环境变量 |
| mcp-server 不引模型 starter | — | 模型只在 mcp-client 里依赖；mcp-server 只提供 MCP Tools 和 Adhoc SQL 执行 |

### 版本托管策略（2026-11-06 起，已实测）

```
根 pom dependencyManagement:
  - spring-boot-dependencies:3.5.14              ← import scope
  - spring-ai-bom:1.1.2                          ← import scope
  - lombok / mybatis-plus / mybatis-plus-jsqlparser / mysql-connector-j /
    flexmark-all / spring-dotenv / commons-lang3 / spring-boot-starter-test /
    okhttp / hutool-all / dotenv-java / redisson ← 12 个坐标统一托管

根 pom pluginManagement:
  - spring-boot-maven-plugin:${spring-boot.version}

子模块 dependencies:
  - 一律不写 <version>
```

### 模型接入（OpenAI 兼容端点，2026-11-06 起）

`spring.ai.openai.*` 配置，环境变量 `OPENAI_API_KEY` / `OPENAI_BASE_URL` / `OPENAI_CHAT_MODEL` / `OPENAI_EMBEDDING_MODEL`。

| 约束 | 说明 |
|---|---|
| `base-url` 必须带 `/compatible-mode/v1` 后缀 | OpenAI starter 会自行拼 `/chat/completions`，重复或缺失后缀都会 404 |
| **不要配 `multi-model`** | 兼容端点无此概念；残留 `spring.ai.dashscope.chat.options.multi-model: true` 会把纯文本模型路由到 DashScope 原生多模态端点，表现为 429 / `url error` |
| MaaS endpoint 只能走兼容端点 | 百炼 MaaS 推理 endpoint 的 API Key 仅支持 `/compatible-mode/v1`，调原生 API 会 `url error` |
| 模型名 | 直接写兼容端点支持的模型名或 endpoint ID（如长期使用的 `qwen3.8-flash`），不再受 SAA 枚举限制 |

## 三、Agent 职责

Agent 负责理解用户问题、选择合适工具、组织参数并根据真实工具结果生成回答。

Agent 不负责自行决定访问权限，也不负责凭语言推理生成统计数字。权限校验、参数校验、数据访问、业务规则和确定性计算必须在 Java 服务端完成。

## 四、业务口径铁律（已实测，不准改）

### 会员表：必须走 view，不能直接查原表

| 规则 | 证据 |
|---|---|
| 会员统计一律 `FROM member_info_view mv` | SemanticRegistry.entity("member").table() = "member_info_view"；Adhoc 所有 SQL 全走 view；GuessTool 曾误查 member_info 导致会员数对不上（2026-11-04 踩坑） |
| 有效会员 `mv.del_flag = '0'` | MemberOverviewResult.java:15 注释明确写"有效会员总数（del_flag='0'）"；**不是 0/1/2 多状态**，是字符串类型 |
| **del_flag 不能瞎写数字** | 2026-11-04 GuessTool 第一次写 SQL 瞎写 `del_flag = 2`，一条数据都查不到。铁律：**写前先 grep 项目里其他 mapper 的实际值** |

### 订单表：编码优先，名称禁过滤

| 规则 | 证据 |
|---|---|
| 门店条件用 `expense_stationCode` / `expense_d_stationCode` 等值 | 有索引，性能好 |
| **禁止**对 `member_allot_stationName` 做任何 WHERE 过滤 | 无索引，全表扫描；名称→编码解析必须通过 GuessTool 或 SemanticRegistry 的 NAME_TO_CODE 指引 |
| 有效订单 `expense_state = '10'` AND `del_flag = '0'` | 订单/明细表一对多，expense_state 是订单主状态 |

### 模糊查询：前缀优先 + 包含兜底

GuessTool / 门店模糊查询统一策略：
1. **先前缀匹配**（`member_allot_stationName LIKE CONCAT(keyword, '%')`）—— 走索引友好路径
2. **0 条结果时再用包含匹配兜底**（`%keyword%`）—— 覆盖"哈市长江路店"里"长江路"在中间的情况
3. GROUP BY stationCode + stationName 去重，按会员数倒序（大门店排前）
4. 防全表扫：keyword 最短 2 字 + LIMIT 20 兜底

### MCP Tool 返回 JSON 结构约定

Tool 返回的 JSON 必须包含 `actionHint` 决策字段，模型直接读它决定下一步：

```json
{
  "count": 2,
  "candidates": [{"code": "01010601", "label": "哈市长江路店", "meta": "1248 会员"}],
  "matchedBy": "prefix",
  "actionHint": "unique_match | multiple_matches | no_match | invalid_input"
}
```

- `unique_match` → 直接用该编码查询
- `multiple_matches` → 必须输出 `<clarify>` 标记块，让前端弹出交互卡片
- `no_match` → 请用户提供更准确的名称/编码，不得猜
- `invalid_input` → 如实告知"关键词太短或手机号格式不对"

## 五、@Tool 注解编写规范（已实测）

Spring AI Tool Calling 要求 `@Tool` 方法必须返回 `String`（JSON），不能返回 void 或对象——因为模型直接解析返回值作为工具执行结果。

### 注解写法

```java
// ✅ 正确：name 必须有意义；description 必须覆盖以下 4 点
@Tool(name = "guessStation",
      description = "根据用户给出的门店名称关键词（如'长江路'、'中央'、'建设'）模糊匹配门店。"
              + "关键词通常是街道名、路名、商圈名、门店别名或部分名称，不是完整门店编码。"
              + "返回候选门店列表，每条含 stationCode/stationName/memberCount。"
              + "恰好 1 个候选可直接用；多个候选必须停下来追问用户选哪一家；0 个如实告知没找到。"
)
public String guessStation(String keyword) { ... }

// ❌ 禁止
@Tool  // 没有 name 和 description
public Map<String, Object> guessStation(String keyword) { ... }  // 返回 void 或对象
```

**description 必须写清楚**：
1. **触发场景**：什么情况下模型应该调用这个工具
2. **参数格式**：keyword 是街道名/路名/商圈名，不是编码
3. **返回值含义**：candidates 每条包含哪些字段
4. **actionHint 四种语义**：unique_match 直接用 / multiple_matches 追问 / no_match 告知没找到 / invalid_input 提示参数问题

### Tool 内部逻辑拆分

```java
// ✅ 正确：@Tool 方法只做参数校验 → 调 Service/Mapper → 组装 JSON 返回
@Tool(name = "guessStation", description = "...")
public String guessStation(String keyword) {
    String trimmed = StringUtils.trimToNull(keyword);
    if (trimmed == null || trimmed.length() < MIN_KEYWORD_LEN) {
        return buildEmptyResult("门店名称关键词太短（至少 " + MIN_KEYWORD_LEN + " 字）");
    }
    List<Map<String, Object>> candidates = memberInfoMapper.guessStationByPrefix(trimmed);
    // ... 业务逻辑 ...
    return toJson(result);
}

private String buildStationResult(...) { ... }  // 结果组装 private 方法
private String toJson(...) { ... }               // 序列化 private 方法
```

**禁止**：在 @Tool 方法里直接写大量 SQL、直接 new ObjectMapper、直接 log 不返回错误原因。

### Tool 返回 JSON 结构约定（完整模板）

```json
{
  "count": 2,
  "keyword": "长江路",
  "matchedBy": "prefix",
  "candidates": [
    {"stationCode": "01010601", "stationName": "哈市长江路店", "memberCount": 1248}
  ],
  "actionHint": "unique_match"
}
```

| actionHint | 模型行为 | 前端联动 |
|---|---|---|
| `unique_match` | 直接用该编码发起正式查询 | 无交互 |
| `multiple_matches` | 停止查询，输出 `<clarify>{...}</clarify>` 标记块 | 弹出追问卡片 |
| `no_match` | 如实告知"没找到匹配的门店/会员" | 无交互 |
| `invalid_input` | 告知用户需要调整参数（太短、格式错等） | 无交互 |

## 六、SQL 编写规范（已实测）

### 必须用 Java 文本块

```java
// ✅ 正确：Java 15+ 原生语法，缩进对齐，可读性好
@Select("""
        SELECT mv.member_allot_store AS stationCode,
               mv.member_allot_stationName AS stationName,
               COUNT(*)                 AS memberCount
        FROM member_info_view mv
        WHERE mv.del_flag = '0'
          AND mv.member_allot_store IS NOT NULL
          AND mv.member_allot_stationName IS NOT NULL
          AND mv.member_allot_stationName LIKE CONCAT(#{keyword}, '%')
        GROUP BY mv.member_allot_store, mv.member_allot_stationName
        ORDER BY memberCount DESC
        LIMIT 20
        """)
List<Map<String, Object>> guessStationByPrefix(@Param("keyword") String keyword);

// ❌ 禁止
@Select("SELECT mv.member_allot_store AS stationCode, ... " +
        "FROM member_info_view mv WHERE mv.del_flag = '0' AND ...")
List<Map<String, Object>> guessStationByPrefix(String keyword);
```

### 字段别名 AS 规范

- 数据库字段是 snake_case，SQL 里必须用 `AS camelCase` 别名映射。
- **不要在 Java 里做 snake_case → camelCase 转换**（麻烦且容易遗漏）。
- GROUP BY 里必须包含所有 SELECT 里的非聚合字段（MySQL ONLY_FULL_GROUP_BY 模式要求）。

### 防脏数据 + 防全表扫

```java
// ✅ 正确：可选字段 IS NOT NULL 过滤 + LIMIT 兜底
WHERE mv.del_flag = '0'
  AND mv.member_allot_store IS NOT NULL
  AND mv.member_allot_stationName IS NOT NULL
  AND mv.member_allot_stationName LIKE CONCAT(#{keyword}, '%')
...
LIMIT 20

// ❌ 禁止
WHERE mv.del_flag = '0' AND mv.member_allot_stationName LIKE #{keyword}  // 无 IS NOT NULL，无 LIMIT
```

### 参数校验必须在 Java 层

- MyBatis `@Param` 标注每个参数。
- Tool 方法里先校验参数最小长度/格式（如手机号至少 3 位纯数字），再调用 Mapper。
- 校验失败返回友好 JSON，不要抛异常。

### Mapper 继承 BaseMapper + 手写 @Select

```java
// ✅ 正确：继承 BaseMapper 用 MyBatis Plus 自动 CRUD + 自定义复杂查询方法
public interface MemberInfoMapper extends BaseMapper<MemberInfo> {
    @Select("""...""")
    List<Map<String, Object>> guessStationByPrefix(@Param("keyword") String keyword);
}
```

## 七、SQL 安全

- 不信任模型生成的参数，所有参数由服务端校验。
- 不把用户自然语言直接拼接进 SQL。
- 不让模型生成的 SQL 未经验证就执行。
- 优先使用经过审核的 SQL 模板、Mapper 或受控查询服务。
- 对时间跨度、分页、返回行数和查询耗时设置限制。
- 用户不能通过参数覆盖服务端确定的数据权限。
- 不得在没有授权的情况下调用写操作。
- 数据库连接凭证从安全配置或环境变量读取。

## 八、CRM 数据质量

- 对 VARCHAR 金额和数量字段先验证格式，再进行数值转换和聚合。
- 明确订单表与明细表的一对多关系。
- 避免将订单级金额在明细联查后重复累计。
- 不要假定某个状态值就是所有场景的有效订单条件。
- **不要假定会员删除标记的含义 —— 实际值是字符串 '0' 表示有效**。
- 不要把"近一年半"字段当作自然年度指标。
- 业务规则不明确时，先 grep 项目里其他 SQL 的实际写法，再提问，**不要猜测**。

## 九、数据权限和个人信息

- 权限校验由服务端执行。
- 只查询完成任务所需的字段。
- 默认优先输出汇总结果。
- 不向模型传递不必要的姓名、手机号、地址、生日或家庭信息。
- GuessTool 返回的会员只给手机号 + 姓名 + 门店，**不带其他敏感字段**。
- 日志中不得写入密钥、密码或不必要的个人信息。
- 不得通过提示注入绕过工具权限或查询边界。

## 十、结果表达

- 回答必须以工具真实返回的数据为依据。
- 必要时说明统计周期、范围和指标口径。
- 查询失败、数据不足或口径未确认时，明确告知用户。
- 不编造统计值、排名、趋势或计算过程。
- 不将相关性直接解释为因果关系。
- 对异常值和数据限制进行必要说明。

## 十一、系统 Prompt 第 8 条规范

门店/会员模糊查询 → 追问闭环的 system prompt 必须明确：

```
multiple_matches 时的输出格式（严格遵守，模型看到 <clarify> 会弹出交互卡片）：
先写一句自然语言引导（"为您找到 N 家匹配的门店，请选择要查的哪一家："），
再紧跟 <clarify> 块（必须单独一行，前后各一行）：
<clarify>{"type":"station","candidates":[...]}</clarify>
字段说明：type=station 或 member；candidates[].code 是用户回复的编码。
前端会自动渲染成交互卡片，用户点击选项后自动把 code 发回来，
不要再用 markdown 表格或纯文本列表，不要省略标记。
```

## 十二、测试要求

至少测试：
1. 正常业务问题。
2. 模糊意图与追问。
3. 缺失参数和非法日期。
4. 超出允许范围的查询。
5. 无权限访问。
6. 空结果。
7. 数据库连接或 SQL 异常。
8. 模型输出不符合工具要求。
9. 工具调用失败。
10. 查询结果与标准答案不一致。
11. VARCHAR 金额或数量存在异常值。
12. 订单表与明细表联查是否造成重复统计。

测试优先在测试库和脱敏数据上进行。

## 十三、交付要求

完成工具或 Agent 功能后，记录：
- 支持的问题类型。
- 调用的工具及参数。
- 数据来源和统计口径。
- 权限与安全控制。
- 实际执行的测试及结果。
- 已知限制和待确认事项。

## 十四、配置与凭证管理

| 项 | 规则 |
|---|---|
| `.env` / 密钥 | **绝对不能提交到 git**；`.gitignore` 必须覆盖；写 `.env.example` 保留键名但清空值 |
| **模型接入（OpenAI 兼容）** | `spring.ai.openai.base-url` 必须带 `/compatible-mode/v1`；**不要配 `multi-model`**；凭证只从 `OPENAI_*` 环境变量读取，禁止写入 yml |
| MaaS endpoint 限制 | 专属推理 endpoint 的 API Key 只能调 `/compatible-mode/v1/chat/completions`，调 DashScope 原生 API 会 `url error` |
| Embedding 维度 | `spring.ai.openai.embedding.options.dimensions`（默认 1024）决定 Redis 向量索引结构，变更后必须重建索引 |
| **会话归属（2026-11-06）** | 所有 `/api/session/*` 接口必须带 `userId`，服务端按 `chat:session:owner:{sessionId}` 校验：缺失 400、他人 403、无归属 404；写入路径 `doChat` 同样校验 |
| Redis ChatMemory TTL | `RedisChatMemoryRepository` 默认 7 天（`app.chat.memory.redis.ttl-days:7` 可配）；LIST 存，Jackson 序列化 |
| Redis 连接 | `spring.data.redis.*` 原生配置（Lettuce）；Redisson 手动复用，**绝对不要覆盖 RedisConnectionFactory** |
| Redis timeout | 必须配大（`timeout: 600000` = 10 分钟）—— Spring AI RedisVectorStore 初始化 `FT.CREATE` 和向量批量写入比较慢，默认 2s 会超时 |
| `redis.keys()` 边界 | 会话列表已改走用户索引 SET（`chat:user:sessions:{userId}`）；`findConversationIds()` 已换成 `SCAN`（count 500）。生产环境仍禁止使用 `KEYS` |

## 十五、Controller 返回值规范（已实测）

所有会话/API Controller 统一返回 `ResponseEntity<Map<String, Object>>`：

```java
// ✅ 正确：ResponseEntity + LinkedHashMap（保字段顺序）
@GetMapping("/list")
public ResponseEntity<Map<String, Object>> listSessions(@RequestParam String userId) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("total", sessions.size());
    result.put("sessions", sessions);
    return ResponseEntity.ok(result);
}

// ❌ 禁止
public List<SessionVO> listSessions() { ... }  // 直接返回对象
public Map<String, Object> listSessions() { ... }  // 无 ResponseEntity 包装
```

**为什么用 Map 不用 DTO**：SessionController / GuessTool 返回结构每次都不完全一样（Session 返回 summary/messageCount，Guess 返回 actionHint/matchedBy），写 DTO 维护成本高。用 `LinkedHashMap` 保证 JSON 字段按 put 顺序输出。

## 十六、ChatMemory 已知限制

| 限制 | 现状 | 影响 |
|---|---|---|
| ToolResponseMessage 暂不完整恢复 | `RedisChatMemoryRepository.deserializeMessage()` 里跳过了 TOOL 类型 | 多轮工具回填场景下，之前工具返回结果丢失（但当前单轮 Tool → 回复的流程不受影响） |
| metadata/media 字段不恢复 | `deserializeMessage()` 只取 text，metadata / media 字段被丢弃 | 带图片附件或富媒体消息存入 Redis 后，下次读取时只剩纯文本 |
| keys() 而非 SCAN | `findConversationIds()` 已改为 `redisTemplate.scan()`（count 500） | 已解决；会话列表另走用户索引 SET |

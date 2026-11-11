# SpringAi-MCP-RAG-Dev 后端开发规约

**2026-11-05 版本 —— 基于《阿里巴巴 Java 开发手册（嵩山版）v1.8.1》+ 本项目 P1 实际落地。**

## 规约层级

```
┌─────────────────────────────────────────────────┐
│  Level 1 · 阿里嵩山版 v1.8.1（上位规范）          │
│  本规约未覆盖的场景，默认遵守阿里手册              │
│  https://github.com/alibaba/p3c                  │
├─────────────────────────────────────────────────┤
│  Level 2 · backend-dev-rules.md（项目特化）      │
│  凡是本章写了具体条款的，优先级高于阿里手册        │
│  包含：依赖注入、工具类、SQL 写法、Redis 配置、   │
│  Spring AI 特有约束、MCP Tool 编写、Controller    │
│  返回值、版本锁定、踩坑矩阵                       │
├─────────────────────────────────────────────────┤
│  Level 3 · crm-data-agent.md（业务口径铁律）     │
│  会员表/订单表的字段口径、del_flag 值、门店查询   │
│  策略、MCP Tool 返回 JSON 结构 —— 业务不可变更   │
└─────────────────────────────────────────────────┘
```

**优先级**：Level 3（业务铁律）> Level 2（本文件）> Level 1（阿里嵩山版）。

**归类说明**：

| 本文件条款 | 归类 | 与阿里手册的关系 |
|---|---|---|
| 依赖注入：构造器注入，禁止字段注入 | 阿里原封不动 | 嵩山版 1.8 节强制规约 |
| 常量集中 + 私有构造器抛异常 | 阿里原封不动 | 嵩山版 6.2 节强制规约 |
| 日志 `{}` 占位符 | 阿里原封不动 | 嵩山版 2.6 节强制规约 |
| 工具类模板、命名规范、import 分组、Javadoc 模板 | 阿里原封不动 | 嵩山版推荐规约 |
| Controller 返回值 `ResponseEntity<Map>` + LinkedHashMap | **项目特化** | 阿里没规定；因 Tool 返回结构不固定、DTO 维护成本高，统一用 Map |
| SQL 必须用 Java 文本块 `"""` + AS camelCase 别名 | **项目特化** | 阿里只说 SQL 用参数化查询，没规定 Java 侧写法 |
| MCP Tool 必须返回 String(JSON) + actionHint 四值 | **项目特化** | Spring AI Tool Calling 框架约束，阿里手册无 |
| Redis timeout 必须 10 分钟 | **项目特化** | 阿里没规定；因 Spring AI RedisVectorStore 初始化 `FT.CREATE` 慢 |
| Redisson 只能用纯核心库 + RedissonConfig 手动初始化 | **项目特化** | 阿里没规定；Redisson starter 会覆盖 RedisConnectionFactory |
| ChatMemory 反序列化双重路由（@class 优先 + messageType 兜底） | **项目特化** | Spring AI 1.x 不可变 Message POJO 限制 |
| Redis ChatMemory 已知限制（keys() / metadata 丢弃） | **项目特化** | 实测记录，不是规范 |
| 版本锁定（Boot 3.5.14 / Spring AI BOM 1.1.2 / Spring AI Alibaba BOM 1.1.2.2 / MyBatis Plus 3.5.14） | **项目特化** | 踩坑实测结果，阿里手册不管具体版本号；SAA 是对齐 Spring AI 1.1.2 编译基线 |

## 一、强制规约（违反即改）

### 1. 依赖注入：构造器注入，禁止字段注入

```java
// ✅ 正确（Spring 4.3+ 单构造器自动注入）
@Service
public class ChatServiceImpl {
    private final ChatClient chatClient;      // final！
    private final ToolCallbackProvider tools;  // final！

    public ChatServiceImpl(ChatClient.Builder chatClientBuilder,
                           ToolCallbackProvider tools,
                           ChatMemory chatMemory) {
        this.chatClient = chatClientBuilder.defaultToolCallbacks(tools).build();
        this.tools = tools;
    }
}

// ❌ 禁止
@Resource private ChatService chatService;      // 字段注入
@Autowired private RedissonClient redisson;     // 字段注入
```

**已清零**：mcp-client 3 Controller + ChatServiceImpl + SessionController + EmailTool 全部整改。

### 2. 工具类：私有构造器 + final + 无状态

```java
// ✅ 正确：私有构造器里抛 UnsupportedOperationException（防反射实例化）
public final class RedisLockUtils {
    private RedisLockUtils() {
        throw new UnsupportedOperationException("RedisLockUtils cannot be instantiated");
    }
    public static void execute(...) { ... }
}

// ❌ 禁止
@Component  // 工具类不要加 @Component，除非需要 Spring 注入（如 RedisHelper 注入 StringRedisTemplate）
public class RedisLockUtils { ... }
```

### 3. 常量集中：一个 Constants 文件一个领域

```java
// ✅ 正确（ChatConstants 实际写法）
public final class ChatConstants {
    private ChatConstants() {
        throw new UnsupportedOperationException("ChatConstants cannot be instantiated");
    }

    // ==================== Redis Key ====================
    /** Redis ChatMemory LIST 的 key 前缀（完整 key: chat:memory:{conversationId}） */
    public static final String REDIS_KEY_CHAT_MEMORY_PREFIX = "chat:memory:";
    /** Redisson 分布式锁的 key 前缀（完整 key: chat:lock:{conversationId}） */
    public static final String REDIS_KEY_LOCK_PREFIX = "chat:lock:";

    // ==================== 对话记忆 ====================
    /** 消息窗口大小（每个会话保留最近 N 条消息） */
    public static final int MEMORY_WINDOW_SIZE = 20;
    /** ChatMemory 会话摘要最大截断长度 */
    public static final int SUMMARY_MAX_LEN = 30;
    /** ChatMemory 在 Redis 中的默认过期时间（天） */
    public static final long MEMORY_DEFAULT_TTL_DAYS = 7L;

    // ==================== 会话 API ====================
    /** 历史消息拉取默认 limit */
    public static final int DEFAULT_HISTORY_LIMIT = 50;
}

// ❌ 禁止
// 1. 在多个类里重复定义 private static final String KEY_PREFIX = ...
// 2. 常量不分领域堆在一起，上千行
// 3. 用接口（public static final 是冗余的）
```

**命名规范**：
- Redis key 前缀统一 `chat:` 开头，完整 key 格式在 Javadoc 里标注
- 全大写下划线，见名知意
- 用注释分隔线 `// ==================== xxx ====================` 分领域

**已集中**：ChatConstants 8 个常量（替换了 SessionController / ChatServiceImpl / ChatSessionKeyUtils / RedisChatMemoryRepository 各自散落的魔法值）。

### 4. Controller 返回值：ResponseEntity + LinkedHashMap

```java
// ✅ 正确：统一返回 ResponseEntity<Map<String, Object>>
@GetMapping("/list")
public ResponseEntity<Map<String, Object>> listSessions() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("total", sessions.size());
    result.put("sessions", sessions);
    return ResponseEntity.ok(result);
}

// ❌ 禁止
public List<SessionVO> listSessions() { ... }       // 直接返回对象
public Map<String, Object> listSessions() { ... }   // 无 ResponseEntity 包装
```

**为什么用 Map 不用 DTO**：Tool 返回结构每次都不完全一样（GuessTool 有 actionHint/matchedBy，Session 有 summary/messageCount），写 DTO 维护成本高。`LinkedHashMap` 保证 JSON 字段按 put 顺序输出（前端依赖字段顺序）。

### 5. Redis timeout：必须配大

```yaml
# ✅ 正确：mcp-client/application.yml
spring:
  data:
    redis:
      timeout: 600000  # 10 分钟
```

Spring AI RedisVectorStore 初始化 `FT.CREATE`（向量索引创建）和向量批量写入比较慢，默认 2s 会超时。**必须配 10 分钟以上**。

### 6. Redisson destroyMethod：必须写 shutdown

```java
// ✅ 正确：RedissonConfig 实际写法
@Bean(destroyMethod = "shutdown")
public RedissonClient redissonClient() { ... }

// ❌ 禁止
@Bean  // 不写 destroyMethod → Spring 关闭时 Redisson 连接不释放，进程挂起
public RedissonClient redissonClient() { ... }
```

### 7. 日志：`{}` 占位符，禁止字符串拼接

```java
// ✅ 正确
log.info("调用 MCP 工具 guessStation, keyword={}, count={}, matchedBy={}", keyword, count, matchedBy);

// ❌ 禁止
log.info("调用 MCP 工具 guessStation, keyword=" + keyword + ", count=" + count);
```

### 8. UTF-8 BOM：禁止

**已修复**：2026-11-04 PowerShell Set-Content 带 BOM 导致 ~67 个文件头有 `\xEF\xBB\xBF`，编译报 `'\ufeff'` 错误。**修复脚本**：

```powershell
$files = Get-ChildItem -Recurse -Include *.java,*.xml,*.yml,*.yaml,*.properties
foreach ($f in $files) {
    $bytes = [System.IO.File]::ReadAllBytes($f.FullName)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        [System.IO.File]::WriteAllBytes($f.FullName, $bytes[3..($bytes.Length-1)])
        Write-Host "Fixed BOM: $($f.FullName)"
    }
}
```

## 二、推荐规约（新文件必须遵守，存量逐步整改）

### 1. import 分组

按顺序：`java.*` → `jakarta.*` → `org.*` → `com.*` → 项目内包。组间空一行。

### 2. Javadoc 模板（类）

```java
/**
 * XXXService —— 一句话说明职责边界。
 * <p>
 * 补充设计决策、依赖关系、与其他组件的交互方式。
 * </p>
 *
 * @author oliver
 * @since 2026-11-04
 */
```

**禁止**：MyBatis Plus 模板注释（`@ClassName` / `@Description` / `@Date` 空内容）—— 已全量清理。

### 3. Javadoc 模板（public 方法）

```java
/**
 * 门店前缀匹配。
 *
 * @param keyword 门店名称关键词（不允许空字符串，由调用方校验）
 * @return 候选门店列表（最多 20 条），每条包含 stationCode / stationName / memberCount
 */
List<Map<String, Object>> guessStationByPrefix(@Param("keyword") String keyword);
```

### 4. 异常处理（MCP Tool 特有）

```java
// ✅ Tool 返回值 = 模型能拿到的唯一反馈，异常不能只 log 不返回
@Tool(name = "sendEmail")
public String sendEmail(EmailRequest req) {
    try {
        mailSender.send(...);
        return "OK";                              // 成功
    } catch (MessagingException e) {
        log.error("发送邮件失败, to={}, subject={}", req.getTo(), req.getSubject(), e);
        return "发送失败：" + e.getMessage();      // 失败原因必须返回给模型
    }
}
```

**已修复**：EmailTool 之前吞异常只 log，返回 void —— 模型不知道成没成。

### 5. SQL 注释规范

每条 `@Select` / `@Insert` / `@Update` 上方写清楚：表名、关键 WHERE 条件的业务含义、防全表扫约束、del_flag 口径。

```java
/**
 * 门店前缀匹配（`keyword%`）。
 * 统一走 member_info_view（项目口径），del_flag='0' 表示有效会员。
 * GROUP BY stationCode 去重，按会员数倒序；LIMIT 20 防全表扫。
 */
@Select("""
        SELECT mv.member_allot_store AS stationCode, ...
        FROM member_info_view mv
        WHERE mv.del_flag = '0'
          AND mv.member_allot_stationName LIKE CONCAT(#{keyword}, '%')
        GROUP BY mv.member_allot_store, mv.member_allot_stationName
        ORDER BY memberCount DESC
        LIMIT 20
        """)
List<Map<String, Object>> guessStationByPrefix(@Param("keyword") String keyword);
```

### 6. @Value 默认值写法

```java
// ✅ 正确：配置项有默认值，启动时不报错
@Value("${app.chat.memory.redis.prefix:chat:memory:}")
private String keyPrefix;

@Value("${app.chat.memory.redis.ttl-days:7}")
private int ttlDays;

// ❌ 禁止：无默认值，缺少配置就启动失败
@Value("${app.chat.memory.redis.prefix}")
private String keyPrefix;
```

### 7. Repository 内部 ObjectMapper：直接 new 不注入

```java
// ✅ 正确：RedisChatMemoryRepository 内部用的 ObjectMapper
// 只需要基础序列化/反序列化能力，不需要全局配置（日期格式、字段过滤等）
public RedisChatMemoryRepository(StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
    this.mapper = new ObjectMapper();  // 直接 new
}

// ❌ 禁止
public RedisChatMemoryRepository(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
    this.mapper = objectMapper;  // 注入全局 ObjectMapper —— 过度耦合
}
```

### 8. 简单 JSON 字段提取：手动实现避免 ObjectMapper 开销

SessionController.computeSummary() 里需要从 Redis LIST 存的 Message JSON 里提取 `messageType` / `text` 字段，如果每次都走 ObjectMapper 反序列化 20 条消息，性能浪费。手动实现 extractString 只解析需要的字段：

```java
// ✅ 正确：手动字符串提取，处理转义引号/换行/反斜杠
private static String extractString(String json, String field) {
    String key = "\"" + field + "\"";
    int keyIdx = json.indexOf(key);
    if (keyIdx < 0) return null;
    int colonIdx = json.indexOf(':', keyIdx + key.length());
    int startQuote = json.indexOf('"', colonIdx + 1);
    StringBuilder sb = new StringBuilder();
    for (int i = startQuote + 1; i < json.length(); i++) {
        char c = json.charAt(i);
        if (c == '"') break;
        if (c == '\\' && i + 1 < json.length()) {
            char next = json.charAt(++i);
            switch (next) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                default -> sb.append(next);
            }
        } else {
            sb.append(c);
        }
    }
    return sb.toString();
}
```

## 三、工具类模板（可直接复制）

### RedisLockUtils（Redisson 分布式锁声明式封装）

```java
package com.github.yingli.utils;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

/**
 * Redisson 分布式锁声明式封装。
 * 仅用纯 redisson 核心库，不引 redisson-spring-boot-starter（会覆盖 Spring Boot 原生 RedisConnectionFactory）。
 * 解锁只在 isHeldByCurrentThread 时执行，防误释放。
 *
 * @author oliver
 * @since 2026-11-04
 */
public final class RedisLockUtils {

    private RedisLockUtils() {
        throw new UnsupportedOperationException("RedisLockUtils cannot be instantiated");
    }

    /**
     * 尝试获取锁并执行（无返回值）。
     * 获取失败返回 false，lockWaitTime 超时后返回 false。
     *
     * @param client       RedissonClient 实例
     * @param lockKey      锁键（如 "chat:lock:conversationId"）
     * @param waitSeconds  最多等待秒数（0 = 立即返回）
     * @param leaseSeconds 持有秒数（-1 = 看门狗自动续期）
     * @param action       锁内执行的逻辑
     * @return true=获取成功执行完成；false=获取失败或超时
     */
    public static boolean execute(RedissonClient client, String lockKey,
                                  long waitSeconds, long leaseSeconds,
                                  ThrowingRunnable action) { ... }
}

@FunctionalInterface
interface ThrowingRunnable {
    void run() throws Exception;
}
```

### RedisHelper（StringRedisTemplate 通用操作）

```java
package com.github.yingli.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis 通用操作 —— LIST / VALUE / KEY 三大类。
 * 复用项目原生 StringRedisTemplate（Lettuce），不引入 Jedis / Redisson 替代。
 *
 * @author oliver
 * @since 2026-11-04
 */
@Component
public class RedisHelper {
    private final StringRedisTemplate redis;

    public RedisHelper(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** LIST */
    public List<String> lRange(String key, long start, long end) { ... }
    public Long lSize(String key) { ... }
    public String lIndex(String key, long index) { ... }
    public Long lRightPushAll(String key, String... values) { ... }
    public void lReplaceAll(String key, List<String> values) { ... }

    /** VALUE */
    public void set(String key, String value, long ttlSeconds) { ... }
    public String get(String key) { ... }

    /** KEY */
    public boolean exists(String key) { ... }
    public void delete(String... keys) { ... }
    public boolean expire(String key, long ttlSeconds) { ... }
}
```

### RedissonConfig（手动初始化 RedissonClient，不覆盖 RedisConnectionFactory）

```java
package com.github.yingli.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 手动初始化 RedissonClient，复用 {@code spring.data.redis.*} 配置。
 * 为什么不用 redisson-spring-boot-starter：它会覆盖 Spring Boot 原生 RedisConnectionFactory，
 * 导致 Spring AI RedisVectorStore（FT.CREATE/FT.SEARCH）和 RedisChatMemoryRepository 失效。
 *
 * @author oliver
 * @since 2026-11-04
 */
@Configuration
public class RedissonConfig {

    private static final Logger log = LoggerFactory.getLogger(RedissonConfig.class);

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.database:0}")
    private int database;

    /**
     * 手动构建 RedissonClient。destroyMethod = shutdown 确保 Spring 关闭时释放连接。
     * 绝不覆盖 RedisConnectionFactory / RedisTemplate —— 只提供 RedissonClient bean。
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        String address = "redis://" + host + ":" + port;
        config.useSingleServer().setAddress(address).setDatabase(database);
        if (password != null && !password.isBlank()) {
            config.useSingleServer().setPassword(password);
        }
        log.info("RedissonClient 初始化完成: {}, db={}", address, database);
        return Redisson.create(config);
    }
}
```

## 四、Redis ChatMemory 手动实现（Spring AI 1.x 官方未提供 Redis starter）

**现状**：Spring AI 2.0.0+ 才有 `spring-ai-starter-model-chat-memory-repository-redis`；1.x 只能自己实现。

**核心类**：
- `ChatMemoryConfig`：提供 `MessageWindowChatMemory(20)` + `RedisChatMemoryRepository` bean；注释掉 `redisChatMemoryRepository()` 的 @Bean 就自动回退 Spring AI 默认的 `InMemoryChatMemoryRepository`
- `RedisChatMemoryRepository`：LIST 存消息 + Jackson 序列化 + 手动路由反序列化

**序列化方案（已实测，必看）**：

```java
// ✅ 正确：序列化用 Jackson，反序列化双重路由
// Spring AI 1.1.x 的 UserMessage/AssistantMessage/SystemMessage 是不可变 POJO
// （无无参构造、无 @JsonCreator），Jackson 默认多态反序列化不支持

// 第一优先：@class 精确匹配（Jackson 序列化时自动带的类型标识）
private Message deserializeByClass(String className, String text, String json) {
    return switch (className) {
        case USER_MESSAGE_CLASS      -> new UserMessage(text);
        case ASSISTANT_MESSAGE_CLASS -> new AssistantMessage(text);
        case SYSTEM_MESSAGE_CLASS    -> new SystemMessage(text);
        case TOOL_RESPONSE_MESSAGE_CLASS -> {
            log.info("ToolResponseMessage 暂不支持完整恢复，跳过: {}", json);
            yield null;
        }
        default -> {
            log.warn("未识别的 Message 子类，跳过: {}", json);
            yield null;
        }
    };
}

// 兜底：messageType 枚举名匹配
private Message deserializeByType(String typeName, String text, String json) {
    MessageType type = MessageType.valueOf(typeName);
    return switch (type) {
        case USER      -> new UserMessage(text);
        case ASSISTANT -> new AssistantMessage(text);
        case SYSTEM    -> new SystemMessage(text);
        case TOOL      -> { log.info("TOOL 类型暂不支持，跳过"); yield null; }
    };
}
```

**已知限制（必须知晓）**：

| 项 | 现状 | 备注 |
|---|---|---|
| ToolResponseMessage 跳过 | `deserializeMessage()` 返回 null | 多轮工具回填场景下之前工具结果丢失；当前单轮 Tool → 回复流程不受影响 |
| metadata/media 字段丢弃 | 只取 text 字段 | 带图片附件或富媒体消息存 Redis 后，下次读取只剩纯文本 |
| keys() 而非 SCAN | `findConversationIds()` 用 `redisTemplate.keys()` | **测试库可接受**；生产环境必须换 `SCAN`，`KEYS` 会阻塞 Redis |
| TTL 默认 7 天 | `app.chat.memory.redis.ttl-days:7` | 0 表示不过期；LIST 存，每次 saveAll 都会重新 expire |

## 五、踩坑矩阵（已实测）

| 坑 | 现象 | 根因 | 修复 | 日期 |
|---|---|---|---|---|
| Redisson starter 覆盖 RedisConnectionFactory | `RedisVectorStore` bean 找不到 | redisson-spring-boot-starter 4.x 对应 Boot 4，覆盖 Lettuce 连接 | 换纯 redisson 核心库 + 手动 RedissonConfig | 2026-11-04 |
| Spring AI 1.1.6+ 删除 DEFAULT_CONVERSATION_ID | `ChatSessionKeyUtils` 编译不过 | 常量值就是 `"default"` | 硬编码 `"default"` | 2026-11-04 |
| ChatMemory 消息反序列化失败 | `MismatchedInputException` 无法构造 UserMessage | Message 子类是不可变 POJO，Jackson 无 Creator | 手动按 messageType 路由调构造器 | 2026-11-04 |
| PowerShell 批量替换带 BOM | 编译报 `'\ufeff'` | Set-Content 默认 UTF-8 with BOM | 脚本清 `\xEF\xBB\xBF` 头字节 | 2026-11-04 |
| 批量替换 `$name` 被 PowerShell 展开 | 9 个文件内容被污染 | `-replace` 字符串里的 `$name` 被 PowerShell 当变量 | Write 手动重写 + 反引号转义 | 2026-11-04 |
| SQL 瞎写 `del_flag = 2` | 一条数据都查不到 | 凭印象写值，没 grep 项目实际口径 | grep `MemberOverviewResult.java:15` → `del_flag='0'` | 2026-11-04 |
| GuessTool 查 member_info 表 | 卡片会员数与实际对不上 | 项目统一用 member_info_view，不是原表 | 改 `FROM member_info_view mv` | 2026-11-04 |
| kimi-k3 400 Bad Request（旧 OpenAI starter 时） | WebClientResponseException 400 | kimi-k3 是 thinking-only 模型，temperature 必须 1.0 | application.yml 显式写四行固定值 | 2026-11-04 |
| **DashScope `InvalidParameter: url error`（多模态模型）** | SAA DashScope starter 调 kimi-k3 / kimi-k2 反复报 `url error` | **多模态模型必须走 `/multimodal-generation` 端点，SAA 默认走 `/text-generation`** | application.yml 加 `multi-model: true` | **2026-11-05** |
| **DashScope base-url 带 `/api/v1` 后缀** | 404 或 URL 拼接成 `/api/v1/api/v1/...` | SAA 内部自动拼完整 path | base-url 只写域名（`https://{workspaceId}.{region}.maas.aliyuncs.com`） | **2026-11-05** |
| **MaaS endpoint key 不能调 DashScope 原生 API** | `InvalidParameter: url error` + 官方域名和业务空间域名都试了都不通 | MaaS 推理 endpoint 的 key 只能调 `/compatible-mode/v1` 兼容端点 | 用百炼控制台 API Key 页面创建的主账号/业务空间 key | **2026-11-05** |
| **Spring AI BOM 1.1.8 降回 1.1.2** | 对齐 SAA 编译基线 | SAA 1.1.2.2 的 autoconfigure-dashscope + graph-core pom 都 pin Spring AI 1.1.2 | 两模块 pom.xml dependencyManagement 统一 BOM 1.1.2 | **2026-11-05** |
| **SAA BOM 不管理 starter-dashscope** | Maven 编译报 `dependencies.dependency.version missing` | SAA BOM 只托管 8 个 artifact（agentscope/graph-core/agent-framework 等），不含 starter-dashscope | dependencyManagement 里额外显式托管 `spring-ai-alibaba-starter-dashscope:1.1.2.2` | **2026-11-05** |

### DashScope `url error` 排查优先级（新！）

> 遇到 DashScope 原生 API 报 `InvalidParameter: url error`，**不要先怀疑 URL 拼错**，按以下顺序排查：
>
> 1. **是不是多模态模型**？（kimi-k3 / qwen-vl / omni 系列 → 开 `multi-model: true`）
> 2. **base-url 是不是带了 `/api/v1` 后缀**？（去掉，SAA 内部会拼）
> 3. **API key 是不是 MaaS endpoint key**？（换百炼控制台 API Key 页面创建的）
> 4. **model 名是不是百炼原生 API 支持的**？（不要用 `kimi-k2.7-code` / `qwen3.8-2.4t-a95b` 这种兼容端点专属名）
>
> **PowerShell 快速验证**（绕开 Java 代码，直接打 DashScope API 确认根因）：
> ```powershell
> # 多模态端点验证
> $h=@{Authorization="Bearer $env:DASHSCOPE_API_KEY";'Content-Type'='application/json'}
> $b='{"model":"kimi-k3","input":{"messages":[{"role":"user","content":[{"type":"text","text":"hello"}]}]},"parameters":{"result_format":"message"}}'
> try{$r=Invoke-RestMethod -Method Post -Uri "https://{baseUrl}/api/v1/services/aigc/multimodal-generation/generation" -Headers $h -Body $b -TimeoutSec 30;$r|ConvertTo-Json -Depth 10}catch{"Status: $($_.Exception.Response.StatusCode)";[System.IO.StreamReader]::new($_.Exception.Response.GetResponseStream()).ReadToEnd()}
> ```

## 六、版本升级策略（已验证）

| 升级路径 | 工作量 | 风险 | 备注 |
|---|---|---|---|
| Spring AI 1.0.0 → 1.1.8（同 minor） | 0.5 人日 | 低 | 本仓库实测 219 测试全过，唯一 breaking 是 DEFAULT_CONVERSATION_ID |
| Spring AI 1.1.x → 2.0.x（大版本） | 3–5 人日 | 中 | Redis ChatMemory starter 有了，但 MCP / ChatClient API 有 breaking |
| Boot 3.5 → 4.0（大版本） | 5–7 人日 | 高 | Spring Framework 6→7，双重 breaking；Redisson starter 也要换版本 |
| **推荐**：保持 Boot 3.5 + Spring AI 1.1.x，等 Spring AI Alibaba 2.0.0 GA（对齐 Spring AI 2.0 GA + Boot 4.x）再考虑大版本迁移 | — | — | SAA 当前只到 1.1.2.4-security-fix（GA），2.0.0 还是 milestone |

## 七、git 提交规范

```
feat(P1): 简短标题
       ↑ 模块代号  ↑ 冒号后空一格

核心变更:
- 行内说明（不超过 72 字符/行）
- 继续...

破坏性变更:
- 仅当有 breaking 时填写

测试: mcp-server 219/219, ChatSessionKeyUtilsTest 7/7, compile ✅

排除: .env (敏感凭证)
```

**模块代号约定**：`P0` / `P1` / `P2` / `fix` / `refactor` / `chore`。

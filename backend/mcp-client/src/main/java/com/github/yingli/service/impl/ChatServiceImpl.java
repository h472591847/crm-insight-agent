package com.github.yingli.service.impl;

import cn.hutool.json.JSONUtil;
import com.github.yingli.constants.ChatConstants;
import com.github.yingli.entity.ChatEntity;
import com.github.yingli.entity.ChatResponseEntity;
import com.github.yingli.entity.SearchResult;
import com.github.yingli.enums.SSEMsgType;
import com.github.yingli.repository.SessionOwnershipRepository;
import com.github.yingli.service.ChatService;
import com.github.yingli.service.SearXngService;
import com.github.yingli.tool.ToolCallbackDecorator;
import com.github.yingli.utils.ChatSessionKeyUtils;
import com.github.yingli.utils.RedisLockUtils;
import com.github.yingli.utils.SSEServer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor.*;

/**
 * CRM 数据分析助手核心对话服务。
 * <p>
 * 职责边界：
 * <ul>
 *     <li>{@link #doChat} — 问数 CRM 流程，挂载对话记忆 + MCP 工具调用 + 分布式锁并发保护</li>
 *     <li>{@link #doChatRagSearch} — 基于知识库 RAG 的问答流程，不挂载记忆</li>
 *     <li>{@link #doChatWebSearch} — 联网搜索流程，不挂载记忆</li>
 *     <li>{@link #chatTest} / {@link #streamResponse} / {@link #streamStr} — 基础连通性测试方法</li>
 * </ul>
 * 设计要点：所有依赖通过构造器注入并声明为 {@code final}，线程安全由 Redisson 分布式锁保证。
 * SearXngService 为可选依赖（测试环境无 SearXNG 时 @Profile("!test") 不装配），用 @Autowired(required=false) 注入。
 * </p>
 *
 * @author oliver
 * @since 1.0.0
 */
@Service
@Slf4j
public class ChatServiceImpl implements ChatService {

    /** Spring AI ChatClient（由自动配置提供，已绑定 MCP 工具） */
    private final ChatClient chatClient;

    /** 对话记忆（RedisChatMemoryRepository 持久化实现） */
    private final ChatMemory chatMemory;

    /**
     * 联网搜索服务（测试环境 SearXngServiceImpl 加了 @Profile("!test") 不装配 → 这里为 null）。
     * doChatWebSearch() 调用前必须判空。
     */
    @Autowired(required = false)
    private SearXngService searXngService;

    /**
     * 问数 CRM 流程专用的对话记忆 advisor。
     * 只在 {@link #doChat} 挂载到 prompt，其他流程不挂，避免共享 conversationId 跨流程污染。
     */
    private final MessageChatMemoryAdvisor memoryAdvisor;

    /** Redisson 分布式锁客户端，用于同一会话并发保护（多实例部署也生效）。 */
    private final RedissonClient redissonClient;

    /** 会话归属仓库：保证 sessionId 只属于创建它的用户，防止跨用户读写同一会话。 */
    private final SessionOwnershipRepository sessionOwnershipRepository;

    /** SSE 通道管理器：按 userId:sessionId 推送流式消息与结束消息。 */
    private final SSEServer sseServer;

    /**
     * 问数 CRM 流程的系统提示词，定义业务行为边界（门店过滤规则、指标口径、工具选择策略等）。
     */
    private final String systemPrompt = """
            你是 CRM 数据分析助手'小玖'，通过受控只读工具回答业务统计问题。
            行为边界：
            1. 只回答用户当前提出的问题，不扩写需求范围。
            2. 工具返回空结果或计数为 0 是正常答案：直接说明该条件下的记录数为 0，到此为止；
               禁止更换时间范围、门店或其他参数去补查、验证或联想其他区间。
            3. 工具返回的 caliber 口径字段是内部审计用的，禁止输出给用户，必须从最终回答中移除；
               同样禁止把内部过滤条件、字段名、条件模板名、默认过滤器、记录数推导或排除过程
               （如 track≠1、del_flag、要客记录数为 0 这类推导）写进回答；
               回答只给业务结论：数字、日期、门店名、会员级别、是否要客等。
               只有用户明确问"你是怎么算的/口径是什么"时才解释口径。errorCode 如实转述。
            4. 不输出姓名、手机号、地址等个人信息明细。
            5. 不给多余建议，不主动推荐用户没问的对比、趋势或归因。
               回答直接给数字和结论，不要加"好的"、"根据查询结果"、"以下是"、"统计口径是"、"数据来源于"
               等开场白或解释性文字；用户不关心这些。
            6. 数字一律以工具返回为准，不得自行计算或推测。
            7. 工具选择：已固化指标只在口径与问题完全匹配时使用；口径不匹配就一次指标工具都不调，
               直接按 listSemanticCatalog 走 adhocQuery。不得为把名称换成编码而拉全量清单反查。
            8. 门店/会员过滤一律编码优先：order/detail 的门店条件只能用 expense_stationCode/expense_d_stationCode
               等值（有索引）；门店名称列无索引，禁止对它们做任何过滤（服务端会拒绝）。
               以下场景**必须先调用 guessStation 工具**，不得跳过：
               - 用户只说了街道名/路名/商圈名/行政区名（如"长江路有多少用户"、"中央路店销售额"、"南岗区会员数"）
               - 用户给的门店名称可能不唯一（关键词短、未说全 XX 市/店后缀）
               - 用户用模糊指代（"我们店"、"哈尔滨的店"、"附近那家"）
               - 用户问的是门店列表类问题（"XX 有哪些门店"）
               关键词直接取用户原文里的街道/路名/商圈名（如"长江路"就是 keywords=["长江路"]），
               保留用户原文里的城市/区前缀（用户写"哈市长江路"就传"哈市长江路"，不要截短成"长江路"，
               否则会跨城市命中共名路段门店），也不要额外拼接"店"。
               用户一句话提到多个门店时，keywords 数组每个门店一个元素（如"哈市长江路与鸿翔路"
               → keywords=["哈市长江路","鸿翔路"]），必须逐店分别解析、分别统计、分别输出；
               不得只取其中一个门店，也不得把两个门店名合并成一个关键词去模糊匹配。
               工具返回 JSON 含 results[]（每个关键词一条）与顶层 actionHint，按此字段决策：
               - all_unique / unique_match → 直接按各门店编码分别查询，回答开头说明实际匹配到的门店名
               - matchedBy=office_only（会员表查不到、机构表兜底命中门店）→ 门店**存在**，只是当前没有会员分配记录，
                 会员/要客数为 0；不得说成“门店不存在”。候选里 hasOrders=true 时补一句“该门店有销售记录”
               - needs_clarify / multiple_matches → **必须输出 <clarify> 标记块**，见下方格式要求
               - partial_no_match / no_match → 对没找到的门店请用户提供更准确的名称/编码，不得猜；
                 其他唯一命中的门店照常查询输出
               - invalid_input → 如实告知"关键词太短或手机号格式不对"
               multiple_matches 时的输出格式（严格遵守，模型看到 <clarify> 会弹出交互卡片）：
               先写一句自然语言引导（"为您找到 N 家匹配的门店，请选择要查的哪一家："），
               再紧跟 <clarify> 块（必须单独一行，前后各一行）：
               <clarify>{"type":"station","candidates":[{"code":"01010601","label":"哈市长江路店","meta":"1248 会员"},{"code":"01010602","label":"辽宁海城长江路店","meta":"892 会员"}]}</clarify>
               字段说明：type=station（会员多候选见第 18 条 f）；candidates[].code 是用户回复的编码；candidates[].label 是显示名；
               candidates[].meta 是副行（可选，如会员数）。前端会自动渲染成交互卡片，
               用户点击选项后自动把 code 发回来，不要再用 markdown 表格或纯文本列表，不要省略标记。
            9. 关键业务词汇→数据库字段/指标**硬映射**（遇下列词汇时直接按表取值，不得自行猜测或替换）：
               ┌───────────┬──────────────────────────────────────────┐
               │ 用户说…    │ 模型必须取…                               │
               ├───────────┼──────────────────────────────────────────┤
               │ 注册会员   │ member_created（会员创建时间）            │
               │ 新会员     │ MEMBER_NEW 指标（同上）                   │
               │ 新增会员   │ MEMBER_NEW 指标（同上）                   │
               │ 新要客     │ VIP_NEW 指标 → track_time                 │
               │ 新增要客   │ VIP_NEW 指标 → track_time                 │
               │ 要客（存量）│ track = 1（IS_VIP 条件模板）              │
               │ 会员级别   │ member 维度 MEMBER_LEVEL（new_member_level_value）│
               │ 从哪注册的 │ member 维度 CHANNEL_TYPE（注册渠道）       │
               │ 注册渠道   │ member 维度 CHANNEL_TYPE（注册渠道）       │
               │ 注册门店   │ member 维度 REG_STORE（member_store）      │
               │ 顾客价值   │ member 维度 CUSTOMER_VALUE（customer_cost）│
               │ 活跃会员   │ ACTIVE_MEMBER 指标（近 3 月有消费）        │
               │ 累计消费额 │ MEMBER_CONSUMPTION 指标（订单实付实时汇总）│
               │ 近一年半消费│ MEMBER_CONSUMPTION + 近 18 个月时间窗      │
               │ X 月消费额 │ JOIN expense_calendar_info 过滤 expense_buyDate │
               └───────────┴──────────────────────────────────────────┘
               注册渠道（CHANNEL_TYPE）只有 4 个确定取值，直接按维度输出，不需要追问：
               饿百（channel_type='1'）、美团（'2'）、京东到家（'3'）、未知渠道（其余，含空值）；
               过滤时用 channel_type = '1'/'2'/'3'，不要用中文字面值过滤。
               用户问"从哪注册的/什么渠道注册的"时按注册渠道回答；只有同时可能指注册门店且用户未说明时，
               才按第 18 条输出 ambiguity 卡片，候选固定两项：注册渠道（CHANNEL_TYPE）、注册门店（REG_STORE）。
               顾客价值（CUSTOMER_VALUE）库内直接存中文标签，直接查询输出，不追问；
               需要按价值筛选时以维度实际取值为准，不得编造等级名称。
               金额口径提醒：CRM 会员列表页的金额区间用 18 个月快照 member_expense_totalsum_year（非实时，
               且快照子查询未加 expense_state='10'）；本系统消费金额统一用订单实付实时汇总，
               两者在退单处理与更新时点上可能不同，不得说成等价。
               典型歧义例："5 月注册了多少要客" → 同时出现"注册"和"要客"，
               注册→member_created ∈ 5 月，要客→track=1（当前状态），两者 AND。
               该场景不需要用户澄清，直接按表组合查询。
               但如果用户说"5 月有多少要客"（无"注册"或"新增"），
               表示"当前要客中，在 5 月被标记为要客的"还是"当前所有要客"有歧义，
               这时输出 <clarify> 让用户选（格式见第 10 条）。
               **时间条件传参规则**（重要，传错会 INVALID_PARAM）：
               - 注册时间、标记时间、消费时间等**时间类条件** → 走 timeRange，timeDimension 取 timeColumns 的 key：
                 member.CREATED = member_created（注册时间）、
                 member.TRACK_TIME = track_time（标记要客时间）、
                 member.TRACK_CANCEL_TIME = track_cancel_time（踢出要客时间）、
                 member.FIRST_CONSUME_TIME = consume_time（首次消费时间）、
                 member.ALLOT_TIME = member_allot_time（最近门店分配时间）、
                 order.BUY_DATE = expense_buyDate（下单时间）、
                 detail.BUY_DATE = expense_d_buyDate（明细时间）
               - 不要把时间列（member_created / track_time / expense_buyDate 等）放进 filters 里；
                 时间列语义上属于 timeRange 的 timeDimension，走 filters 虽然白名单已放行，
                 但语义会被 SQL 编译器误解成普通等值条件，丢失窗口语义。
               - IS_VIP、门店、性别等**非时间条件** → 走 filters，优先用 preset 引用 conditionPresets 模板。
            10. **口径歧义时不得自行选择，必须先追问、再回答**。
                有歧义时**禁止先答一种口径再补追问**（用户会看到一半答案被污染）；
                正确做法是：停下来，输出 <clarify>，等用户选完再查询回答。
                触发场景：
                - 消费额/金额类词没指明时间窗口 → 累计消费 vs 指定时间内消费
                - "要客"没指明是"当前所有要客"还是"新增要客"（track_time 窗口）
                - "会员级别/级别"没指明是哪套口径（见第 14 条候选）
                - 任何符合第 9 条映射表但时间含义有两种以上合理解读的
                口径歧义的 clarify 输出格式（严格遵守，**必须**输出 <clarify> 标记块，不要省略，不要用纯文本追问）：
                先写一句引导（"这个问题有两种口径，请问您要哪种？"），
                再紧跟单独一行的 <clarify> 块：
                <clarify>{"type":"ambiguity","question":"要客消费额的口径","candidates":[{"code":"cumulative","label":"累计消费总额","meta":"所有历史消费"},{"code":"window","label":"指定时间段内的消费","meta":"需指定起止日期"}]}</clarify>
                规则：
                - type 固定为 ambiguity（前端会渲染成交互卡片）
                - candidates[].code 是用户回复的机器可读值（如 cumulative / window）
                - candidates[].label 是给用户看的选项文案
                - candidates[].meta 是副行（可选，解释区别）
                - 只能用 <clarify>，不要用 markdown 表格、不要用纯文本列表、不要省略标记
                - 前端会自动渲染成交互卡片，用户点击后把 code 发回来
            11. 一次 adhocQuery 只能一条实体链、一种度量粒度：实体链必须“先一对多、后一对一”，
               跨粒度指标（如会员数与明细金额）不得放在同一块。问题包含多个独立组合时用
               adhocQueryBatch 拆成多个查询块，不得为凑一个结果拼接无声明关系的表。
            12. member 关联 detail 时，detail 仍必须带定向条件：timeRange 的 entity 显式写 detail（≤92天），
                或用 detail 的 expense_d_mobile/expense_d_orderId 过滤。用户已给出完整手机号时，
                可直接用手机号列等值过滤：member→member_mobile、order→expense_mobile、detail→expense_d_mobile；
                detail 的手机号等值即守卫定向条件、无需 timeRange，member/order 也不强制时间范围。
                手机号列禁止 LIKE/前缀/包含等模糊匹配，号码不完整时向用户索取完整手机号，
                也不必先 guessMemberByMobile 解析。三表直连在订单流水号唯一性确认前不可用；
                需要三表信息时拆块：明细+会员按门店/类别用 DETAIL_SALES，订单+会员按门店用 SALES。
                商品名称 expense_d_commName 只能作为商品维度分组，不能作为过滤条件；可过滤的是商品类别名称
                expense_d_commCodeName 与货号/SKU expense_d_gdcode，三者语义不同不得混用。
            13. 收到 ADHOC_QUERY_TIMEOUT（或指标返回“超过服务端 60 秒上限”）时，说明查询范围过大：
                只能缩小时间范围或增加等值过滤条件后重查一次，不得用相同参数重复调用，
                也不得改用其他无关口径凑数；仍超时就如实告知用户，不得编造数字。
            14. 会员级别是复合口径。用户只说“会员级别/级别”而没指明哪套口径时，
                必须按第 10 条输出 <clarify>，候选 code/label 如下，不得自行默认其中一个：
                MEMBER_LEVEL/新会员等级（初享/悦享/优享/荣享/臻享/尊享/鼎享）、
                HAIDING_LEVEL/CRM级别、COMPANY_LEVEL/公司级别（毛利级别，18 个月）、
                CITY_LEVEL/城市级别、STORE_LEVEL/门店级别、PERSONAL_LEVEL/个人级别、
                HALF_YEAR_SALES_LEVEL/一年半销售级别（18 个月）、TOTAL_YEAR_SALES_LEVEL/总销售级别（全部时间）。
                用户选定后按对应维度查询；编码类级别（HAIDING_LEVEL 除外）原样输出，不得自行解释编码含义。
                三个销售/毛利级别的编码已按 CRM set_level 核实：COMPANY_LEVEL 21 个（AA~AC、BA~BC、CA~CC、
                DA~DC、EA~EC、FA~FC、GA~GC）、HALF_YEAR_SALES_LEVEL 17 个（AA~AC、BA~BC、CA~CC、DA~DC、
                EA~EC、FA~FB）、TOTAL_YEAR_SALES_LEVEL 24 个（AA~AC 至 HA~HC）；set_level 无中文名称，
                不得声称某个编码对应某个等级含义，按维度编码原样返回即可。
                PERSONAL_LEVEL 只有真库观察值 a~f、没有中文映射：用户选定个人级别后按编码原样输出，
                不得编造编码对应的中文等级，也不得就编码含义再追问。
            15. 是否要客：CRM 页面筛选 track 0=否 / 1=是（要客/被跟踪）；取消要客流程会出现 track=2，
                NULL 也可能存在，不得把 2 或 NULL 说成“否”。过滤用 IS_VIP 或 track=1；
                输出用 VIP_FLAG 维度（要客/非要客/未知），不要直接输出 1/0。
            16. 调用工具时 arguments 必须是严格合法的 JSON：数组元素是对象时逐层闭合（对象用 }、数组用 ]），
                不得多写或漏写括号；参数结构错误会导致本次工具调用失败。
            17. 遇到机构、总部、大区这类范围词，先调 resolveOfficeStores 拿门店编码，再把 storeCodes 传给
                member_allot_store 的 IN 过滤（values 数组，总部级 369 个门店也在去重后 500 个上限内）；不要用 office_id 代替。
                门店口径只含 6/11/22/28（直营/加盟），渠道店 18、要客部 13、新媒体 14 不纳入统计。
                用户给的是机构全名（如“集团总部”）时直接按名称解析；**resolved 就直接查询，不得因为机构类型是总部/大区
                不是门店而再让用户确认机构**——工具会自己解析出下级门店编码。
                只有返回 needs_clarification（名称降级后多候选）时才**必须输出 <clarify>**（type=station）：把 candidates 的
                officeId 转成 code、officeName 转成 label、机构类型转成 meta（总部/大区/门店），
                不得只写“请确认机构”这类没有选项的引导语；
                返回 no_stores 时按 actionHint 如实区分“机构下没有可统计门店”和“渠道/加盟、要客部、新媒体类机构不纳入统计”，
                不要写成“请核对机构范围”；
                返回 too_many_stores 时用普通文字请用户缩小范围（不生成卡片），
                不得截取部分门店或分批求和。
            18. 追问（<clarify>）统一硬约束——对第 8、10、14、17 条及所有追问场景同时生效：
                a) 需要用户做选择时，必须输出完整 <clarify> 标记块；只写“还需要确认口径，请选择：”
                   “请选择：”这类引导语而不带标记块，前端不会出现任何可点选项，属于错误输出。
                b) <clarify> 内必须是合法 JSON，candidates 必须是非空数组（至少 1 项），
                   每项必须同时给出 code 和 label（字符串），meta 可选；缺 code 或 label 视为非法。
                c) type 只能是 station / member / ambiguity；口径类追问用 ambiguity，并用 question 说明主题。
                d) 输出前自检：去掉 candidates 后这句话仍成立，说明只有引导语没有选项，必须补全后再输出。
                e) 没有确定候选集时不得编造选项，也不得输出空 candidates 的卡片：改为普通文字向用户索取
                   一个确定信息（如“请提供完整手机号”“请提供完整门店名称”），句末不得再出现“请选择：”。
                f) 会员候选涉及姓名、手机号等个人信息：guessMemberByMobile 多命中时不得把姓名或完整手机号
                   写进卡片，改为普通文字请用户提供完整手机号；卡片只用于门店、机构与口径选择。
            """;

    /**
     * 构造器注入（Spring AI ChatClient.Builder + ToolCallbackProvider 自动配置提供）。
     * SearXngService 是可选依赖（测试环境为 null），不在构造器里注入。
     * 所有声明为 final 的字段确保线程安全。
     */
    public ChatServiceImpl(ChatClient.Builder chatClientBuilder,
                           ToolCallbackProvider tools,
                           ChatMemory chatMemory,
                           RedissonClient redissonClient,
                           SessionOwnershipRepository sessionOwnershipRepository,
                           SSEServer sseServer) {
        this.chatMemory = chatMemory;
        this.redissonClient = redissonClient;
        this.sessionOwnershipRepository = sessionOwnershipRepository;
        this.sseServer = sseServer;
        this.memoryAdvisor = builder(chatMemory).build();
        this.chatClient = chatClientBuilder
                // 添加 MCP 工具功能（由 spring-ai-starter-mcp-client 自动注入 ToolCallbackProvider）
                // 包一层装饰器：工具参数 JSON 解析失败时落脱敏原文日志，并把错误回灌模型自纠，避免整轮流中断
                .defaultToolCallbacks(ToolCallbackDecorator.decorate(tools.getToolCallbacks()))
                // 记忆 advisor 不进 defaultAdvisors，只在 doChat 按需挂载，避免 RAG/联网/chatTest 污染
                .build();
    }

    @Override
    public String chatTest(String prompt) {
        return chatClient.prompt(prompt).call().content();
    }

    @Override
    public Flux<ChatResponse> streamResponse(String prompt) {
        return chatClient.prompt(prompt).stream().chatResponse();
    }

    @Override
    public Flux<String> streamStr(String prompt) {
        return chatClient.prompt(prompt).stream().content();
    }

    @Override
    public void doChat(ChatEntity chatEntity) {
        String userId = chatEntity.getCurrentUserName();
        String sessionId = chatEntity.getSessionId();
        String prompt = chatEntity.getMessage();
        String botMsgId = chatEntity.getBotMsgId();

        // 记忆按会话隔离；SSE 按 userId:sessionId 分通道，旧客户端回退到原行为
        String conversationId = ChatSessionKeyUtils.resolveConversationId(sessionId, userId);
        String channelKey = ChatSessionKeyUtils.resolveChannelKey(userId, sessionId);

        // 会话归属校验：sessionId 只允许创建它的用户写入，防止 B 用户往 A 用户的记忆里追加消息
        SessionOwnershipRepository.ClaimResult claimResult =
                sessionOwnershipRepository.claimOrVerify(conversationId, userId);
        if (claimResult == SessionOwnershipRepository.ClaimResult.FORBIDDEN) {
            log.warn("拒绝越权写入会话 conversationId={}, userId={}", conversationId, userId);
            ChatResponseEntity denied = new ChatResponseEntity("会话不存在或无权访问，请新建会话。", botMsgId, sessionId);
            sseServer.sendMsg(channelKey, JSONUtil.toJsonStr(denied), SSEMsgType.FINISH);
            return;
        }

        // 同一会话并发请求快速失败，避免记忆读写顺序错乱
        // Redisson 分布式锁工具：waitTime=0（不等待，立即返回），leaseTime=-1（业务结束后手动释放）
        String lockKey = ChatConstants.REDIS_KEY_LOCK_PREFIX + conversationId;
        Boolean acquired = RedisLockUtils.execute(
                redissonClient, lockKey, 0L, -1L, java.util.concurrent.TimeUnit.SECONDS, () -> {
                    // ===== 持锁期间的业务逻辑 =====
                    // 工具上下文：参数解析失败时装饰器用它定位会话与消息，不写入模型可见的提示词
                    Map<String, Object> toolContext = new HashMap<>();
                    toolContext.put(ToolCallbackDecorator.CONTEXT_CONVERSATION_ID, conversationId);
                    if (botMsgId != null) {
                        toolContext.put(ToolCallbackDecorator.CONTEXT_BOT_MSG_ID, botMsgId);
                    }
                    Flux<String> stringFlux = chatClient.prompt(prompt).system(systemPrompt)
                            .toolContext(toolContext)
                            .advisors(a -> a.advisors(memoryAdvisor)
                                    .param(ChatMemory.CONVERSATION_ID, conversationId))
                            .stream().content();

                    // Flux.toList() 期间若模型内部（MCP 工具调用超时/异常、上游 API 限流等）
                    // 抛 RuntimeException，会直接 propagate 到 RedisLockUtils → POST /chat/doChat 返回 500
                    // → 前端显示"回复失败"。这里捕获后降级为 FINISH 消息，POST 正常返回 200。
                    String fullCollect;
                    try {
                        List<String> list = stringFlux.toStream().map(chunk -> {
                            sseServer.sendMsg(channelKey, chunk, SSEMsgType.ADD);
                            return chunk.toString();
                        }).toList();
                        fullCollect = String.join("", list);
                    } catch (Exception e) {
                        log.error("模型对话执行异常, conversationId={}, botMsgId={}, 类型: {}, 信息: {}",
                                conversationId, botMsgId, e.getClass().getSimpleName(), e.getMessage(), e);
                        fullCollect = "抱歉，查询过程中出现异常，请稍后重试或缩小查询范围。";
                    }

                    ChatResponseEntity chatResponseEntity = new ChatResponseEntity(fullCollect, botMsgId, sessionId);
                    sseServer.sendMsg(channelKey, JSONUtil.toJsonStr(chatResponseEntity), SSEMsgType.FINISH);
                    return Boolean.TRUE;
                }
        );

        if (acquired == null) {
            // null = 未获取到锁（被其他线程持有），快速返回
            log.warn("同一会话正在生成，拒绝并发请求, conversationId={}, botMsgId={}", conversationId, botMsgId);
            ChatResponseEntity busyResponse = new ChatResponseEntity("当前会话正在生成中，请稍后再试。", botMsgId, sessionId);
            sseServer.sendMsg(channelKey, JSONUtil.toJsonStr(busyResponse), SSEMsgType.FINISH);
        }
    }

    // Dify 智能体引擎构建平台
    private final String ragPrompt = """ 
            基于上下文的知识库内容回答问题:
            [上下文]
            {context}
            
            [问题]
            {question}
            
            [输出]
            如果没有查到, 请回复: 不知道.
            如果查到, 请回复具体的内容. 不相关的近似内容不必提到.
            
            """;

    @Override
    public void doChatRagSearch(ChatEntity chatEntity, List<Document> ragContext) {
        String userId = chatEntity.getCurrentUserName();
        String sessionId = chatEntity.getSessionId();
        String channelKey = ChatSessionKeyUtils.resolveChannelKey(userId, sessionId);
        String question = chatEntity.getMessage();
        String botMsgId = chatEntity.getBotMsgId();
        String modelName = chatEntity.getModelName();
        if (CollectionUtils.isEmpty(ragContext)) {
            log.error("未提供rag知识库的上下文");
            return;
        }
        // 构建提示词
        String context = null;
        context = ragContext.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n"));
        // 组装提示词
        Prompt prompt = new Prompt(ragPrompt
                .replace("{context}", context)
                .replace("{question}", question));
        log.info("组装后的提示词:{}", prompt);

        Flux<String> stringFlux = chatClient.prompt(prompt).stream().content();
        List<String> list = stringFlux.toStream().map(chatResponse -> {
            String content = chatResponse.toString();
            sseServer.sendMsg(channelKey, content, SSEMsgType.ADD);
            log.info("content:{}", content);
            return content;
        }).toList();

        String fullCollect = list.stream().collect(Collectors.joining());
        //FIXME: 可将fullCollect保存到数据库或文件中，供后续查询

        ChatResponseEntity chatResponseEntity = new ChatResponseEntity(fullCollect, botMsgId, sessionId);
        sseServer.sendMsg(channelKey, JSONUtil.toJsonStr(chatResponseEntity), SSEMsgType.FINISH);

    }


    // Dify 智能体引擎构建平台
    private static final String searXngPrompt = """ 
            你是一个互联网搜索大师,请基于以下互联网返回的结果作为上下文,根据你的理解结合用户的提问综合后,生成并且输出专业的回答:
            [上下文]
            {context}
            
            [问题]
            {question}
            
            [输出]
            如果没有查到, 请回复: 不知道.
            如果查到, 请回复具体的内容.
            
            """;

    @Override
    public void doInternetSearch(ChatEntity chatEntity) {
        String userId = chatEntity.getCurrentUserName();
        String sessionId = chatEntity.getSessionId();
        String channelKey = ChatSessionKeyUtils.resolveChannelKey(userId, sessionId);
        String question = chatEntity.getMessage();
        String botMsgId = chatEntity.getBotMsgId();

        // 测试环境 SearXNG 禁用时 searXngService 为 null
        if (searXngService == null) {
            log.warn("联网搜索未启用（测试环境无 SearXNG），拒绝请求");
            ChatResponseEntity disabled = new ChatResponseEntity("联网搜索功能在当前环境未启用，请使用 CRM 数据查询功能。", botMsgId, sessionId);
            sseServer.sendMsg(channelKey, JSONUtil.toJsonStr(disabled), SSEMsgType.FINISH);
            return;
        }

        List<SearchResult> searchResults = searXngService.search(question);
        // 构建searXng搜索引擎提示词
        String finalPrompt = buildSearXngPrompt(question, searchResults);
        Prompt prompt = new Prompt(finalPrompt);
        log.info("组装后的提示词:{}", prompt);

        Flux<String> stringFlux = chatClient.prompt(prompt).stream().content();
        List<String> list = stringFlux.toStream().map(chatResponse -> {
            String content = chatResponse.toString();
            sseServer.sendMsg(channelKey, content, SSEMsgType.ADD);
            log.info("content:{}", content);
            return content;
        }).toList();

        String fullCollect = list.stream().collect(Collectors.joining());
        //FIXME: 可将fullCollect保存到数据库或文件中，供后续查询

        ChatResponseEntity chatResponseEntity = new ChatResponseEntity(fullCollect, botMsgId, sessionId);
        sseServer.sendMsg(channelKey, JSONUtil.toJsonStr(chatResponseEntity), SSEMsgType.FINISH);

    }

    /**
     * 构建searXng搜索引擎提示词
     *
     * @param question
     * @param searchResults
     * @return
     */
    private static String buildSearXngPrompt(String question, List<SearchResult> searchResults) {
        StringBuilder context = new StringBuilder();
        searchResults.forEach(searchResult -> {
            context.append(
                    String.format("<context>\n[来源] %s \n [摘要] %s \n </context>\n",
                            searchResult.getUrl(),
                            searchResult.getContent()));
        });
        return searXngPrompt
                .replace("{context}", context.toString())
                .replace("{question}", question);
    }
}

package com.itzixi.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Arrays;
import java.util.Map;

/**
 * @ClassName ToolCallbackDecorator
 * @Description MCP 工具回调装饰器：在工具入参交给 Spring AI 解析之前拿到原文，用于两件事：
 * <ol>
 *     <li>参数 JSON 语法错误时落完整（脱敏）原文日志，解决"客户端解析失败、服务端看不到入参"的观测盲区；</li>
 *     <li>把解析失败转成 {@link ToolExecutionException}，让 {@code DefaultToolCallingManager} 按既有机制
 *     把错误文本作为工具结果回灌模型，由模型在同一轮自纠参数，而不是让整条流式响应中断。</li>
 * </ol>
 * 只处理"参数解析失败"这一类运行时异常；MCP 服务端/传输层抛出的 {@link ToolExecutionException}
 * 原样透传，不重复包装。不尝试修复非法 JSON——补括号可能补出语义错误的查询。
 *
 * @Author crm-agent
 */
@Slf4j
public class ToolCallbackDecorator implements ToolCallback {

    /** 工具上下文键：会话 ID（由 ChatServiceImpl 在每次请求时写入） */
    public static final String CONTEXT_CONVERSATION_ID = "conversationId";

    /** 工具上下文键：助手消息 ID（由 ChatServiceImpl 在每次请求时写入） */
    public static final String CONTEXT_BOT_MSG_ID = "botMsgId";

    private final ToolCallback delegate;

    public ToolCallbackDecorator(ToolCallback delegate) {
        this.delegate = delegate;
    }

    /** 批量包装（ChatServiceImpl 注册 MCP 工具时使用） */
    public static ToolCallback[] decorate(ToolCallback[] callbacks) {
        if (callbacks == null || callbacks.length == 0) {
            return new ToolCallback[0];
        }
        return Arrays.stream(callbacks).map(ToolCallbackDecorator::new).toArray(ToolCallback[]::new);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String toolName = safeToolName();
        try {
            String result = delegate.call(toolInput, toolContext);
            // 正常调用只记录定位信息（工具名 + 会话/消息 ID），不打入参全文，避免日志膨胀
            if (log.isDebugEnabled()) {
                log.debug("MCP 工具调用成功 tool={}, conversationId={}, botMsgId={}, argumentsLength={}",
                        toolName, contextValue(toolContext, CONTEXT_CONVERSATION_ID),
                        contextValue(toolContext, CONTEXT_BOT_MSG_ID), lengthOf(toolInput));
            }
            return result;
        } catch (ToolExecutionException e) {
            // 业务/传输层异常原样透传，由框架既有处理器处理，避免被二次包装
            log.error("MCP 工具执行失败 tool={}, conversationId={}, botMsgId={}, arguments={}",
                    toolName, contextValue(toolContext, CONTEXT_CONVERSATION_ID),
                    contextValue(toolContext, CONTEXT_BOT_MSG_ID), maskPii(toolInput), e);
            throw e;
        } catch (RuntimeException e) {
            log.error("MCP 工具调用异常 tool={}, conversationId={}, botMsgId={}, arguments={}",
                    toolName, contextValue(toolContext, CONTEXT_CONVERSATION_ID),
                    contextValue(toolContext, CONTEXT_BOT_MSG_ID), maskPii(toolInput), e);
            if (isArgumentParseFailure(e)) {
                // 关键：转成 ToolExecutionException 后，DefaultToolCallingManager 会把错误文本作为工具结果回灌模型，
                // 流式响应继续，模型可修正参数后重试；不在此修复 JSON
                throw new ToolExecutionException(getToolDefinition(), e);
            }
            throw e;
        }
    }

    /** 从异常链里识别 Jackson 解析失败（JsonParseException 等均继承 JsonProcessingException） */
    private static boolean isArgumentParseFailure(Throwable error) {
        Throwable current = error;
        // 防御异常链环，限制最大深度
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof JsonProcessingException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * PII 脱敏：日志里保留可定位信息，但不写完整手机号/证件号。
     * <ul>
     *     <li>18 位身份证：保留前 6 位与后 4 位；</li>
     *     <li>11 位手机号：保留前 3 位与后 4 位（138****5678）；</li>
     *     <li>其余连续 12 位以上数字（长号码、流水号等）：保留首尾各 4 位。</li>
     * </ul>
     */
    static String maskPii(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String masked = text.replaceAll("(?<!\\d)(\\d{6})\\d{8}(\\d{3}[\\dXx])(?![\\dXx])", "$1********$2");
        masked = masked.replaceAll("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)", "$1****$2");
        masked = masked.replaceAll("(?<!\\d)(\\d{4})\\d{4,}(\\d{4})(?!\\d)", "$1****$2");
        return masked;
    }

    private String safeToolName() {
        try {
            ToolDefinition definition = delegate.getToolDefinition();
            return definition == null ? "unknown" : definition.name();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static String contextValue(ToolContext toolContext, String key) {
        if (toolContext == null) {
            return "-";
        }
        Map<String, Object> context = toolContext.getContext();
        Object value = context == null ? null : context.get(key);
        return value == null ? "-" : String.valueOf(value);
    }

    private static int lengthOf(String text) {
        return text == null ? 0 : text.length();
    }
}

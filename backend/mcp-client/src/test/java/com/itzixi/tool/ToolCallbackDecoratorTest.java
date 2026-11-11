package com.itzixi.tool;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.DefaultToolMetadata;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Description 工具回调装饰器测试：合法入参透传、非法 JSON 转工具错误结果、日志定位与 PII 脱敏、
 * 定义/元数据转发、业务异常不重复包装。
 */
class ToolCallbackDecoratorTest {

    private static final ToolDefinition DEFINITION = DefaultToolDefinition.builder()
            .name("adhocQuery")
            .description("自助查询")
            .inputSchema("{}")
            .build();

    private static final ToolMetadata METADATA = DefaultToolMetadata.builder().returnDirect(true).build();

    private static final ToolContext CONTEXT = new ToolContext(
            Map.of(ToolCallbackDecorator.CONTEXT_CONVERSATION_ID, "sess-1",
                    ToolCallbackDecorator.CONTEXT_BOT_MSG_ID, "msg-1"));

    private Logger decoratorLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        decoratorLogger = (Logger) LoggerFactory.getLogger(ToolCallbackDecorator.class);
        appender = new ListAppender<>();
        appender.start();
        decoratorLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        decoratorLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void 合法入参原样透传且转发工具定义与元数据() {
        StubCallback delegate = new StubCallback(input -> "tool-result");
        ToolCallbackDecorator decorator = new ToolCallbackDecorator(delegate);

        String result = decorator.call("{\"entities\":[\"member\"]}", CONTEXT);

        assertThat(result).isEqualTo("tool-result");
        assertThat(delegate.lastInput).isEqualTo("{\"entities\":[\"member\"]}");
        assertThat(decorator.getToolDefinition()).isSameAs(DEFINITION);
        assertThat(decorator.getToolMetadata()).isSameAs(METADATA);
        assertThat(errorEvents()).isEmpty();
    }

    @Test
    void 非法JSON转成工具异常供框架回灌模型() {
        StubCallback delegate = new StubCallback(ModelOptionsUtils::jsonToMap);
        ToolCallbackDecorator decorator = new ToolCallbackDecorator(delegate);

        assertThatThrownBy(() -> decorator.call("{\"filters\":[{\"column\":\"x\"]}", CONTEXT))
                .isInstanceOf(ToolExecutionException.class)
                // 框架会把该 message 作为工具结果回灌模型，模型据此自纠参数
                .hasMessageContaining("Unexpected close marker");
    }

    @Test
    void 框架异常处理器把解析错误文本作为工具结果回灌模型() {
        StubCallback delegate = new StubCallback(ModelOptionsUtils::jsonToMap);
        ToolCallbackDecorator decorator = new ToolCallbackDecorator(delegate);

        try {
            decorator.call("{\"filters\":[{\"column\":\"x\"]}", CONTEXT);
            org.junit.jupiter.api.Assertions.fail("应当抛出 ToolExecutionException");
        } catch (ToolExecutionException e) {
            // 与 ToolCallingAutoConfiguration 默认一致：throwExceptionOnError=false 时异常文本即工具结果
            String toolResult = new DefaultToolExecutionExceptionProcessor(false).process(e);
            assertThat(toolResult).contains("Unexpected close marker");
        }
    }

    @Test
    void 解析失败日志包含工具名与会话定位且不写完整手机号() {
        StubCallback delegate = new StubCallback(ModelOptionsUtils::jsonToMap);
        ToolCallbackDecorator decorator = new ToolCallbackDecorator(delegate);

        String invalid = "{\"entities\":[\"member\"],\"filters\":[{\"value\":\"13812345678\"]}";
        assertThatThrownBy(() -> decorator.call(invalid, CONTEXT))
                .isInstanceOf(ToolExecutionException.class);

        String content = errorEvents().get(0).getFormattedMessage();
        assertThat(content)
                .contains("tool=adhocQuery")
                .contains("conversationId=sess-1")
                .contains("botMsgId=msg-1")
                .doesNotContain("13812345678")
                .contains("138****5678");
    }

    @Test
    void 业务工具异常原样透传不重复包装() {
        ToolExecutionException business = new ToolExecutionException(DEFINITION,
                new IllegalStateException("服务端拒绝执行"));
        ToolCallbackDecorator decorator = new ToolCallbackDecorator(new StubCallback(input -> {
            throw business;
        }));

        assertThatThrownBy(() -> decorator.call("{}", CONTEXT)).isSameAs(business);
        assertThat(errorEvents().get(0).getFormattedMessage()).contains("tool=adhocQuery");
    }

    @Test
    void 工具定义与元数据转发保持一致() {
        ToolCallbackDecorator decorator = new ToolCallbackDecorator(new StubCallback(input -> ""));

        assertThat(decorator.getToolDefinition().name()).isEqualTo("adhocQuery");
        assertThat(decorator.getToolMetadata().returnDirect()).isTrue();
    }

    @Test
    void 批量包装保持数量且逐个生效() {
        ToolCallback[] callbacks = new ToolCallback[]{
                new StubCallback(input -> "a"), new StubCallback(input -> "b")};
        ToolCallback[] decorated = ToolCallbackDecorator.decorate(callbacks);

        assertThat(decorated).hasSize(2);
        assertThat(decorated[0].call("{}")).isEqualTo("a");
        assertThat(decorated[1].call("{}")).isEqualTo("b");
        assertThat(decorated[0].getToolDefinition().name()).isEqualTo("adhocQuery");
    }

    @Test
    void PII脱敏保留定位信息() {
        assertThat(ToolCallbackDecorator.maskPii("{\"mobile\":\"13812345678\"}"))
                .isEqualTo("{\"mobile\":\"138****5678\"}");
        assertThat(ToolCallbackDecorator.maskPii("{\"id\":\"110101199003071234\"}"))
                .isEqualTo("{\"id\":\"110101********1234\"}");
        assertThat(ToolCallbackDecorator.maskPii("{\"serial\":\"1234567890123456\"}"))
                .isEqualTo("{\"serial\":\"1234****3456\"}");
        // 门店码、日期等短数字保持原样
        assertThat(ToolCallbackDecorator.maskPii("{\"store\":\"04010105\",\"date\":\"20260101\"}"))
                .isEqualTo("{\"store\":\"04010105\",\"date\":\"20260101\"}");
        assertThat(ToolCallbackDecorator.maskPii(null)).isEmpty();
    }

    private List<ILoggingEvent> errorEvents() {
        return appender.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.ERROR)).toList();
    }

    /** 可控行为的工具桩：默认直接回显入参，可替换为真实解析逻辑或抛异常 */
    private static final class StubCallback implements ToolCallback {

        private final java.util.function.Function<String, Object> behavior;
        private String lastInput;

        private StubCallback(java.util.function.Function<String, Object> behavior) {
            this.behavior = behavior;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return DEFINITION;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return METADATA;
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            this.lastInput = toolInput;
            return String.valueOf(behavior.apply(toolInput));
        }
    }
}

package com.itzixi.controller;

import com.itzixi.service.ChatService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 连通性测试 & 非流式 ChatClient 探针 Controller。
 * 用于开发阶段验证 ChatClient 自动配置、模型连通、流式能力是否正常。
 *
 * @author oliver
 * @since 1.0.0
 */
@RestController
@RequestMapping("/hello")
public class HelloController {

    private final ChatService chatService;

    public HelloController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/world")
    public String world() {
        return "Hello, World!";
    }

    /** ChatClient 基础连通性测试（非流式）。 */
    @GetMapping("/chat")
    public String chat(@RequestParam String msg) {
        return chatService.chatTest(msg);
    }

    /** ChatClient 流式返回完整 ChatResponse 测试。 */
    @GetMapping("/chat/stream/response")
    public Flux<ChatResponse> chatStreamResponse(@RequestParam String msg, HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        return chatService.streamResponse(msg);
    }

    /** ChatClient 流式返回 String 测试。 */
    @GetMapping("/chat/stream/str")
    public Flux<String> chatStreamStr(@RequestParam String msg, HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        return chatService.streamStr(msg);
    }
}

package com.itzixi.controller;

import com.itzixi.enums.SSEMsgType;
import com.itzixi.service.ChatService;
import com.itzixi.utils.ChatSessionKeyUtils;
import com.itzixi.utils.SSEServer;
import lombok.SneakyThrows;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE（Server-Sent Events）通道 Controller。
 * <p>
 * 负责前端 Web 客户端与后端 SSEServer 的连接建立、消息发送测试、连接管理。
 * 当前 SSE 消息通道按 {@code userId:sessionId} 分通道，支持多会话隔离。
 * </p>
 *
 * @author oliver
 * @since 1.0.0
 */
@RestController
@RequestMapping("/sse")
public class SSEController {

    private final ChatService chatService;

    private final SSEServer sseServer;

    public SSEController(ChatService chatService, SSEServer sseServer) {
        this.chatService = chatService;
        this.sseServer = sseServer;
    }

    /**
     * 建立 SSE 连接 —— 前端进入聊天页时调用。
     *
     * @param userId    用户标识（匿名时为 localStorage 生成的 UUID）
     * @param sessionId 会话标识（可选，不传则按 userId 单通道）
     * @return SseEmitter 实例（Spring MVC 自动异步保持 HTTP 连接）
     */
    @GetMapping(path = "/connect", produces = {MediaType.TEXT_EVENT_STREAM_VALUE})
    public SseEmitter connect(@RequestParam String userId,
                              @RequestParam(required = false) String sessionId) {
        String channelKey = ChatSessionKeyUtils.resolveChannelKey(userId, sessionId);
        return sseServer.connect(channelKey);
    }

    /** 向指定 userId 发送单条消息（测试用）。 */
    @GetMapping("/sendMessage")
    public String sendMessage(@RequestParam String userId, @RequestParam String message) {
        sseServer.sendMsg(userId, message, SSEMsgType.MESSAGE);
        return "ok";
    }

    /** 向指定 userId 发送 10 条增量消息（模拟流式输出测试用）。 */
    @GetMapping("/sendMessageAdd")
    @SneakyThrows
    public String sendMessageAdd(@RequestParam String userId, @RequestParam String message) {
        for (int i = 0; i < 10; i++) {
            Thread.sleep(200L);
            sseServer.sendMsg(userId, message, SSEMsgType.ADD);
        }
        return "ok";
    }

    /** 向所有已连接用户广播消息（测试用）。 */
    @GetMapping("/sendMessageAll")
    public String sendMessageAll(@RequestParam String message) {
        sseServer.sendMsgToAllUsers(message);
        return "ok";
    }
}

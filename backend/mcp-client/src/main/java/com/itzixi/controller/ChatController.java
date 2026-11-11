package com.itzixi.controller;

import com.itzixi.entity.ChatEntity;
import com.itzixi.service.ChatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 聊天入口 Controller — 接前端 POST 请求，转发给 {@link ChatService#doChat}。
 *
 * @author oliver
 * @since 1.0.0
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 问数 CRM 流程入口（流式返回 + 分布式锁并发保护）。
     */
    @PostMapping("/doChat")
    public void doChat(@RequestBody ChatEntity chatEntity) {
        chatService.doChat(chatEntity);
    }
}

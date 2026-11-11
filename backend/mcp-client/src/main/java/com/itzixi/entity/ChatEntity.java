package com.itzixi.entity;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author oliver
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatEntity {
    private String currentUserName;
    private String message;
    private String botMsgId;
    private String modelName;
    /**
     * 前端生成的会话ID（UUID），用于隔离不同标签页/会话的对话记忆与 SSE 通道。
     * 可为空，为空时按 legacy 规则回退。
     */
    private String sessionId;
}

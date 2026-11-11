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
public class ChatResponseEntity {
    private String message;
    private String botMsgId;
    /**
     * 会话ID，随 finish 事件回传，供前端校验事件归属。
     */
    private String sessionId;
}

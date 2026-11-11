package com.itzixi.enums;


import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @Author oliver
 */
@Getter
@AllArgsConstructor
public enum SSEMsgType {
    MESSAGE("message", "单次发送的普通类型消息"),
    ADD("add", "消息追加,适用于流式stream推送"),
    FINISH("finish", "消息完成"),
    CUSTOM_EVENT("custom_event", "自定义事件"),
    DONE("done", "消息完成"),

    ;

    private final String type;
    private final String desc;
}

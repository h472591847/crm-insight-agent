package com.itzixi.entity.request;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * @Author oliver
 */
@Data
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class EmailRequest {
    /**
     * 邮箱地址
     */
    @ToolParam(description = "收件人邮箱")
    private String email;

    /**
     * 邮件标题
     */
    @ToolParam(description = "发送邮件的标题/主题")
    private String subject;

    /**
     * 邮件内容
     */
    @ToolParam(description = "发送邮件的消息/正文内容")
    private String message;

    @ToolParam(description = "邮件的内容是html格式？还是markdown格式？如果是markdown格式，则为1，如果是html格式，则为2，其他格式为0")
    private Integer contentType;
}

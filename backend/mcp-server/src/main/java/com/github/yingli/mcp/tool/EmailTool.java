package com.github.yingli.mcp.tool;

import com.github.yingli.entity.request.EmailRequest;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * 邮件发送 MCP 工具。
 * <p>
 * 通过 JavaMailSender 发送邮件，支持纯文本、HTML、markdown 三种格式（由 contentType 字段区分）：
 * <ul>
 *     <li>{@code contentType=0} — 纯文本</li>
 *     <li>{@code contentType=1} — markdown → HTML（内部转换）</li>
 *     <li>{@code contentType=2} — 直接 HTML</li>
 * </ul>
 * 所有异常不吞掉：发送失败返回结构化错误结果，让上层模型知道是否需要重试或降级。
 * </p>
 *
 * @author oliver
 * @since 1.0.0
 */
@Component
@Slf4j
public class EmailTool {

    private final JavaMailSender mailSender;
    private final String from;

    private static final String RESULT_SUCCESS = "OK";

    /**
     * Spring 4.3+ 单构造器自动注入，无需 @Autowired。
     *
     * @param mailSender Spring Boot 自动配置的邮件发送器
     * @param from       发件人地址（从 spring.mail.username 配置读取）
     */
    public EmailTool(JavaMailSender mailSender, @Value("${spring.mail.username}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    /**
     * 发送邮件。
     *
     * @param emailRequest 邮件请求体（收件人、标题、内容、格式类型）
     * @return 成功返回 "OK"；失败返回错误原因字符串（模型可据此重试或降级）
     */
    @Tool(name = "sendMailMessage",
            description = "为指定邮箱发送邮件信息。email 为收件人邮箱，subject 为邮件标题，message 为邮件内容，"
                    + "contentType=0 纯文本、=1 markdown、=2 HTML。发送失败返回错误原因字符串。")
    public String sendMailMessage(EmailRequest emailRequest) {
        log.info("调用 MCP 工具 sendMailMessage, emailRequest={}", emailRequest);

        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage);

            helper.setFrom(from);
            helper.setTo(emailRequest.getEmail());
            helper.setSubject(emailRequest.getSubject());
            helper.setText(resolveContent(emailRequest.getMessage(), emailRequest.getContentType()),
                    emailRequest.getContentType() == 1 || emailRequest.getContentType() == 2);

            mailSender.send(mimeMessage);
            log.info("邮件发送成功, to={}, subject={}", emailRequest.getEmail(), emailRequest.getSubject());
            return RESULT_SUCCESS;
        } catch (MessagingException e) {
            log.error("邮件发送失败, to={}, subject={}, error={}",
                    emailRequest.getEmail(), emailRequest.getSubject(), e.getMessage(), e);
            return "发送失败：" + e.getMessage();
        } catch (Exception e) {
            // 兜底：JavaMailSender.send 可能抛 RuntimeException
            log.error("邮件发送异常, to={}, subject={}, error={}",
                    emailRequest.getEmail(), emailRequest.getSubject(), e.getMessage(), e);
            return "发送异常：" + e.getMessage();
        }
    }

    /**
     * 返回当前配置的发件人邮箱地址（供模型确认身份）。
     *
     * @return 发件人邮箱
     */
    @Tool(name = "getMyEmailAddress", description = "查询我的邮件/邮箱地址（发件人）")
    public String getMyEmailAddress() {
        log.info("调用 MCP 工具 getMyEmailAddress");
        return from;
    }

    // ==================== 内部工具方法 ====================

    /**
     * 根据 contentType 解析最终要发送的内容。
     *
     * @param content     原始内容
     * @param contentType 0=纯文本 / 1=markdown→HTML / 2=直接 HTML
     * @return 最终内容（HTML 或纯文本）
     */
    private static String resolveContent(String content, Integer contentType) {
        if (contentType != null && contentType == 1) {
            return markdownToHtml(content);
        }
        return content;
    }

    /**
     * Markdown → HTML（flexmark 默认配置）。
     *
     * @param markdown markdown 文本
     * @return 渲染后的 HTML
     */
    private static String markdownToHtml(String markdown) {
        MutableDataSet options = new MutableDataSet();
        Parser parser = Parser.builder(options).build();
        HtmlRenderer renderer = HtmlRenderer.builder(options).build();
        Node document = parser.parse(markdown);
        return renderer.render(document);
    }
}

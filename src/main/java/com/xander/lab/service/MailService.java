package com.xander.lab.service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Shared mail delivery service for verification codes and system notifications.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private final JavaMailSender mailSender;

    @Value("${mail.from}")
    private String from;

    @Value("${email-reminder.display-zone:Asia/Shanghai}")
    private String reminderDisplayZone;

    public void sendSimpleMail(String to, String subject, String content) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(content);
            mailSender.send(message);
            log.info("[Mail] 邮件发送成功：{} -> {}", from, to);
        } catch (Exception e) {
            log.error("[Mail] 邮件发送失败：{}", e.getMessage());
            throw new RuntimeException("邮件发送失败，请稍后重试", e);
        }
    }

    public void sendVerificationCode(String email, String code) {
        String subject = "【Xander Lab】登录验证码";
        String content = "您的登录验证码为：" + code + "，有效期为 5 分钟。请勿泄露给他人。";
        sendSimpleMail(email, subject, content);
    }

    /**
     * Sends a branded HTML reminder. User-controlled values are escaped before
     * being inserted into HTML, while a plain-text alternative is included for
     * clients that do not render HTML.
     */
    public void sendReminderMail(String to, String subject, String userMessage, Instant scheduledAt) {
        try {
            ZoneId displayZone = ZoneId.of(reminderDisplayZone);
            String formattedTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(displayZone)
                    .format(scheduledAt);
            String html = buildReminderHtml(subject, userMessage, formattedTime, displayZone.getId());
            String plainText = "Xander Lab 定时提醒\n\n"
                    + userMessage
                    + "\n\n计划发送时间：" + formattedTime + " (" + displayZone.getId() + ")";

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message,
                    MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                    StandardCharsets.UTF_8.name()
            );
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(plainText, html);
            mailSender.send(message);
            log.info("[Mail] 定时提醒发送成功：task recipient={}", to);
        } catch (Exception e) {
            log.error("[Mail] 定时提醒发送失败：recipient={}, reason={}", to, e.getMessage());
            throw new RuntimeException("定时提醒邮件发送失败", e);
        }
    }

    private String buildReminderHtml(String subject, String userMessage,
                                     String formattedTime, String displayZone) {
        String escapedSubject = HtmlUtils.htmlEscape(subject);
        String escapedTime = HtmlUtils.htmlEscape(formattedTime);
        String escapedZone = HtmlUtils.htmlEscape(displayZone);
        String escapedMessage = HtmlUtils.htmlEscape(userMessage)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\n", "<br>");

        StringBuilder html = new StringBuilder(4096);
        html.append("<!doctype html><html lang=\"zh-CN\"><head>")
                .append("<meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(escapedSubject).append("</title></head>")
                .append("<body style=\"margin:0;padding:0;background:#f4f7fb;color:#172033;")
                .append("font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" ")
                .append("style=\"background:#f4f7fb;padding:32px 12px;\"><tr><td align=\"center\">")
                .append("<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" ")
                .append("style=\"max-width:620px;background:#ffffff;border-radius:20px;overflow:hidden;")
                .append("box-shadow:0 16px 40px rgba(31,55,90,.10);\">")
                .append("<tr><td style=\"padding:30px 34px;background:linear-gradient(135deg,#172554,#2563eb);color:#fff;\">")
                .append("<div style=\"font-size:13px;letter-spacing:2px;opacity:.82;\">XANDER LAB</div>")
                .append("<h1 style=\"margin:10px 0 0;font-size:26px;line-height:1.35;\">")
                .append(escapedSubject).append("</h1></td></tr>")
                .append("<tr><td style=\"padding:34px;\">")
                .append("<div style=\"font-size:13px;font-weight:700;color:#2563eb;letter-spacing:.08em;\">想对你说</div>")
                .append("<div style=\"margin-top:14px;padding:22px;background:#f8fafc;border:1px solid #e2e8f0;")
                .append("border-radius:14px;font-size:16px;line-height:1.85;color:#263348;word-break:break-word;\">")
                .append(escapedMessage).append("</div>")
                .append("<div style=\"margin-top:22px;padding-top:20px;border-top:1px solid #e8edf5;")
                .append("font-size:13px;line-height:1.7;color:#7b879b;\">")
                .append("这是你在 Xander Lab 设置的定时邮件提醒。<br>")
                .append("计划发送时间：").append(escapedTime).append("（").append(escapedZone).append("）")
                .append("</div></td></tr></table>")
                .append("<div style=\"margin-top:18px;font-size:12px;color:#98a2b3;\">")
                .append("由 Xander Lab 定时提醒服务发送</div>")
                .append("</td></tr></table></body></html>");
        return html.toString();
    }
}

package com.xander.lab.service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

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
        String subject = LoginVerificationMailTemplates.buildSubject();
        String plainText = LoginVerificationMailTemplates.buildPlainText(code);
        String html = LoginVerificationMailTemplates.buildHtml(code);
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message,
                    MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                    StandardCharsets.UTF_8.name()
            );
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject(subject);
            helper.setText(plainText, html);
            mailSender.send(message);
            log.info("[Mail] 登录验证码发送成功：{} -> {}", from, email);
        } catch (Exception e) {
            log.error("[Mail] 登录验证码发送失败：{}", e.getMessage());
            throw new RuntimeException("邮件发送失败，请稍后重试", e);
        }
    }

    /**
     * PLAIN (no markup): body text only.
     * HTML: custom HTML as-is, or wrapped by a selected layout template.
     */
    public void sendReminderMail(String to, String subject, String userMessage, Instant scheduledAt) {
        sendReminderMail(
                to,
                subject,
                userMessage,
                scheduledAt,
                null,
                EmailReminderMailTemplates.TEMPLATE_NONE
        );
    }

    public void sendReminderMail(String to, String subject, String userMessage, Instant scheduledAt,
                                 String contentType, String templateId) {
        try {
            String normalizedType = EmailReminderMailTemplates.resolveEffectiveContentType(
                    contentType, templateId, userMessage
            );
            String normalizedTemplate = EmailReminderMailTemplates.normalizeTemplateId(templateId);

            if (EmailReminderMailTemplates.CONTENT_PLAIN.equals(normalizedType)) {
                String plainBody = userMessage == null ? "" : userMessage;
                sendSimpleMail(to, subject, plainBody);
                log.info("[Mail] 定时提醒发送成功：task recipient={}, contentType=PLAIN", to);
                return;
            }

            ZoneId displayZone = ZoneId.of(reminderDisplayZone);
            String formattedTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(displayZone)
                    .format(scheduledAt);
            String bodyHtml = EmailReminderMailTemplates.resolveBodyHtml(userMessage, normalizedType);
            String html = EmailReminderMailTemplates.buildHtml(
                    normalizedTemplate,
                    subject,
                    bodyHtml,
                    formattedTime,
                    displayZone.getId()
            );
            String plainText = EmailReminderMailTemplates.resolvePlainText(
                    userMessage,
                    normalizedType,
                    EmailReminderMailTemplates.TEMPLATE_NONE.equals(normalizedTemplate)
                            ? ""
                            : formattedTime,
                    EmailReminderMailTemplates.TEMPLATE_NONE.equals(normalizedTemplate)
                            ? ""
                            : displayZone.getId()
            );

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
            log.info("[Mail] 定时提醒发送成功：task recipient={}, contentType=HTML, template={}",
                    to, normalizedTemplate);
        } catch (Exception e) {
            log.error("[Mail] 定时提醒发送失败：recipient={}, reason={}", to, e.getMessage());
            throw new RuntimeException("定时提醒邮件发送失败", e);
        }
    }
}

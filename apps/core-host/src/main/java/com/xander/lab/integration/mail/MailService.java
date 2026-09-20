package com.xander.lab.integration.mail;

import com.xander.lab.integration.api.MailPort;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * Mail delivery. Implements {@link MailPort} for callers in other deployment units.
 *
 * <p>⚠️ 这里<b>只做投递</b>，不做渲染。提醒邮件的模板、显示时区格式化与 PLAIN/HTML 归一化
 * 全部在调用方一侧（{@code automation.domain.EmailReminderMailRenderer}）。
 * 它们若住在这个类里，本类就必须 import
 * {@code automation.domain.EmailReminderMailTemplates}（业务域类型）、
 * 并读 {@code email-reminder.display-zone}（配置台账里 owner 记在 content-automation 名下的键）
 * ——提供方反过来依赖消费方，而且读的是别人的配置。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService implements MailPort {

    private final JavaMailSender mailSender;

    @Value("${mail.from}")
    private String from;

    @Override
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

    @Override
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
     * HTML 正文 + 纯文本兜底的多段投递。
     *
     * <p>⚠️ 这里<b>不认识模板、也不认识时区</b>——{@code plainText} 与 {@code html}
     * 都是调用方渲染好的成品。若这个位置收的是
     * {@code sendReminderMail(..., scheduledAt, contentType, templateId)}，
     * 六个参数里四个只服务于渲染，而渲染需要业务域的模板类与别人家的配置键。</p>
     */
    @Override
    public void sendHtmlMail(String to, String subject, String plainText, String html) {
        try {
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
            log.info("[Mail] HTML 邮件发送成功：{} -> {}", from, to);
        } catch (Exception e) {
            log.error("[Mail] HTML 邮件发送失败：{}", e.getMessage());
            throw new RuntimeException("邮件发送失败，请稍后重试", e);
        }
    }
}

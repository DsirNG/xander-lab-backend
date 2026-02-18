
package com.xander.lab.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 邮件服务
 * 用于发送登录验证码和系统通知
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String from;

    /**
     * 发送简单文本邮件
     *
     * @param to      接收者
     * @param subject 主题
     * @param content 内容
     */
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
            throw new RuntimeException("邮件发送失败，请稍后重试");
        }
    }

    /**
     * 发送登录验证码
     *
     * @param email 邮箱地址
     * @param code  六位验证码
     */
    public void sendVerificationCode(String email, String code) {
        String subject = "【Xander Lab】登录验证码";
        String content = "您的登录验证码为：" + code + "，有效期为 5 分钟。请勿泄露给他人。";
        sendSimpleMail(email, subject, content);
    }
}

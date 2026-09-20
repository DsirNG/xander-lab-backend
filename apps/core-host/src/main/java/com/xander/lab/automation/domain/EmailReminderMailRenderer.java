package com.xander.lab.automation.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 把一条提醒任务渲染成"要投递的内容"。
 *
 * <h2>为什么渲染在 automation，而不是在邮件服务里</h2>
 * <p>这段逻辑若住在 {@code integration.mail.MailService.sendReminderMail(...)}，
 * 于是那个类必须 import 本域的 {@link EmailReminderMailTemplates}、并读
 * {@code email-reminder.display-zone}（配置台账里 owner 记在 {@code content-automation}
 * 名下的键）。结果是<b>提供方反过来依赖消费方</b>，而且读的是别人家的配置。</p>
 *
 * <p>判据很简单：<b>这封提醒长什么样</b>是 automation 的业务概念
 * （{@code EmailReminderTask} / {@code EmailReminderService} / {@code EmailReminderScheduler}
 * 全在本域）；<b>把它投出去</b>才是 integration 的能力。所以渲染留在本域，
 * 邮件端口只收成品。</p>
 *
 * <h2>纯函数</h2>
 * <p>没有字段、没有 Spring 依赖、不读配置——时区 id 由调用方传入。这样它可以被直接单测，
 * 不需要起 {@code JavaMailSender}。</p>
 */
public final class EmailReminderMailRenderer {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 渲染结果。
     *
     * @param html      {@code false} 表示这封邮件是<b>纯文本</b>（此时 {@code htmlBody} 是空串，
     *                  调用方必须走纯文本投递，不要拿空 {@code htmlBody} 去发 HTML）
     * @param plainText 纯文本正文
     * @param htmlBody  HTML 正文；{@code html} 为 {@code false} 时为空串
     */
    public record Rendered(boolean html, String plainText, String htmlBody) {
    }

    private EmailReminderMailRenderer() {
    }

    /**
     * 按任务的内容类型与模板 id 渲染。
     *
     * <p>内容类型的判定沿用 {@link EmailReminderMailTemplates#resolveEffectiveContentType}：
     * 显式 {@code contentType} 优先；否则正文里有标记、或选了版式模板时按 HTML 处理。
     * 也就是说<b>"看起来像 HTML"也算 HTML</b>，这不是本方法新引入的规则。</p>
     *
     * @param subject       邮件主题（渲染进 HTML 标题）
     * @param message       用户填写的正文
     * @param contentType   用户请求里的内容类型，可为 {@code null}
     * @param templateId    用户请求里的版式 id，可为 {@code null}
     * @param scheduledAt   计划发送时刻（渲染进 HTML 的页脚）
     * @param displayZoneId 展示用时区 id，来自 {@code email-reminder.display-zone}
     */
    public static Rendered render(String subject,
                                  String message,
                                  String contentType,
                                  String templateId,
                                  Instant scheduledAt,
                                  String displayZoneId) {
        String normalizedType = EmailReminderMailTemplates.resolveEffectiveContentType(
                contentType, templateId, message);
        String normalizedTemplate = EmailReminderMailTemplates.normalizeTemplateId(templateId);

        if (EmailReminderMailTemplates.CONTENT_PLAIN.equals(normalizedType)) {
            return new Rendered(false, message == null ? "" : message, "");
        }

        // ⚠️ 为什么这里收的是 String 而不是 ZoneId：解析必须留在 HTML 分支**里面**。
        // 纯文本提醒根本不需要时区，`email-reminder.display-zone` 配错时不该连带把纯文本
        // 提醒也打死。ZoneId.of 必须留在这个位置（HTML 分支内）。
        ZoneId displayZone = ZoneId.of(displayZoneId);
        String formattedTime = TIME_FORMAT.withZone(displayZone).format(scheduledAt);
        String bodyHtml = EmailReminderMailTemplates.resolveBodyHtml(message, normalizedType);
        String html = EmailReminderMailTemplates.buildHtml(
                normalizedTemplate,
                subject,
                bodyHtml,
                formattedTime,
                displayZone.getId()
        );
        // ⚠️ 选了 "none"（不要版式）时，纯文本兜底里不带时间与时区 ——
        // 那两行本来就是"版式模板才会渲染的页脚"。
        boolean wrapped = !EmailReminderMailTemplates.TEMPLATE_NONE.equals(normalizedTemplate);
        String plainText = EmailReminderMailTemplates.resolvePlainText(
                message,
                normalizedType,
                wrapped ? formattedTime : "",
                wrapped ? displayZone.getId() : ""
        );
        return new Rendered(true, plainText, html);
    }
}

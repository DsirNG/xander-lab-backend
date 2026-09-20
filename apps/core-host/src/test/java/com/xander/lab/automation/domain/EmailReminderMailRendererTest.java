package com.xander.lab.automation.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EmailReminderMailRenderer} 的行为。
 *
 * <p>这段逻辑是从 {@code integration.mail.MailService.sendReminderMail} 原样搬过来的，
 * 搬的时候<b>一条断言都没带过来</b> —— 那个方法此前零测试（它只被调度器间接走到，
 * 而调度器的测试用 mock 把邮件服务整个换掉了）。所以这些用例既是新类的守卫，
 * 也是那段渲染逻辑<b>第一次</b>被钉住。</p>
 *
 * <p>⚠️ 与"一份实体两份投影必须配跨投影断言"同源：<b>东西搬走了，守卫也要跟着搬</b>，
 * 否则搬完就没人看了。</p>
 */
class EmailReminderMailRendererTest {

    private static final Instant SCHEDULED_AT = Instant.parse("2026-07-24T10:30:00Z");
    private static final String ZONE = "Asia/Shanghai";
    /** {@link #SCHEDULED_AT} 在上海时区是 18:30。 */
    private static final String FORMATTED_TIME = "2026-07-24 18:30";

    @Test
    void plainMessageWithoutMarkupIsRenderedAsPlainText() {
        EmailReminderMailRenderer.Rendered rendered =
                EmailReminderMailRenderer.render("提醒", "买牛奶", null, null, SCHEDULED_AT, ZONE);

        assertFalse(rendered.html(), "正文里没有标记、也没选版式 ⇒ 纯文本");
        assertTrue(rendered.plainText().contains("买牛奶"));
        assertTrue(rendered.htmlBody().isEmpty(), "纯文本路径不该产出 HTML 正文");
    }

    /** 显式 {@code contentType} 优先于"看起来像 HTML"：用户说了 PLAIN 就是 PLAIN。 */
    @Test
    void explicitPlainWinsOverMarkupInTheBody() {
        EmailReminderMailRenderer.Rendered rendered =
                EmailReminderMailRenderer.render("提醒", "<b>买牛奶</b>", "PLAIN", null, SCHEDULED_AT, ZONE);

        assertFalse(rendered.html());
        assertTrue(rendered.plainText().contains("<b>买牛奶</b>"), "PLAIN 要原样发出去，不做标签剥离");
    }

    /** 选了版式模板 ⇒ HTML，且时间与展示时区都渲染进去。 */
    @Test
    void selectedTemplateRendersHtmlWithTimeAndDisplayZone() {
        EmailReminderMailRenderer.Rendered rendered =
                EmailReminderMailRenderer.render("提醒", "买牛奶", null, "classic", SCHEDULED_AT, ZONE);

        assertTrue(rendered.html());
        assertTrue(rendered.htmlBody().contains("提醒"), "主题要进 HTML 标题");
        assertTrue(rendered.htmlBody().contains("买牛奶"));
        assertTrue(rendered.htmlBody().contains(FORMATTED_TIME), "计划发送时刻要进页脚");
        assertTrue(rendered.htmlBody().contains(ZONE), "展示时区要进页脚");
        assertTrue(rendered.plainText().contains(FORMATTED_TIME), "纯文本兜底也要带时间");
    }

    /** 没选版式但正文里有标记 ⇒ 也走 HTML，只是不套外框。 */
    @Test
    void markupInTheBodyAloneAlsoRendersHtml() {
        EmailReminderMailRenderer.Rendered rendered =
                EmailReminderMailRenderer.render("提醒", "<p>买牛奶</p>", null, null, SCHEDULED_AT, ZONE);

        assertTrue(rendered.html());
        assertTrue(rendered.htmlBody().contains("买牛奶"));
    }

    /**
     * ⭐ 选了 {@code "none"}（不要版式）时，<b>纯文本兜底里不带时间与时区</b>。
     *
     * <p>这两行本来就是"版式模板才会渲染的页脚"（
     * {@code TEMPLATE_NONE.equals(normalizedTemplate) ? "" : formattedTime}）。
     * 搬家时最容易被"顺手统一"掉的一处，所以专门钉一条。</p>
     */
    @Test
    void noneTemplateKeepsTimeOutOfThePlainTextFallback() {
        EmailReminderMailRenderer.Rendered rendered =
                EmailReminderMailRenderer.render("提醒", "<p>买牛奶</p>", null, "none", SCHEDULED_AT, ZONE);

        assertTrue(rendered.html());
        assertTrue(rendered.htmlBody().contains("买牛奶"));
        assertFalse(rendered.plainText().contains(FORMATTED_TIME),
                "选了 none 就不套版式，纯文本兜底里不该凭空多出一行计划发送时间");
    }

    /**
     * ⚠️ 时区只在 HTML 分支里解析 —— {@code display-zone} 配错不该连带把纯文本提醒打死。
     *
     * <p>时区解析必须在 HTML 分支内（{@code ZoneId.of} 就在那里），
     * 也是本类收 {@code String displayZoneId} 而不是 {@code ZoneId} 的<b>唯一</b>理由：
     * 收 {@code ZoneId} 的话，调用方必须在调 render 之前就解析，纯文本提醒会跟着一起炸。</p>
     */
    @Test
    void invalidDisplayZoneOnlyBreaksTheHtmlPath() {
        EmailReminderMailRenderer.Rendered rendered =
                EmailReminderMailRenderer.render("提醒", "买牛奶", "PLAIN", null, SCHEDULED_AT, "Mars/Olympus");

        assertFalse(rendered.html(), "纯文本路径根本用不到时区，非法时区不该影响它");

        assertThrows(RuntimeException.class, () ->
                EmailReminderMailRenderer.render("提醒", "买牛奶", null, "classic", SCHEDULED_AT, "Mars/Olympus"));
    }
}

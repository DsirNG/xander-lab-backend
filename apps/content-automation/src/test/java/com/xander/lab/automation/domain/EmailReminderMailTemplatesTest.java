package com.xander.lab.automation.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailReminderMailTemplatesTest {

    @Test
    void plainResolveBodyEscapesMarkup() {
        String body = EmailReminderMailTemplates.resolveBodyHtml("<b>hi</b>\nnext", "PLAIN");
        assertThat(body).isEqualTo("&lt;b&gt;hi&lt;/b&gt;<br>next");
    }

    @Test
    void plainTextBodyHasNoBrandingChrome() {
        String plain = EmailReminderMailTemplates.resolvePlainText(
                "只发正文",
                "PLAIN",
                "2026-07-25 15:00",
                "Asia/Shanghai"
        );
        assertThat(plain).isEqualTo("只发正文");
        assertThat(plain).doesNotContain("Xander Lab");
        assertThat(plain).doesNotContain("计划发送时间");
    }

    @Test
    void htmlContentKeepsMarkupAndStripsScript() {
        String body = EmailReminderMailTemplates.resolveBodyHtml(
                "<p>hello</p><script>alert(1)</script><img src=x onerror=alert(1)>",
                "HTML"
        );
        assertThat(body).contains("<p>hello</p>");
        assertThat(body).doesNotContain("<script");
        assertThat(body).doesNotContain("onerror");
    }

    @Test
    void noneTemplateBuildsRawHtmlWithoutBrandHeader() {
        String html = EmailReminderMailTemplates.buildHtml(
                "none",
                "Subject",
                "<strong>自定义</strong>",
                "2026-07-25 15:00",
                "Asia/Shanghai"
        );
        assertThat(html).contains("<strong>自定义</strong>");
        assertThat(html).doesNotContain("XANDER LAB");
        assertThat(html).doesNotContain("晨光");
    }

    @Test
    void blankTemplateDefaultsToNone() {
        assertThat(EmailReminderMailTemplates.normalizeTemplateId(null))
                .isEqualTo(EmailReminderMailTemplates.TEMPLATE_NONE);
        assertThat(EmailReminderMailTemplates.resolveEffectiveContentType(null, "none", "<b>x</b>"))
                .isEqualTo("HTML");
        assertThat(EmailReminderMailTemplates.resolveEffectiveContentType(null, "none", "plain only"))
                .isEqualTo("PLAIN");
    }

    @Test
    void buildHtmlUsesSelectedTemplate() {
        String html = EmailReminderMailTemplates.buildHtml(
                "notice",
                "Subject",
                "<strong>Body</strong>",
                "2026-07-25 15:00",
                "Asia/Shanghai"
        );
        assertThat(html).contains("NOTICE");
        assertThat(html).contains("<strong>Body</strong>");
        assertThat(html).contains("Subject");
    }
}



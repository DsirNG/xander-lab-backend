package com.xander.lab.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailReminderMailTemplatesTest {

    @Test
    void plainContentIsEscapedInsideTemplate() {
        String body = EmailReminderMailTemplates.resolveBodyHtml("<b>hi</b>\nnext", "PLAIN");
        assertThat(body).isEqualTo("&lt;b&gt;hi&lt;/b&gt;<br>next");
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

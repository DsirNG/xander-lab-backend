package com.xander.lab.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginVerificationMailTemplatesTest {

    @Test
    void buildHtmlHighlightsPlatformAndCode() {
        String html = LoginVerificationMailTemplates.buildHtml("384921");

        assertThat(html).contains("XANDER LAB");
        assertThat(html).contains("登录验证码");
        assertThat(html).contains("安全提示");
        assertThat(html).contains("xl-code-float");
        assertThat(html).contains("xl-digit");
        assertThat(html).contains("3", "8", "4", "9", "2", "1");
        assertThat(LoginVerificationMailTemplates.buildSubject()).contains("Xander Lab");
        assertThat(LoginVerificationMailTemplates.buildPlainText("384921"))
                .contains("384921")
                .contains("安全提醒");
    }

    @Test
    void buildHtmlEscapesCodeContent() {
        String html = LoginVerificationMailTemplates.buildHtml("<script>");
        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;");
    }
}

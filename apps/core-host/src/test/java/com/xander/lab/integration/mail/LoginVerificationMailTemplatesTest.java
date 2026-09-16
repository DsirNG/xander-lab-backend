package com.xander.lab.integration.mail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginVerificationMailTemplatesTest {

    @Test
    void buildHtmlRendersSimpleCodeMail() {
        String html = LoginVerificationMailTemplates.buildHtml("384921");

        assertThat(html).contains("DinQorAI");
        assertThat(html).contains("登录验证码");
        assertThat(html).contains("长按或选中上方数字即可复制验证码");
        assertThat(html).doesNotContain("@keyframes");
        assertThat(html).doesNotContain("linear-gradient");
        assertThat(html).contains("3", "8", "4", "9", "2", "1");
        assertThat(LoginVerificationMailTemplates.buildSubject()).contains("DinQorAI");
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

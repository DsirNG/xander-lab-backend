package com.xander.lab.integration.mcp.csdn.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CsdnImageHtmlRewriterTest {

    @Test
    void rewriteReplacesRemoteImageSources() {
        CsdnImageStorageClient client = (cookie, apiKey, nonce, signature, timestamp, imageUrl, articleId) ->
                "https://csdn.example.com/" + articleId + "/rewritten.png";
        CsdnProperties properties = new CsdnProperties();
        properties.setWebConsoleSecret("test-secret-that-is-long-enough");
        CsdnImageHtmlRewriter rewriter = new CsdnImageHtmlRewriter(client, properties);

        String html = "<p>before</p><img src=\"https://example.com/a.png\" alt=\"a\"><p>after</p>";

        String rewritten = rewriter.rewrite(html, "cookie", "apiKey", "123", "https://dinqor.cn/blog/123");

        assertThat(rewritten).contains("<a href=\"https://dinqor.cn/blog/123\" target=\"_blank\">原文链接</a>");
        assertThat(rewritten).contains("https://csdn.example.com/123/rewritten.png");
        assertThat(rewritten).doesNotContain("https://example.com/a.png");
    }
}

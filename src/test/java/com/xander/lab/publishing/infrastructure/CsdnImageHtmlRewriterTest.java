package com.xander.lab.publishing.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CsdnImageHtmlRewriterTest {

    @Test
    void rewriteReplacesRemoteImageSources() {
        CsdnImageStorageClient client = (cookie, apiKey, nonce, signature, timestamp, imageUrl, articleId) ->
                "https://csdn.example.com/" + articleId + "/rewritten.png";
        CsdnImageHtmlRewriter rewriter = new CsdnImageHtmlRewriter(client);

        String html = "<p>before</p><img src=\"https://example.com/a.png\" alt=\"a\"><p>after</p>";

        String rewritten = rewriter.rewrite(html, "cookie", "apiKey", "123", "https://xander.dsircity.top/blog/123");

        assertThat(rewritten).contains("<a href=\"https://xander.dsircity.top/blog/123\" target=\"_blank\">原文链接</a>");
        assertThat(rewritten).contains("https://csdn.example.com/123/rewritten.png");
        assertThat(rewritten).doesNotContain("https://example.com/a.png");
    }
}

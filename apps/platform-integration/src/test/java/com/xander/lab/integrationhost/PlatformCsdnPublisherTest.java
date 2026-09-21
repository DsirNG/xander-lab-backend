package com.xander.lab.integration.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.integration.api.BlogSyncResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformCsdnPublisherTest {

    @Test
    void convertsMarkdownAddsBacklinkAndReturnsCsdnResult() throws Exception {
        PlatformCsdnCredentialStore credentials = mock(PlatformCsdnCredentialStore.class);
        PlatformCsdnClient client = mock(PlatformCsdnClient.class);
        when(credentials.requireCookie(7L)).thenReturn("cookie");
        when(client.saveArticle(eq(new PlatformCsdnClient.Credential("cookie")), any()))
                .thenReturn(new ObjectMapper().readTree(
                        "{\"code\":200,\"data\":{\"article_id\":\"csdn-1\",\"url\":\"https://blog.csdn.net/a/1\"}}"));

        PlatformCsdnPublisher publisher = new PlatformCsdnPublisher(credentials, client);

        BlogSyncResult result = publisher.publish(new PlatformCsdnPublisher.PublishRequest(
                7L, 42L, "标题", "摘要", "# 小节\n\n正文内容", "前端", null,
                List.of("Java")));

        assertThat(result).isEqualTo(new BlogSyncResult(
                "csdn", "csdn-1", "https://blog.csdn.net/a/1"));
        verify(client).saveArticle(eq(new PlatformCsdnClient.Credential("cookie")),
                org.mockito.ArgumentMatchers.argThat(article ->
                        article.content().contains("https://dinqor.cn/blog/42")
                                && article.content().contains("<h1>小节</h1>")
                                && article.category().equals("前端")
                                && article.tags().equals(List.of("Java"))));
    }

    @Test
    void marksCredentialExpiredWhenCsdnRejectsSession() {
        PlatformCsdnCredentialStore credentials = mock(PlatformCsdnCredentialStore.class);
        PlatformCsdnClient client = mock(PlatformCsdnClient.class);
        when(credentials.requireCookie(7L)).thenReturn("cookie");
        doThrow(new PlatformCsdnClient.CsdnSessionException("expired"))
                .when(client).saveArticle(any(), any());
        PlatformCsdnPublisher publisher = new PlatformCsdnPublisher(credentials, client);

        assertThrows(PlatformCsdnClient.CsdnSessionException.class,
                () -> publisher.publish(new PlatformCsdnPublisher.PublishRequest(
                        7L, 42L, "标题", null, "正文", null, null, List.of())));

        verify(credentials).markExpired(7L);
    }
}

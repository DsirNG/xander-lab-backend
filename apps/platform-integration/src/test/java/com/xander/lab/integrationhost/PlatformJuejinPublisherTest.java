package com.xander.lab.integration.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.integration.api.BlogSyncResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformJuejinPublisherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createsAndPublishesDraftWithNormalizedPayload() throws Exception {
        PlatformJuejinCredentialStore credentials = mock(PlatformJuejinCredentialStore.class);
        PlatformJuejinClient client = mock(PlatformJuejinClient.class);
        PlatformJuejinClient.Credential credential =
                new PlatformJuejinClient.Credential("cookie", "csrf", "2608", "uuid");
        when(credentials.requireCredential(7L)).thenReturn(credential);
        when(client.createDraft(eq(credential), eq("标题"), any(), eq("正文内容"),
                eq("123"), eq(List.of("456")), eq("")))
                .thenReturn(objectMapper.readTree("{\"data\":{\"id\":\"draft-1\"}}"));
        when(client.publishDraft(credential, "draft-1"))
                .thenReturn(objectMapper.readTree("{\"data\":{\"article_id\":\"article-1\"}}"));

        PlatformJuejinPublisher publisher = new PlatformJuejinPublisher(
                new PlatformJuejinProperties(), credentials, client, Optional.empty());

        BlogSyncResult result = publisher.publish(new PlatformJuejinPublisher.PublishRequest(
                7L, 42L, "标题", "短摘要", "正文内容", "123", "分类", "", List.of("456")));

        assertThat(result).isEqualTo(new BlogSyncResult(
                "juejin", "article-1", "https://juejin.cn/post/article-1"));
        verify(client).publishDraft(credential, "draft-1");
    }

    @Test
    void marksCredentialExpiredWhenVendorRejectsSession() throws Exception {
        PlatformJuejinCredentialStore credentials = mock(PlatformJuejinCredentialStore.class);
        PlatformJuejinClient client = mock(PlatformJuejinClient.class);
        PlatformJuejinClient.Credential credential =
                new PlatformJuejinClient.Credential("cookie", "csrf", "2608", "uuid");
        when(credentials.requireCredential(7L)).thenReturn(credential);
        doThrow(new PlatformJuejinClient.JuejinSessionException("expired"))
                .when(client).createDraft(eq(credential), eq("标题"), any(), eq("正文内容"),
                        any(), any(), any());

        PlatformJuejinPublisher publisher = new PlatformJuejinPublisher(
                new PlatformJuejinProperties(), credentials, client, Optional.empty());

        assertThrows(PlatformJuejinClient.JuejinSessionException.class,
                () -> publisher.publish(new PlatformJuejinPublisher.PublishRequest(
                        7L, 42L, "标题", null, "正文内容", null, null, null, List.of())));

        verify(credentials).markExpired(7L);
    }

    @Test
    void briefNeverLoopsOnEmptyContent() {
        String brief = PlatformJuejinPublisher.buildBrief(null, null);

        assertThat(brief).hasSizeBetween(50, 100).startsWith("DinQorAI");
    }
}

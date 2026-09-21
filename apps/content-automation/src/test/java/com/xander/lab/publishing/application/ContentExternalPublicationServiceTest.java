package com.xander.lab.publishing.application;

import com.xander.lab.automation.infrastructure.PlatformBlogSyncClient;
import com.xander.lab.integration.api.BlogSyncResult;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentExternalPublicationServiceTest {

    @Test
    void recordsJuejinResultInContentOwnedLedger() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        PlatformBlogSyncClient platformClient = mock(PlatformBlogSyncClient.class);
        BlogSyncResult result = new BlogSyncResult(
                "juejin", "article-1", "https://juejin.cn/post/article-1");
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of());
        when(platformClient.syncJuejin(7L, 42L)).thenReturn(result);

        ContentExternalPublicationService service = new ContentExternalPublicationService(
                jdbcTemplate, redisTemplate, platformClient);

        assertThat(service.sync(7L, 42L, "juejin")).isEqualTo(result);

        verify(platformClient).syncJuejin(7L, 42L);
        verify(jdbcTemplate).update(anyString(), eq(result.externalId()), eq(result.url()),
                any(), eq(-42L), eq(7L), eq("juejin"));
    }

    @Test
    void returnsDurableResultWithoutCallingPlatformAgain() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        PlatformBlogSyncClient platformClient = mock(PlatformBlogSyncClient.class);
        BlogSyncResult result = new BlogSyncResult(
                "csdn", "csdn-1", "https://blog.csdn.net/a/1");
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of(result));

        ContentExternalPublicationService service = new ContentExternalPublicationService(
                jdbcTemplate, redisTemplate, platformClient);

        assertThat(service.sync(7L, 42L, "csdn")).isEqualTo(result);

        verify(platformClient, never()).syncCsdn(7L, 42L);
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any(), any(), any(), any());
    }
}

package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.billing.application.BillingService;
import com.xander.lab.blogagent.application.BlogAgentService;
import com.xander.lab.auth.core.UserContext;
import com.xander.lab.integration.mcp.csdn.application.CsdnBlogSyncService;
import com.xander.lab.integration.mcp.juejin.application.JuejinBlogSyncService;
import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.publishing.application.BlogService;
import com.xander.lab.content.api.PublishResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublishPostToolTest {

    @AfterEach
    void clearThreadContext() {
        UserContext.clear();
    }

    @Test
    void resolvesUserContextForCallerAndClearsItAfter() {
        BlogAgentService blogAgentService = mock(BlogAgentService.class);
        when(blogAgentService.publish(eq(9L), eq(42L)))
                .thenAnswer(invocation -> {
                    assertThat(UserContext.getUserId()).isEqualTo(42L);
                    return post(9L, "本地发布", 1);
                });
        PublishPostTool tool = new PublishPostTool(
                blogAgentService, mock(BlogService.class),
                mock(CsdnBlogSyncService.class), mock(JuejinBlogSyncService.class),
                mock(BillingService.class));
        tool.execute(42L,
                JsonNodeFactory.instance.objectNode().put("taskId", 9L), new RecordingProgress());

        assertThat(UserContext.getUserId()).isNull();
    }

    @Test
    void publishesGeneratedTaskLocallyByDefault() {
        BlogAgentService blogAgentService = mock(BlogAgentService.class);
        BlogPostVO published = post(9L, "本地发布", 1);
        when(blogAgentService.publish(eq(9L), eq(42L))).thenReturn(published);
        BillingService billing = mock(BillingService.class);

        PublishPostTool tool = new PublishPostTool(
                blogAgentService, mock(BlogService.class),
                mock(CsdnBlogSyncService.class), mock(JuejinBlogSyncService.class), billing);
        JsonNode result = tool.execute(42L,
                JsonNodeFactory.instance.objectNode().put("taskId", 9L), new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("postId").asLong()).isEqualTo(9L);
        assertThat(result.path("platform").asText()).isEqualTo("local");
        verify(blogAgentService).publish(eq(9L), eq(42L));
        verify(billing, never()).chargeFlat(any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void syncsExistingPostToCsdnAndChargesFlat() {
        BlogService blogService = mock(BlogService.class);
        BlogPostVO post = post(5L, "CSDN 同步", 1);
        post.setContent("正文");
        post.setSummary("摘要");
        post.setCategoryName("前端");
        post.setTags(List.of("java"));
        when(blogService.getManagedBlogById(42L, 5L)).thenReturn(post);
        CsdnBlogSyncService csdn = mock(CsdnBlogSyncService.class);
        when(csdn.sync(eq(42L), eq(5L))).thenReturn(new PublishResult("csdn", "123", "https://csdn.example.com/123"));
        BillingService billing = mock(BillingService.class);

        PublishPostTool tool = new PublishPostTool(
                mock(BlogAgentService.class), blogService, csdn,
                mock(JuejinBlogSyncService.class), billing);
        JsonNode result = tool.execute(42L,
                JsonNodeFactory.instance.objectNode().put("postId", 5L).put("platform", "csdn"),
                new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("externalId").asText()).isEqualTo("123");
        assertThat(result.path("url").asText()).isEqualTo("https://csdn.example.com/123");
        verify(csdn).sync(eq(42L), eq(5L));
        verify(billing).chargeFlat(eq(42L), eq("csdn-publish"), any(), any(), anyLong(), any());
    }

    @Test
    void skipsDuplicateCsdnSyncWhenAlreadySynced() {
        BlogService blogService = mock(BlogService.class);
        BlogPostVO post = post(5L, "已同步", 1);
        post.setCsdnSynced(true);
        when(blogService.getManagedBlogById(42L, 5L)).thenReturn(post);
        CsdnBlogSyncService csdn = mock(CsdnBlogSyncService.class);
        BillingService billing = mock(BillingService.class);

        PublishPostTool tool = new PublishPostTool(
                mock(BlogAgentService.class), blogService, csdn,
                mock(JuejinBlogSyncService.class), billing);
        JsonNode result = tool.execute(42L,
                JsonNodeFactory.instance.objectNode().put("postId", 5L).put("platform", "csdn"),
                new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("hint").asText()).contains("已同步过 CSDN");
        verify(csdn, never()).sync(any(), any());
        verify(billing, never()).chargeFlat(any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void rejectsMissingTaskAndPost() {
        PublishPostTool tool = new PublishPostTool(
                mock(BlogAgentService.class), mock(BlogService.class),
                mock(CsdnBlogSyncService.class), mock(JuejinBlogSyncService.class),
                mock(BillingService.class));
        assertThatThrownBy(() -> tool.execute(42L,
                JsonNodeFactory.instance.objectNode(), new RecordingProgress()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    @Test
    void reconcilesPersistedCsdnResultWithoutCallingThePlatformAgain() {
        BlogService blogService = mock(BlogService.class);
        when(blogService.getManagedBlogById(42L, 5L)).thenReturn(post(5L, "CSDN 同步", 1));
        CsdnBlogSyncService csdn = mock(CsdnBlogSyncService.class);
        when(csdn.findSucceeded(42L, 5L))
                .thenReturn(new PublishResult("csdn", "123", "https://csdn.example.com/123"));
        PublishPostTool tool = new PublishPostTool(mock(BlogAgentService.class), blogService, csdn,
                mock(JuejinBlogSyncService.class), mock(BillingService.class));

        JsonNode result = tool.reconcile(new AgentToolContext(42L, 7L, 3L, "invocation-1"),
                JsonNodeFactory.instance.objectNode().put("postId", 5L).put("platform", "csdn"))
                .orElseThrow();

        assertThat(result.path("externalId").asText()).isEqualTo("123");
        verify(csdn, never()).sync(any(), any());
    }

    @Test
    void leavesExternalPublishUnknownWithoutAPersistedSuccessRecord() {
        BlogService blogService = mock(BlogService.class);
        when(blogService.getManagedBlogById(42L, 5L)).thenReturn(post(5L, "CSDN 同步", 1));
        CsdnBlogSyncService csdn = mock(CsdnBlogSyncService.class);
        PublishPostTool tool = new PublishPostTool(mock(BlogAgentService.class), blogService, csdn,
                mock(JuejinBlogSyncService.class), mock(BillingService.class));

        assertThat(tool.reconcile(new AgentToolContext(42L, 7L, 3L, "invocation-1"),
                JsonNodeFactory.instance.objectNode().put("postId", 5L).put("platform", "csdn")))
                .isEmpty();
        verify(csdn, never()).sync(any(), any());
    }

    private BlogPostVO post(Long id, String title, Integer status) {
        BlogPostVO post = new BlogPostVO();
        post.setId(id);
        post.setTitle(title);
        post.setStatus(status);
        return post;
    }

    private static final class RecordingProgress implements AgentTool.ProgressListener {
        @Override
        public void onProgress(String message) {
        }

        @Override
        public void onToolDelta(String delta) {
        }
    }
}

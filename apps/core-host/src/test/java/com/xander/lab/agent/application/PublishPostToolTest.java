package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.payment.api.BillingPort;
import com.xander.lab.blogagent.application.BlogAgentService;
import com.xander.lab.auth.core.UserContext;
import com.xander.lab.content.api.ContentPost;
import com.xander.lab.content.api.ContentPostPort;
import com.xander.lab.content.api.ContentPostStatus;
import com.xander.lab.integration.mcp.csdn.application.CsdnBlogSyncService;
import com.xander.lab.integration.mcp.juejin.application.JuejinBlogSyncService;
import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.content.api.PublishResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
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

/**
 * {@link PublishPostTool} 的两条本地文章来源。
 *
 * <p>本工具有两条取文章的路：{@code taskId} 走 {@code blogagent}（同部署单元，仍返回内容域的
 * {@code BlogPostVO}），{@code postId} 走 {@link ContentPostPort}（跨部署单元，返回契约类型
 * {@link ContentPost}）。两条路在工具内部归一成 {@code LocalPost}，所以测试必须<b>两条都覆盖</b> ——
 * 只测一条的话，另一条的字段取值（尤其是 {@code status}/{@code csdnSynced}/{@code juejinSynced}
 * 这几个决定分支的字段）就没有任何东西钉住。</p>
 */
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
                    return generatedPost(9L, "本地发布", ContentPostStatus.PUBLISHED);
                });
        PublishPostTool tool = new PublishPostTool(
                blogAgentService, mock(ContentPostPort.class),
                mock(CsdnBlogSyncService.class), mock(JuejinBlogSyncService.class),
                mock(BillingPort.class));
        tool.execute(42L,
                JsonNodeFactory.instance.objectNode().put("taskId", 9L), new RecordingProgress());

        assertThat(UserContext.getUserId()).isNull();
    }

    @Test
    void publishesGeneratedTaskLocallyByDefault() {
        BlogAgentService blogAgentService = mock(BlogAgentService.class);
        BlogPostVO published = generatedPost(9L, "本地发布", ContentPostStatus.PUBLISHED);
        when(blogAgentService.publish(eq(9L), eq(42L))).thenReturn(published);
        BillingPort billing = mock(BillingPort.class);

        PublishPostTool tool = new PublishPostTool(
                blogAgentService, mock(ContentPostPort.class),
                mock(CsdnBlogSyncService.class), mock(JuejinBlogSyncService.class), billing);
        JsonNode result = tool.execute(42L,
                JsonNodeFactory.instance.objectNode().put("taskId", 9L), new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("postId").asLong()).isEqualTo(9L);
        assertThat(result.path("platform").asText()).isEqualTo("local");
        verify(blogAgentService).publish(eq(9L), eq(42L));
        verify(billing, never()).chargeFlat(any(), any(), any(), any(), anyLong(), any());
    }

    /**
     * 走 {@code postId} 分支时读的是端口 —— 这条测试同时钉住“端口拿到的 userId 是调用方给的”，
     * 而不是 ThreadLocal 里那个。
     */
    @Test
    void syncsExistingPostToCsdnAndChargesFlat() {
        ContentPostPort contentPostPort = mock(ContentPostPort.class);
        ContentPost post = contractPost(5L, "CSDN 同步", "摘要", "正文", false, false);
        when(contentPostPort.findOwned(42L, 5L)).thenReturn(post);
        CsdnBlogSyncService csdn = mock(CsdnBlogSyncService.class);
        when(csdn.sync(eq(42L), eq(5L))).thenReturn(new PublishResult("csdn", "123", "https://csdn.example.com/123"));
        BillingPort billing = mock(BillingPort.class);

        PublishPostTool tool = new PublishPostTool(
                mock(BlogAgentService.class), contentPostPort, csdn,
                mock(JuejinBlogSyncService.class), billing);
        JsonNode result = tool.execute(42L,
                JsonNodeFactory.instance.objectNode().put("postId", 5L).put("platform", "csdn"),
                new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("externalId").asText()).isEqualTo("123");
        assertThat(result.path("url").asText()).isEqualTo("https://csdn.example.com/123");
        verify(contentPostPort).findOwned(42L, 5L);
        verify(csdn).sync(eq(42L), eq(5L));
        verify(billing).chargeFlat(eq(42L), eq("csdn-publish"), any(), any(), anyLong(), any());
    }

    /**
     * 幂等分支读的是 {@code csdnSynced} —— {@link ContentPost} 里 {@code csdnSynced} 与
     * {@code juejinSynced} 是相邻的 {@code Boolean}，位置写反不会编译报错，只会让本工具
     * 对着掘金的状态判 CSDN 的幂等。分量顺序由 {@code ContentPostTest} 钉住。
     */
    @Test
    void skipsDuplicateCsdnSyncWhenAlreadySynced() {
        ContentPostPort contentPostPort = mock(ContentPostPort.class);
        when(contentPostPort.findOwned(42L, 5L))
                .thenReturn(contractPost(5L, "已同步", null, null, true, false));
        CsdnBlogSyncService csdn = mock(CsdnBlogSyncService.class);
        BillingPort billing = mock(BillingPort.class);

        PublishPostTool tool = new PublishPostTool(
                mock(BlogAgentService.class), contentPostPort, csdn,
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
                mock(BlogAgentService.class), mock(ContentPostPort.class),
                mock(CsdnBlogSyncService.class), mock(JuejinBlogSyncService.class),
                mock(BillingPort.class));
        assertThatThrownBy(() -> tool.execute(42L,
                JsonNodeFactory.instance.objectNode(), new RecordingProgress()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    @Test
    void reconcilesPersistedCsdnResultWithoutCallingThePlatformAgain() {
        ContentPostPort contentPostPort = mock(ContentPostPort.class);
        when(contentPostPort.findOwned(42L, 5L))
                .thenReturn(contractPost(5L, "CSDN 同步", null, null, false, false));
        CsdnBlogSyncService csdn = mock(CsdnBlogSyncService.class);
        when(csdn.findSucceeded(42L, 5L))
                .thenReturn(new PublishResult("csdn", "123", "https://csdn.example.com/123"));
        PublishPostTool tool = new PublishPostTool(mock(BlogAgentService.class), contentPostPort, csdn,
                mock(JuejinBlogSyncService.class), mock(BillingPort.class));

        JsonNode result = tool.reconcile(new AgentToolContext(42L, 7L, 3L, "invocation-1"),
                JsonNodeFactory.instance.objectNode().put("postId", 5L).put("platform", "csdn"))
                .orElseThrow();

        assertThat(result.path("externalId").asText()).isEqualTo("123");
        verify(csdn, never()).sync(any(), any());
    }

    @Test
    void leavesExternalPublishUnknownWithoutAPersistedSuccessRecord() {
        ContentPostPort contentPostPort = mock(ContentPostPort.class);
        when(contentPostPort.findOwned(42L, 5L))
                .thenReturn(contractPost(5L, "CSDN 同步", null, null, false, false));
        CsdnBlogSyncService csdn = mock(CsdnBlogSyncService.class);
        PublishPostTool tool = new PublishPostTool(mock(BlogAgentService.class), contentPostPort, csdn,
                mock(JuejinBlogSyncService.class), mock(BillingPort.class));

        assertThat(tool.reconcile(new AgentToolContext(42L, 7L, 3L, "invocation-1"),
                JsonNodeFactory.instance.objectNode().put("postId", 5L).put("platform", "csdn")))
                .isEmpty();
        verify(csdn, never()).sync(any(), any());
    }

    /** {@code taskId} 分支的来源：{@code blogagent} 仍返回内容域的 {@code BlogPostVO}。 */
    private static BlogPostVO generatedPost(Long id, String title, Integer status) {
        BlogPostVO post = new BlogPostVO();
        post.setId(id);
        post.setTitle(title);
        post.setStatus(status);
        return post;
    }

    private static ContentPost contractPost(Long id, String title, String summary, String content,
                                            boolean csdnSynced, boolean juejinSynced) {
        return new ContentPost(
                id,                            // id
                title,                         // title
                summary,                       // summary
                content,                       // content
                "backend",                     // category
                "后端",                         // categoryName
                null,                          // coverImage
                List.of("java"),               // tags
                42L,                           // userId
                "XanderDING",                  // author
                LocalDate.of(2026, 8, 1),      // date
                "3 min",                       // readTime
                null,                          // tips
                12,                            // views
                ContentPostStatus.PUBLISHED,   // status
                csdnSynced,                    // csdnSynced
                juejinSynced);                 // juejinSynced
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

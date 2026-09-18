package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.payment.api.BillingPort;
import com.xander.lab.blogagent.spi.BlogAgentPort;
import com.xander.lab.blogagent.spi.BlogAgentPostView;
import com.xander.lab.auth.core.UserContext;
import com.xander.lab.content.api.ContentPost;
import com.xander.lab.content.api.ContentPostPort;
import com.xander.lab.content.api.ContentPostStatus;
import com.xander.lab.integration.api.BlogSyncResult;
import com.xander.lab.integration.api.CsdnBlogSyncPort;
import com.xander.lab.integration.api.JuejinBlogSyncPort;
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
 * {@link PublishPostTool} 的两条本地文章来源与两条站外同步出口。
 *
 * <p>本工具有两条取文章的路：{@code taskId} 走 {@link BlogAgentPort}（返回契约类型
 * {@link BlogAgentPostView}），{@code postId} 走 {@link ContentPostPort}（返回契约类型
 * {@link ContentPost}）。两条路在工具内部归一成 {@code LocalPost}，所以测试必须<b>两条都覆盖</b> ——
 * 只测一条的话，另一条的字段取值（尤其是 {@code status}/{@code csdnSynced}/{@code juejinSynced}
 * 这几个决定分支的字段）就没有任何东西钉住。</p>
 *
 * <p>⚠️ 本类 mock 的是<b>端口</b>而不是 {@code BlogAgentService}，而且 {@code 42L}（userId）
 * 与 {@code 9L}（taskId）保持**互不相同** —— 端口是 {@code (userId, taskId)}、域侧是
 * {@code (taskId, userId)}，两个字面量一旦相同，换位写反就没有任何断言会红。
 * 站外同步同理 mock {@link CsdnBlogSyncPort} / {@link JuejinBlogSyncPort} 而不是
 * {@code integration} 域那两个具体 {@code @Service}。</p>
 *
 * <p>⚠️ {@code findSucceeded} 用 {@code null} 表示"没有成功记录"，所以凡是断言"查不到"的用例
 * 都<b>显式</b> stub 成 {@code null}：Mockito 对未 stub 的返回给的是包装类型的零值，
 * 这条测试今天靠"返回类型是自定义对象 ⇒ 默认就是 null"侥幸成立，写出来才不依赖这个巧合。</p>
 */
class PublishPostToolTest {

    private static final Long USER_ID = 42L;
    private static final Long TASK_ID = 9L;

    @AfterEach
    void clearThreadContext() {
        UserContext.clear();
    }

    @Test
    void resolvesUserContextForCallerAndClearsItAfter() {
        BlogAgentPort blogAgentPort = mock(BlogAgentPort.class);
        when(blogAgentPort.publish(eq(USER_ID), eq(TASK_ID)))
                .thenAnswer(invocation -> {
                    assertThat(UserContext.getUserId()).isEqualTo(USER_ID);
                    return generatedPost(TASK_ID, "本地发布", ContentPostStatus.PUBLISHED);
                });
        PublishPostTool tool = new PublishPostTool(
                blogAgentPort, mock(ContentPostPort.class),
                mock(CsdnBlogSyncPort.class), mock(JuejinBlogSyncPort.class),
                mock(BillingPort.class));
        tool.execute(USER_ID,
                JsonNodeFactory.instance.objectNode().put("taskId", TASK_ID), new RecordingProgress());

        assertThat(UserContext.getUserId()).isNull();
    }

    @Test
    void publishesGeneratedTaskLocallyByDefault() {
        BlogAgentPort blogAgentPort = mock(BlogAgentPort.class);
        BlogAgentPostView published = generatedPost(TASK_ID, "本地发布", ContentPostStatus.PUBLISHED);
        when(blogAgentPort.publish(eq(USER_ID), eq(TASK_ID))).thenReturn(published);
        BillingPort billing = mock(BillingPort.class);

        PublishPostTool tool = new PublishPostTool(
                blogAgentPort, mock(ContentPostPort.class),
                mock(CsdnBlogSyncPort.class), mock(JuejinBlogSyncPort.class), billing);
        JsonNode result = tool.execute(USER_ID,
                JsonNodeFactory.instance.objectNode().put("taskId", TASK_ID), new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("postId").asLong()).isEqualTo(TASK_ID);
        assertThat(result.path("platform").asText()).isEqualTo("local");
        // ⚠️ 顺序断言：(userId, taskId)，不是 (taskId, userId)。
        verify(blogAgentPort).publish(eq(USER_ID), eq(TASK_ID));
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
        when(contentPostPort.findOwned(USER_ID, 5L)).thenReturn(post);
        CsdnBlogSyncPort csdn = mock(CsdnBlogSyncPort.class);
        when(csdn.sync(eq(USER_ID), eq(5L)))
                .thenReturn(new BlogSyncResult("csdn", "123", "https://csdn.example.com/123"));
        BillingPort billing = mock(BillingPort.class);

        PublishPostTool tool = new PublishPostTool(
                mock(BlogAgentPort.class), contentPostPort, csdn,
                mock(JuejinBlogSyncPort.class), billing);
        JsonNode result = tool.execute(USER_ID,
                JsonNodeFactory.instance.objectNode().put("postId", 5L).put("platform", "csdn"),
                new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("externalId").asText()).isEqualTo("123");
        assertThat(result.path("url").asText()).isEqualTo("https://csdn.example.com/123");
        verify(contentPostPort).findOwned(USER_ID, 5L);
        verify(csdn).sync(eq(USER_ID), eq(5L));
        verify(billing).chargeFlat(eq(USER_ID), eq("csdn-publish"), any(), any(), anyLong(), any());
    }

    /**
     * 掘金分支走的是<b>另一个</b>端口，而且参数表与 CSDN 不同：多两个掘金专有的分类/标签。
     *
     * <p>本工具传的是 {@code (null, null)} —— "用文章自己的分类与标签名，由发布器解析成
     * 掘金 tag_id"。这条断言把这两个 {@code null} <b>逐字钉住</b>：
     * 谁把它们换成文章自己的分类/标签值，掘金的解析路径就变了，
     * 而这里的字面量比较会先红。端口上之所以保留这两个参数而不是砍掉，也是这条测试的另一半理由
     * （同域的前端 HTTP 层会传真实值，见 {@link JuejinBlogSyncPort} 的类注释）。</p>
     */
    @Test
    void syncsExistingPostToJuejinWithTheArticlesOwnCategoryAndTags() {
        ContentPostPort contentPostPort = mock(ContentPostPort.class);
        when(contentPostPort.findOwned(USER_ID, 6L))
                .thenReturn(contractPost(6L, "掘金同步", "摘要", "正文", false, false));
        JuejinBlogSyncPort juejin = mock(JuejinBlogSyncPort.class);
        when(juejin.sync(USER_ID, 6L, null, null))
                .thenReturn(new BlogSyncResult("juejin", "jj-6", "https://juejin.cn/post/6"));
        BillingPort billing = mock(BillingPort.class);

        PublishPostTool tool = new PublishPostTool(mock(BlogAgentPort.class), contentPostPort,
                mock(CsdnBlogSyncPort.class), juejin, billing);
        JsonNode result = tool.execute(USER_ID,
                JsonNodeFactory.instance.objectNode().put("postId", 6L).put("platform", "juejin"),
                new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("externalId").asText()).isEqualTo("jj-6");
        assertThat(result.path("url").asText()).isEqualTo("https://juejin.cn/post/6");
        assertThat(result.path("hint").asText()).contains("juejin");
        verify(juejin).sync(USER_ID, 6L, null, null);
        verify(billing).chargeFlat(eq(USER_ID), eq("juejin-publish"), any(), any(), anyLong(), any());
    }

    /**
     * 幂等分支读的是 {@code csdnSynced} —— {@link ContentPost} 里 {@code csdnSynced} 与
     * {@code juejinSynced} 是相邻的 {@code Boolean}，位置写反不会编译报错，只会让本工具
     * 对着掘金的状态判 CSDN 的幂等。分量顺序由 {@code ContentPostTest} 钉住。
     */
    @Test
    void skipsDuplicateCsdnSyncWhenAlreadySynced() {
        ContentPostPort contentPostPort = mock(ContentPostPort.class);
        when(contentPostPort.findOwned(USER_ID, 5L))
                .thenReturn(contractPost(5L, "已同步", null, null, true, false));
        CsdnBlogSyncPort csdn = mock(CsdnBlogSyncPort.class);
        BillingPort billing = mock(BillingPort.class);

        PublishPostTool tool = new PublishPostTool(
                mock(BlogAgentPort.class), contentPostPort, csdn,
                mock(JuejinBlogSyncPort.class), billing);
        JsonNode result = tool.execute(USER_ID,
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
                mock(BlogAgentPort.class), mock(ContentPostPort.class),
                mock(CsdnBlogSyncPort.class), mock(JuejinBlogSyncPort.class),
                mock(BillingPort.class));
        assertThatThrownBy(() -> tool.execute(USER_ID,
                JsonNodeFactory.instance.objectNode(), new RecordingProgress()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    @Test
    void reconcilesPersistedCsdnResultWithoutCallingThePlatformAgain() {
        ContentPostPort contentPostPort = mock(ContentPostPort.class);
        when(contentPostPort.findOwned(USER_ID, 5L))
                .thenReturn(contractPost(5L, "CSDN 同步", null, null, false, false));
        CsdnBlogSyncPort csdn = mock(CsdnBlogSyncPort.class);
        when(csdn.findSucceeded(USER_ID, 5L))
                .thenReturn(new BlogSyncResult("csdn", "123", "https://csdn.example.com/123"));
        PublishPostTool tool = new PublishPostTool(mock(BlogAgentPort.class), contentPostPort, csdn,
                mock(JuejinBlogSyncPort.class), mock(BillingPort.class));

        JsonNode result = tool.reconcile(new AgentToolContext(USER_ID, 7L, 3L, "invocation-1"),
                JsonNodeFactory.instance.objectNode().put("postId", 5L).put("platform", "csdn"))
                .orElseThrow();

        assertThat(result.path("externalId").asText()).isEqualTo("123");
        verify(csdn, never()).sync(any(), any());
    }

    @Test
    void leavesExternalPublishUnknownWithoutAPersistedSuccessRecord() {
        ContentPostPort contentPostPort = mock(ContentPostPort.class);
        when(contentPostPort.findOwned(USER_ID, 5L))
                .thenReturn(contractPost(5L, "CSDN 同步", null, null, false, false));
        CsdnBlogSyncPort csdn = mock(CsdnBlogSyncPort.class);
        // 显式 stub 成 null：契约用 null 表示"没有成功记录"，不靠 Mockito 的默认值。
        when(csdn.findSucceeded(USER_ID, 5L)).thenReturn(null);
        PublishPostTool tool = new PublishPostTool(mock(BlogAgentPort.class), contentPostPort, csdn,
                mock(JuejinBlogSyncPort.class), mock(BillingPort.class));

        assertThat(tool.reconcile(new AgentToolContext(USER_ID, 7L, 3L, "invocation-1"),
                JsonNodeFactory.instance.objectNode().put("postId", 5L).put("platform", "csdn")))
                .isEmpty();
        verify(csdn, never()).sync(any(), any());
    }

    /**
     * {@code taskId} 分支的只读路径：reconcile 走 {@code findPublishedPost}，
     * ⚠️ 同样必须是 {@code (userId, taskId)}。这条单独存在，是因为
     * {@code publish} 那条的断言不会覆盖 {@code findPublishedPost} —— 两个方法各自换位，
     * 写反一个另一个照旧绿。
     */
    @Test
    void reconcileOnATaskIdReadsThePublishedPostWithUserIdFirst() {
        BlogAgentPort blogAgentPort = mock(BlogAgentPort.class);
        when(blogAgentPort.findPublishedPost(eq(USER_ID), eq(TASK_ID)))
                .thenReturn(generatedPost(TASK_ID, "已发布", ContentPostStatus.PUBLISHED));
        PublishPostTool tool = new PublishPostTool(blogAgentPort, mock(ContentPostPort.class),
                mock(CsdnBlogSyncPort.class), mock(JuejinBlogSyncPort.class),
                mock(BillingPort.class));

        JsonNode result = tool.reconcile(new AgentToolContext(USER_ID, 7L, 3L, "invocation-1"),
                JsonNodeFactory.instance.objectNode().put("taskId", TASK_ID)).orElseThrow();

        assertThat(result.path("postId").asLong()).isEqualTo(TASK_ID);
        assertThat(result.path("hint").asText()).contains("已发布到本站");
        verify(blogAgentPort).findPublishedPost(USER_ID, TASK_ID);
    }

    /** {@code taskId} 分支的来源：blogagent 的端口视图（5 个分量）。 */
    private static BlogAgentPostView generatedPost(Long id, String title, Integer status) {
        return new BlogAgentPostView(id, title, status, null, null);
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
                USER_ID,                       // userId
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

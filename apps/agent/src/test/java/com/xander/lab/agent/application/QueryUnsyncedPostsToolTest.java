package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.auth.core.UserContext;
import com.xander.lab.content.api.ContentPost;
import com.xander.lab.content.api.ContentPostPort;
import com.xander.lab.content.api.ContentPostQuery;
import com.xander.lab.content.api.ContentPostStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryUnsyncedPostsToolTest {

    @AfterEach
    void clearThreadContext() {
        UserContext.clear();
    }

    @Test
    void returnsAllJuejinUnsyncedPostsFromSingleQuery() {
        ContentPostPort contentPostPort = mock(ContentPostPort.class);
        ContentPost first = post(1L, "未同步文章一", false, true);
        ContentPost second = post(2L, "未同步文章二", false, false);
        when(contentPostPort.listAllMyPosts(eq(42L),
                eq(new ContentPostQuery(null, "", Boolean.FALSE, null)), eq(null)))
                .thenReturn(List.of(first, second));

        QueryUnsyncedPostsTool tool = new QueryUnsyncedPostsTool(contentPostPort);
        JsonNode result = tool.execute(42L, JsonNodeFactory.instance.objectNode(), new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("platform").asText()).isEqualTo("juejin");
        assertThat(result.path("total").asLong()).isEqualTo(2L);
        assertThat(result.path("posts")).hasSize(2);
        JsonNode firstItem = result.path("posts").get(0);
        assertThat(firstItem.path("id").asLong()).isEqualTo(1L);
        assertThat(firstItem.path("title").asText()).isEqualTo("未同步文章一");
        assertThat(firstItem.path("url").asText()).isEqualTo("/blog/1/");
        assertThat(firstItem.path("tags")).hasSize(1);
        verify(contentPostPort).listAllMyPosts(eq(42L),
                eq(new ContentPostQuery(null, "", Boolean.FALSE, null)), eq(null));
    }

    /**
     * 这条是 {@link ContentPostQuery} 存在的理由：{@code juejinSynced} 与 {@code csdnSynced}
     * 是两个相邻的 {@code Boolean}，位置写反不会有编译错误，只会把“未同步到 CSDN”
     * 静默变成“未同步到掘金”。用两个方向各断言一次来钉住顺序。
     */
    @Test
    void csdnPlatformAndStatusArePassedThrough() {
        ContentPostPort contentPostPort = mock(ContentPostPort.class);
        when(contentPostPort.listAllMyPosts(eq(42L),
                eq(new ContentPostQuery(ContentPostStatus.PUBLISHED, "", null, Boolean.FALSE)), eq(null)))
                .thenReturn(List.of(post(3L, "CSDN 未同步", false, true)));

        QueryUnsyncedPostsTool tool = new QueryUnsyncedPostsTool(contentPostPort);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("platform", "csdn")
                .put("status", "published");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("platform").asText()).isEqualTo("csdn");
        assertThat(result.path("total").asLong()).isEqualTo(1L);
        assertThat(result.path("posts").get(0).path("id").asLong()).isEqualTo(3L);
        verify(contentPostPort).listAllMyPosts(eq(42L),
                eq(new ContentPostQuery(ContentPostStatus.PUBLISHED, "", null, Boolean.FALSE)), eq(null));
    }

    @Test
    void clearsUserContextAfterExecution() {
        ContentPostPort contentPostPort = mock(ContentPostPort.class);
        when(contentPostPort.listAllMyPosts(eq(42L),
                eq(new ContentPostQuery(null, "", Boolean.FALSE, null)), eq(null)))
                .thenAnswer(invocation -> {
                    assertThat(UserContext.getUserId()).isEqualTo(42L);
                    return List.of();
                });

        QueryUnsyncedPostsTool tool = new QueryUnsyncedPostsTool(contentPostPort);
        tool.execute(42L, JsonNodeFactory.instance.objectNode(), new RecordingProgress());

        assertThat(UserContext.getUserId()).isNull();
    }

    /** 分量位置逐个标注，见 {@link ContentPost} 的“分量顺序是位置契约”。 */
    private static ContentPost post(Long id, String title, boolean csdnSynced, boolean juejinSynced) {
        return new ContentPost(
                id,                            // id
                title,                         // title
                "摘要",                         // summary
                null,                          // content（列表投影不返回正文）
                "frontend",                    // category
                "前端开发",                     // categoryName
                null,                          // coverImage
                List.of("react"),              // tags
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

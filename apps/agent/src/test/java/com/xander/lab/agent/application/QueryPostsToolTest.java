package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.auth.core.UserContext;
import com.xander.lab.content.api.ContentPost;
import com.xander.lab.content.api.ContentPostPage;
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

class QueryPostsToolTest {

    @AfterEach
    void clearThreadContext() {
        UserContext.clear();
    }

    @Test
    void listsOwnPublishedPostsWithResolvedSearch() {
        ContentPostPort contentPostPort = mock(ContentPostPort.class);
        ContentPostPage page = new ContentPostPage(
                List.of(post(3L, "Spring 定时任务", true, false)), 1L, 1L, 1L, 10L, false);
        when(contentPostPort.listMyPosts(eq(42L),
                eq(new ContentPostQuery(ContentPostStatus.PUBLISHED, "定时", null, null)), eq(1), eq(10)))
                .thenReturn(page);

        QueryPostsTool tool = new QueryPostsTool(contentPostPort);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("status", "published")
                .put("search", "定时")
                .put("page", 1)
                .put("size", 10);
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("total").asLong()).isEqualTo(1L);
        assertThat(result.path("posts")).hasSize(1);
        JsonNode first = result.path("posts").get(0);
        assertThat(first.path("id").asLong()).isEqualTo(3L);
        assertThat(first.path("title").asText()).isEqualTo("Spring 定时任务");
        assertThat(first.path("status").asText()).isEqualTo("published");
        assertThat(first.path("url").asText()).isEqualTo("/blog/3/");
        assertThat(first.path("juejinSynced").asBoolean()).isFalse();
        assertThat(first.path("csdnSynced").asBoolean()).isTrue();
        assertThat(first.path("tags")).hasSize(2);
        verify(contentPostPort).listMyPosts(eq(42L),
                eq(new ContentPostQuery(ContentPostStatus.PUBLISHED, "定时", null, null)), eq(1), eq(10));
    }

    /**
     * 端口显式收 userId —— 跨进程没有 ThreadLocal 可传，所以调用方必须把它当参数给出去。
     * 同时 {@code UserContext} 仍然要在执行后被清掉，否则会泄漏到下一条复用该线程的请求上。
     */
    @Test
    void passesTheCallerIdExplicitlyAndClearsTheThreadContextAfter() {
        ContentPostPort contentPostPort = mock(ContentPostPort.class);
        ContentPostPage page = new ContentPostPage(List.of(), 0L, 1L, 0L, 10L, false);
        when(contentPostPort.listMyPosts(eq(42L), eq(new ContentPostQuery(null, "", null, null)), eq(1), eq(10)))
                .thenAnswer(invocation -> {
                    assertThat(UserContext.getUserId()).isEqualTo(42L);
                    return page;
                });

        QueryPostsTool tool = new QueryPostsTool(contentPostPort);
        tool.execute(42L, JsonNodeFactory.instance.objectNode(), new RecordingProgress());

        assertThat(UserContext.getUserId()).isNull();
    }

    @Test
    void filtersBySyncStateAndLimitWithoutPagination() {
        ContentPostPort contentPostPort = mock(ContentPostPort.class);
        when(contentPostPort.listAllMyPosts(eq(42L),
                eq(new ContentPostQuery(null, "", null, Boolean.TRUE)), eq(3)))
                .thenReturn(List.of(post(7L, "CSDN 已同步文章", true, false)));

        QueryPostsTool tool = new QueryPostsTool(contentPostPort);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("csdnSynced", true)
                .put("limit", 3);
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("total").asLong()).isEqualTo(1L);
        assertThat(result.path("posts")).hasSize(1);
        JsonNode first = result.path("posts").get(0);
        assertThat(first.path("id").asLong()).isEqualTo(7L);
        assertThat(first.path("url").asText()).isEqualTo("/blog/7/");
        assertThat(result.path("page").isMissingNode()).isTrue();
        assertThat(result.path("hasMore").isMissingNode()).isTrue();
        verify(contentPostPort).listAllMyPosts(eq(42L),
                eq(new ContentPostQuery(null, "", null, Boolean.TRUE)), eq(3));
    }

    /**
     * 具名分量顺序敏感 —— {@link ContentPost} 有 17 个分量、其中四组是相邻同类型字段，
     * 位置写错不会编译报错。所以这里每个分量都标注了名字。
     */
    private static ContentPost post(Long id, String title, boolean csdnSynced, boolean juejinSynced) {
        return new ContentPost(
                id,                            // id
                title,                         // title
                "摘要",                         // summary
                null,                          // content（列表投影不返回正文）
                "backend",                     // category
                "后端",                         // categoryName
                null,                          // coverImage
                List.of("spring", "quartz"),   // tags
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

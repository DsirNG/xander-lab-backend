package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.common.PageData;
import com.xander.lab.identity.security.UserContext;
import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.publishing.application.BlogService;
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
    void returnsOnlyJuejinUnsyncedPostsInOneCall() {
        BlogService blogService = mock(BlogService.class);
        BlogPostVO unsynced = post(1L, "未同步文章", false, true);
        BlogPostVO synced = post(2L, "已同步文章", true, false);
        PageData<BlogPostVO> page = pageOf(List.of(unsynced, synced), 2L, 1L, false);
        when(blogService.getMyBlogs(eq(null), eq(""), eq(1), eq(50))).thenReturn(page);

        QueryUnsyncedPostsTool tool = new QueryUnsyncedPostsTool(blogService);
        JsonNode result = tool.execute(42L, JsonNodeFactory.instance.objectNode(), new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("platform").asText()).isEqualTo("juejin");
        assertThat(result.path("total").asLong()).isEqualTo(1L);
        assertThat(result.path("truncated").asBoolean()).isFalse();
        assertThat(result.path("posts")).hasSize(1);
        JsonNode first = result.path("posts").get(0);
        assertThat(first.path("id").asLong()).isEqualTo(1L);
        assertThat(first.path("title").asText()).isEqualTo("未同步文章");
        assertThat(first.path("url").asText()).isEqualTo("/blog/1/");
        verify(blogService).getMyBlogs(eq(null), eq(""), eq(1), eq(50));
    }

    @Test
    void aggregatesAcrossPagesWithoutExposingPagination() {
        BlogService blogService = mock(BlogService.class);
        BlogPostVO pageOnePost = post(1L, "第一页未同步", false, false);
        BlogPostVO pageTwoPost = post(2L, "第二页未同步", false, false);
        PageData<BlogPostVO> pageOne = pageOf(List.of(pageOnePost), 2L, 1L, true);
        PageData<BlogPostVO> pageTwo = pageOf(List.of(pageTwoPost), 2L, 2L, false);
        when(blogService.getMyBlogs(eq(null), eq(""), eq(1), eq(50))).thenReturn(pageOne);
        when(blogService.getMyBlogs(eq(null), eq(""), eq(2), eq(50))).thenReturn(pageTwo);

        QueryUnsyncedPostsTool tool = new QueryUnsyncedPostsTool(blogService);
        JsonNode result = tool.execute(42L, JsonNodeFactory.instance.objectNode(), new RecordingProgress());

        assertThat(result.path("total").asLong()).isEqualTo(2L);
        assertThat(result.path("posts")).hasSize(2);
        assertThat(result.path("posts").get(0).path("id").asLong()).isEqualTo(1L);
        assertThat(result.path("posts").get(1).path("id").asLong()).isEqualTo(2L);
        verify(blogService).getMyBlogs(eq(null), eq(""), eq(1), eq(50));
        verify(blogService).getMyBlogs(eq(null), eq(""), eq(2), eq(50));
    }

    @Test
    void csdnPlatformFiltersOnCsdnSynced() {
        BlogService blogService = mock(BlogService.class);
        BlogPostVO csdnUnsynced = post(3L, "CSDN 未同步", true, false);
        BlogPostVO csdnSynced = post(4L, "CSDN 已同步", true, true);
        PageData<BlogPostVO> page = pageOf(List.of(csdnUnsynced, csdnSynced), 2L, 1L, false);
        when(blogService.getMyBlogs(eq(BlogService.STATUS_PUBLISHED), eq(""), eq(1), eq(50))).thenReturn(page);

        QueryUnsyncedPostsTool tool = new QueryUnsyncedPostsTool(blogService);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("platform", "csdn")
                .put("status", "published");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("platform").asText()).isEqualTo("csdn");
        assertThat(result.path("total").asLong()).isEqualTo(1L);
        assertThat(result.path("posts").get(0).path("id").asLong()).isEqualTo(3L);
        verify(blogService).getMyBlogs(eq(BlogService.STATUS_PUBLISHED), eq(""), eq(1), eq(50));
    }

    @Test
    void clearsUserContextAfterExecution() {
        BlogService blogService = mock(BlogService.class);
        when(blogService.getMyBlogs(eq(null), eq(""), eq(1), eq(50)))
                .thenAnswer(invocation -> {
                    assertThat(UserContext.getUserId()).isEqualTo(42L);
                    return pageOf(List.of(), 0L, 1L, false);
                });

        QueryUnsyncedPostsTool tool = new QueryUnsyncedPostsTool(blogService);
        tool.execute(42L, JsonNodeFactory.instance.objectNode(), new RecordingProgress());

        assertThat(UserContext.getUserId()).isNull();
    }

    private static BlogPostVO post(Long id, String title, boolean juejinSynced, boolean csdnSynced) {
        BlogPostVO post = new BlogPostVO();
        post.setId(id);
        post.setTitle(title);
        post.setStatus(BlogService.STATUS_PUBLISHED);
        post.setDate(LocalDate.of(2026, 8, 1));
        post.setCategoryName("前端开发");
        post.setTags(List.of("react"));
        post.setJuejinSynced(juejinSynced);
        post.setCsdnSynced(csdnSynced);
        return post;
    }

    private static PageData<BlogPostVO> pageOf(List<BlogPostVO> records, long total, long current, boolean hasMore) {
        PageData<BlogPostVO> page = new PageData<>();
        page.setRecords(records);
        page.setTotal(total);
        page.setCurrent(current);
        page.setSize(50L);
        page.setPages((total + 49) / 50);
        page.setHasMore(hasMore);
        return page;
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
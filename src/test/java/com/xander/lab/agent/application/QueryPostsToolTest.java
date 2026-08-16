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

class QueryPostsToolTest {

    @AfterEach
    void clearThreadContext() {
        UserContext.clear();
    }

    @Test
    void listsOwnPublishedPostsWithResolvedSearch() {
        BlogService blogService = mock(BlogService.class);
        PageData<BlogPostVO> page = new PageData<>();
        BlogPostVO post = new BlogPostVO();
        post.setId(3L);
        post.setTitle("Spring 定时任务");
        post.setStatus(BlogService.STATUS_PUBLISHED);
        post.setDate(LocalDate.of(2026, 8, 1));
        post.setCategoryName("后端");
        post.setTags(List.of("spring", "quartz"));
        page.setRecords(List.of(post));
        page.setTotal(1L);
        page.setCurrent(1L);
        page.setSize(10L);
        page.setPages(1L);
        page.setHasMore(false);
        when(blogService.getMyBlogs(eq(BlogService.STATUS_PUBLISHED), eq("定时"), eq(1), eq(10)))
                .thenReturn(page);

        QueryPostsTool tool = new QueryPostsTool(blogService);
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
        assertThat(first.path("tags")).hasSize(2);
        verify(blogService).getMyBlogs(eq(BlogService.STATUS_PUBLISHED), eq("定时"), eq(1), eq(10));
    }

    @Test
    void resolvesUserContextForCallerAndClearsItAfter() {
        BlogService blogService = mock(BlogService.class);
        PageData<BlogPostVO> page = new PageData<>();
        page.setRecords(List.of());
        page.setTotal(0L);
        page.setCurrent(1L);
        page.setSize(10L);
        page.setPages(0L);
        page.setHasMore(false);
        when(blogService.getMyBlogs(eq(null), eq(""), eq(1), eq(10)))
                .thenAnswer(invocation -> {
                    assertThat(UserContext.getUserId()).isEqualTo(42L);
                    return page;
                });

        QueryPostsTool tool = new QueryPostsTool(blogService);
        tool.execute(42L, JsonNodeFactory.instance.objectNode(), new RecordingProgress());

        assertThat(UserContext.getUserId()).isNull();
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
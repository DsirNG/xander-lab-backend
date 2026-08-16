package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
    void returnsAllJuejinUnsyncedPostsFromSingleQuery() {
        BlogService blogService = mock(BlogService.class);
        BlogPostVO first = post(1L, "未同步文章一", false, true);
        BlogPostVO second = post(2L, "未同步文章二", false, false);
        when(blogService.listMyPostsAll(eq(null), eq(""), eq("juejin"))).thenReturn(List.of(first, second));

        QueryUnsyncedPostsTool tool = new QueryUnsyncedPostsTool(blogService);
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
        verify(blogService).listMyPostsAll(eq(null), eq(""), eq("juejin"));
    }

    @Test
    void csdnPlatformAndStatusArePassedThrough() {
        BlogService blogService = mock(BlogService.class);
        BlogPostVO csdnUnsynced = post(3L, "CSDN 未同步", true, false);
        when(blogService.listMyPostsAll(eq(BlogService.STATUS_PUBLISHED), eq(""), eq("csdn")))
                .thenReturn(List.of(csdnUnsynced));

        QueryUnsyncedPostsTool tool = new QueryUnsyncedPostsTool(blogService);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("platform", "csdn")
                .put("status", "published");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("platform").asText()).isEqualTo("csdn");
        assertThat(result.path("total").asLong()).isEqualTo(1L);
        assertThat(result.path("posts").get(0).path("id").asLong()).isEqualTo(3L);
        verify(blogService).listMyPostsAll(eq(BlogService.STATUS_PUBLISHED), eq(""), eq("csdn"));
    }

    @Test
    void clearsUserContextAfterExecution() {
        BlogService blogService = mock(BlogService.class);
        when(blogService.listMyPostsAll(eq(null), eq(""), eq("juejin")))
                .thenAnswer(invocation -> {
                    assertThat(UserContext.getUserId()).isEqualTo(42L);
                    return List.of();
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

    private static final class RecordingProgress implements AgentTool.ProgressListener {
        @Override
        public void onProgress(String message) {
        }

        @Override
        public void onToolDelta(String delta) {
        }
    }
}
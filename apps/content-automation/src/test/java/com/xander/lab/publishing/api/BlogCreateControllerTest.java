package com.xander.lab.publishing.api;

import com.xander.lab.common.Result;
import com.xander.lab.content.api.ContentPostInput;
import com.xander.lab.publishing.application.ContentAgentPostService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BlogCreateControllerTest {

    @Test
    void requiresGatewayIdentityBeforeCallingCore() {
        ContentAgentPostService service = mock(ContentAgentPostService.class);
        BlogCreateController controller = new BlogCreateController(service);
        BlogPostDTO request = new BlogPostDTO();
        request.setTitle("title");
        request.setContent("content");

        Result<BlogPostVO> result = controller.create(null, "request-1", request);

        assertThat(result.getCode()).isEqualTo(401);
    }

    @Test
    void forwardsPublishFlagAndIdempotencyKey() {
        ContentAgentPostService service = mock(ContentAgentPostService.class);
        BlogPostVO post = new BlogPostVO();
        post.setId(42L);
        when(service.createBlog(eq(7L), any(ContentPostInput.class), eq(false), eq("request-1")))
                .thenReturn(post);
        BlogCreateController controller = new BlogCreateController(service);

        Result<BlogPostVO> result = controller.create("7", "request-1", request());

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isSameAs(post);
    }

    private BlogPostDTO request() {
        BlogPostDTO request = new BlogPostDTO();
        request.setTitle("title");
        request.setContent("content");
        request.setPublish(false);
        return request;
    }
}

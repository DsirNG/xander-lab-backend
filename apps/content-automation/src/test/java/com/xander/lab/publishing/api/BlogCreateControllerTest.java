package com.xander.lab.publishing.api;

import com.xander.lab.common.Result;
import com.xander.lab.publishing.infrastructure.ContentBlogCreateClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BlogCreateControllerTest {

    @Test
    void requiresGatewayIdentityBeforeCallingCore() {
        ContentBlogCreateClient client = mock(ContentBlogCreateClient.class);
        BlogCreateController controller = new BlogCreateController(client);
        BlogPostDTO request = new BlogPostDTO();
        request.setTitle("title");
        request.setContent("content");

        Result<BlogPostVO> result = controller.create(null, "request-1", request);

        assertThat(result.getCode()).isEqualTo(401);
    }

    @Test
    void forwardsPublishFlagAndIdempotencyKey() {
        ContentBlogCreateClient client = mock(ContentBlogCreateClient.class);
        BlogPostVO post = new BlogPostVO();
        post.setId(42L);
        when(client.create(eq(7L), any(BlogPostDTO.class), eq(false), eq("request-1")))
                .thenReturn(post);
        BlogCreateController controller = new BlogCreateController(client);

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

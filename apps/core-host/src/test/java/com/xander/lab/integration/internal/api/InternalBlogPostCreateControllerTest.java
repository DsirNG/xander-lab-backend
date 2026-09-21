package com.xander.lab.integration.internal.api;

import com.xander.lab.content.api.ContentPostInput;
import com.xander.lab.content.api.ContentPost;
import com.xander.lab.content.api.ContentPostCreationPort;
import com.xander.lab.publishing.api.InternalBlogPostCreateController;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalBlogPostCreateControllerTest {

    @Test
    void createsWithExplicitOwnerAndPreservesIdempotencyKey() {
        ContentPostCreationPort contentPostCreationPort = mock(ContentPostCreationPort.class);
        ContentPost post = new ContentPost(42L, "title", "summary", "content", "frontend",
                "Frontend", "cover", List.of("java"), 7L, "Demo", null, "1 min", null,
                0, 1, false, false);
        ContentPostInput input = new ContentPostInput("title", "summary", "content",
                "frontend", "cover", List.of("java"));
        when(contentPostCreationPort.createIdempotent(eq(7L), eq(input), eq(true), eq("request-1")))
                .thenReturn(post);

        InternalBlogPostCreateController controller = controller(contentPostCreationPort);
        ContentPost result = controller.create("core-token",
                new InternalBlogPostCreateController.CreateRequest(7L, "title", "summary",
                        "content", "frontend", "cover", List.of("java"), true, "request-1"))
                .getBody();

        assertThat(result).isSameAs(post);
        verify(contentPostCreationPort).createIdempotent(eq(7L), eq(input), eq(true), eq("request-1"));
    }

    @Test
    void rejectsWrongInternalTokenBeforeWriting() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller(mock(ContentPostCreationPort.class)).create("wrong-token",
                        new InternalBlogPostCreateController.CreateRequest(7L, "title", null,
                                "content", null, null, null, true, null)));

        assertThat(exception.getStatusCode().value()).isEqualTo(404);
    }

    private InternalBlogPostCreateController controller(ContentPostCreationPort contentPostCreationPort) {
        return new InternalBlogPostCreateController(contentPostCreationPort, "core-token");
    }
}

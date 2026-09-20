package com.xander.lab.identity.api;

import com.xander.lab.content.api.ContentPost;
import com.xander.lab.content.api.ContentPostPage;
import com.xander.lab.content.api.ContentPostPort;
import com.xander.lab.content.api.ContentPostQuery;
import com.xander.lab.identity.application.SecurityStateSyncProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentContentPostControllerTest {

    @Test
    void forwardsAuthorizedContentOperationsAndPreservesNullFindResult() {
        ContentPostPort content = mock(ContentPostPort.class);
        ContentPostPage page = new ContentPostPage(List.of(), 0, 1, 0, 10, false);
        when(content.listMyPosts(42L, ContentPostQuery.NONE, 1, 10)).thenReturn(page);
        AgentContentPostController controller = new AgentContentPostController(content, properties());

        assertThat(controller.listMyPosts("sync-secret",
                new AgentContentPostController.ListMyPostsRequest(42L, ContentPostQuery.NONE, 1, 10)))
                .isSameAs(page);
        assertThat(controller.findOwned("sync-secret",
                new AgentContentPostController.FindOwnedRequest(42L, 99L))).isNull();
        verify(content).listMyPosts(42L, ContentPostQuery.NONE, 1, 10);
        verify(content).findOwned(42L, 99L);
    }

    @Test
    void hidesContentOperationsWithoutTheInternalToken() {
        ContentPostPort content = mock(ContentPostPort.class);
        AgentContentPostController controller = new AgentContentPostController(content, properties());

        assertThatThrownBy(() -> controller.countManaged("wrong-secret",
                new AgentContentPostController.UserRequest(42L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
        verifyNoInteractions(content);
    }

    private SecurityStateSyncProperties properties() {
        SecurityStateSyncProperties properties = new SecurityStateSyncProperties();
        properties.setInternalApiEnabled(true);
        properties.setInternalToken("sync-secret");
        return properties;
    }
}

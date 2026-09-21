package com.xander.lab.publishing.api;

import com.xander.lab.automation.host.ContentAutomationProperties;
import com.xander.lab.integration.api.BlogSyncResult;
import com.xander.lab.publishing.application.ContentExternalPublicationService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentExternalPublicationControllerTest {

    @Test
    void rejectsRequestsWithoutConfiguredInternalToken() {
        ContentAutomationProperties properties = new ContentAutomationProperties();
        ContentExternalPublicationController controller = new ContentExternalPublicationController(
                mock(ContentExternalPublicationService.class), properties);

        assertThatThrownBy(() -> controller.sync("wrong", 42L, "csdn",
                new ContentExternalPublicationController.SyncRequest(7L, 42L)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(404));
    }

    @Test
    void authorizesAndDelegatesExplicitOwnerAndPostIds() {
        ContentExternalPublicationService service = mock(ContentExternalPublicationService.class);
        ContentAutomationProperties properties = new ContentAutomationProperties();
        properties.setAgentInternalToken("agent-secret");
        BlogSyncResult expected = new BlogSyncResult("juejin", "article-1", "https://juejin.cn/post/article-1");
        when(service.sync(7L, 42L, "juejin")).thenReturn(expected);
        ContentExternalPublicationController controller = new ContentExternalPublicationController(service, properties);

        BlogSyncResult actual = controller.sync("agent-secret", 42L, "juejin",
                new ContentExternalPublicationController.SyncRequest(7L, 42L));

        assertThat(actual).isEqualTo(expected);
        verify(service).sync(7L, 42L, "juejin");
    }
}

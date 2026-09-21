package com.xander.lab.publishing.api;

import com.xander.lab.automation.host.ContentAutomationProperties;
import com.xander.lab.content.api.ContentPostPort;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ContentAgentPostControllerTest {

    @Test
    void rejectsMissingOrWrongInternalTokenBeforeCallingContentPort() {
        ContentPostPort content = mock(ContentPostPort.class);
        ContentAutomationProperties properties = new ContentAutomationProperties();
        properties.setAgentInternalToken("agent-secret");
        ContentAgentPostController controller = new ContentAgentPostController(content, properties);

        assertThatThrownBy(() -> controller.countManaged("wrong",
                new ContentAgentPostController.UserRequest(42L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
        verifyNoInteractions(content);
    }
}

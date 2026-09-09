package com.xander.lab.context;

import com.xander.lab.agent.application.AgentAttachmentService;
import com.xander.lab.integration.oss.OssService;
import com.xander.lab.publishing.application.BlogMediaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that Spring can instantiate the attachment service with its production constructor. */
@SpringBootTest(classes = AgentAttachmentService.class)
class AgentAttachmentServiceWiringTest {

    @MockBean
    private OssService ossService;

    @MockBean
    private BlogMediaService mediaService;

    @Autowired
    private AgentAttachmentService attachmentService;

    @Test
    void attachmentServiceUsesTheProductionConstructor() {
        assertThat(attachmentService).isNotNull();
    }
}

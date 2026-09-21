package com.xander.lab.integration.internal.api;

import com.xander.lab.integration.api.MailPort;
import com.xander.lab.integration.internal.application.IntegrationInternalApiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class InternalAutomationMailControllerTest {

    @Test
    void forwardsOnlyRenderedMailWithTheInternalToken() {
        MailPort mailPort = mock(MailPort.class);
        InternalAutomationMailController controller = controller(mailPort);

        controller.sendHtml("core-token",
                new InternalAutomationMailController.HtmlMailRequest(
                        "demo@example.com", "Subject", "plain", "<p>html</p>"));

        verify(mailPort).sendHtmlMail("demo@example.com", "Subject", "plain", "<p>html</p>");
    }

    @Test
    void rejectsWrongTokenBeforeDelivery() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller(mock(MailPort.class)).sendSimple("wrong-token",
                        new InternalAutomationMailController.SimpleMailRequest(
                                "demo@example.com", "Subject", "body")));

        org.assertj.core.api.Assertions.assertThat(exception.getStatusCode().value()).isEqualTo(404);
    }

    private InternalAutomationMailController controller(MailPort mailPort) {
        IntegrationInternalApiProperties properties = new IntegrationInternalApiProperties();
        properties.setInternalToken("core-token");
        return new InternalAutomationMailController(mailPort, properties);
    }
}

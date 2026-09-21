package com.xander.lab.integration.internal.api;

import com.xander.lab.integration.internal.application.IntegrationInternalApiProperties;
import com.xander.lab.payment.api.BillingPort;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalAutomationBillingControllerTest {

    @Test
    void exposesOnlyFlatPriceAndChargeOperations() {
        BillingPort billingPort = mock(BillingPort.class);
        when(billingPort.flatPoints("EMAIL_REMINDER")).thenReturn(5000L);
        InternalAutomationBillingController controller = controller(billingPort);

        assertThat(controller.flatPoints("core-token",
                new InternalAutomationBillingController.BizTypeRequest("EMAIL_REMINDER")).points())
                .isEqualTo(5000L);
        controller.chargeFlat("core-token",
                new InternalAutomationBillingController.ChargeFlatRequest(
                        7L, "EMAIL_REMINDER", "email:7:1", "task:7", 5000L, "sent"));

        verify(billingPort).chargeFlat(7L, "EMAIL_REMINDER", "email:7:1", "task:7", 5000L, "sent");
    }

    @Test
    void rejectsWrongTokenBeforeBilling() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller(mock(BillingPort.class)).flatPoints("wrong-token",
                        new InternalAutomationBillingController.BizTypeRequest("EMAIL_REMINDER")));

        assertThat(exception.getStatusCode().value()).isEqualTo(404);
    }

    private InternalAutomationBillingController controller(BillingPort billingPort) {
        IntegrationInternalApiProperties properties = new IntegrationInternalApiProperties();
        properties.setInternalToken("core-token");
        return new InternalAutomationBillingController(billingPort, properties);
    }
}

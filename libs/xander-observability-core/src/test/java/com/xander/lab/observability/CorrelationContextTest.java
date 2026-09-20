package com.xander.lab.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CorrelationContextTest {

    @Test
    void preservesSafeInboundIdentifiers() {
        CorrelationContext context = CorrelationContext.fromHeaders("trace-123", "request_456");

        assertThat(context.traceId()).isEqualTo("trace-123");
        assertThat(context.requestId()).isEqualTo("request_456");
    }

    @Test
    void replacesMissingAndUnsafeInboundIdentifiers() {
        CorrelationContext context = CorrelationContext.fromHeaders(" trace-123 ", "bad\r\nX-Leak: value");

        assertThat(context.traceId()).isEqualTo("trace-123");
        assertThat(context.requestId()).matches("[A-Za-z0-9-]{36}");
    }

    @Test
    void directConstructionRejectsUnsafeIdentifiers() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CorrelationContext("trace", "bad\nvalue"));
    }
}

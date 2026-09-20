package com.xander.lab.gateway;

import com.xander.lab.observability.CorrelationHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayCorrelationFilterTest {

    @Test
    void forwardsSafeCorrelationIdentifiersAndReturnsTraceId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/example")
                        .header(CorrelationHeaders.TRACE_ID, "trace-123")
                        .header(CorrelationHeaders.REQUEST_ID, "request-456")
                        .build());
        GatewayFilterChain chain = filtered -> {
            assertThat(filtered.getRequest().getHeaders().getFirst(CorrelationHeaders.TRACE_ID))
                    .isEqualTo("trace-123");
            assertThat(filtered.getRequest().getHeaders().getFirst(CorrelationHeaders.REQUEST_ID))
                    .isEqualTo("request-456");
            return Mono.empty();
        };

        new GatewayCorrelationFilter().filter(exchange, chain).block();

        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationHeaders.TRACE_ID))
                .isEqualTo("trace-123");
    }

    @Test
    void replacesMissingIdentifiersBeforeForwarding() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/example").build());
        GatewayFilterChain chain = filtered -> {
            assertThat(filtered.getRequest().getHeaders().getFirst(CorrelationHeaders.TRACE_ID))
                    .isNotBlank();
            assertThat(filtered.getRequest().getHeaders().getFirst(CorrelationHeaders.REQUEST_ID))
                    .isNotBlank();
            return Mono.empty();
        };

        new GatewayCorrelationFilter().filter(exchange, chain).block();

        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationHeaders.TRACE_ID))
                .isNotBlank();
    }
}

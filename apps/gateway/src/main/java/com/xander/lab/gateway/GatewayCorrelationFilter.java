package com.xander.lab.gateway;

import com.xander.lab.observability.CorrelationContext;
import com.xander.lab.observability.CorrelationHeaders;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Creates or accepts a safe correlation context at the edge, forwards both identifiers to the
 * selected service, and exposes only the trace identifier to the client response.
 *
 * <p>Reactive requests must not use the Servlet {@code ThreadLocal} identity holder or a
 * thread-bound MDC. The context is therefore placed in Reactor context for later gateway filters
 * and adapters to consume.</p>
 */
@Component
public class GatewayCorrelationFilter implements GlobalFilter, Ordered {

    public static final String REACTOR_CONTEXT_KEY = GatewayCorrelationFilter.class.getName();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        CorrelationContext correlation = CorrelationContext.fromHeaders(
                exchange.getRequest().getHeaders().getFirst(CorrelationHeaders.TRACE_ID),
                exchange.getRequest().getHeaders().getFirst(CorrelationHeaders.REQUEST_ID));

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(CorrelationHeaders.TRACE_ID);
                    headers.remove(CorrelationHeaders.REQUEST_ID);
                    headers.set(CorrelationHeaders.TRACE_ID, correlation.traceId());
                    headers.set(CorrelationHeaders.REQUEST_ID, correlation.requestId());
                })
                .build();
        exchange.getResponse().getHeaders().set(CorrelationHeaders.TRACE_ID, correlation.traceId());

        return chain.filter(exchange.mutate().request(request).build())
                .contextWrite(context -> context.put(REACTOR_CONTEXT_KEY, correlation));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}

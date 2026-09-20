package com.xander.lab.gateway;

import com.xander.lab.auth.core.JwtUtil;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayAuthenticationFilterTest {

    @Test
    void acceptsWebsocketQueryTokenWhenAuthorizationHeaderIsAbsent() {
        JwtUtil jwtUtil = mock(JwtUtil.class);
        Claims claims = mock(Claims.class);
        when(jwtUtil.parseToken("query-token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("42");
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/ws/agent?token=query-token").build());

        new GatewayAuthenticationFilter(new GatewayAccessPolicy(), jwtUtil,
                new GatewaySecurityStateCache(),
                new GatewaySecurityPlaneReadiness(new GatewaySecurityStateProperties()))
                .filter(exchange, chain).block();

        verify(jwtUtil).parseToken("query-token");
        verify(chain).filter(org.mockito.ArgumentMatchers.any());
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void prefersAuthorizationHeaderOverWebsocketQueryToken() {
        JwtUtil jwtUtil = mock(JwtUtil.class);
        Claims claims = mock(Claims.class);
        when(jwtUtil.parseToken("header-token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("42");
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/ws/agent?token=query-token")
                        .header("Authorization", "Bearer header-token")
                        .build());

        new GatewayAuthenticationFilter(new GatewayAccessPolicy(), jwtUtil,
                new GatewaySecurityStateCache(),
                new GatewaySecurityPlaneReadiness(new GatewaySecurityStateProperties()))
                .filter(exchange, chain).block();

        verify(jwtUtil).parseToken("header-token");
        verify(jwtUtil, never()).parseToken("query-token");
        verify(chain).filter(org.mockito.ArgumentMatchers.any());
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void rejectsDisabledAccountBeforeForwarding() {
        JwtUtil jwtUtil = mock(JwtUtil.class);
        Claims claims = mock(Claims.class);
        GatewaySecurityStateCache cache = new GatewaySecurityStateCache();
        cache.apply(42L, true, 1L);
        when(jwtUtil.parseToken("access-token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("42");
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/blog/posts")
                        .header("Authorization", "Bearer access-token")
                        .build());

        new GatewayAuthenticationFilter(new GatewayAccessPolicy(), jwtUtil, cache,
                new GatewaySecurityPlaneReadiness(new GatewaySecurityStateProperties()))
                .filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(chain, never()).filter(org.mockito.ArgumentMatchers.any());
    }
}

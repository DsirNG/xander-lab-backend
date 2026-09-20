package com.xander.lab.gateway;

import com.xander.lab.auth.core.JwtUtil;
import io.jsonwebtoken.Claims;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Enforces the access side of V9 §10 at the edge. Refresh rotation and business authorization
 * remain downstream responsibilities; the Gateway only validates the signed access JWT and the
 * policy's optional administrator role.
 */
@Component
public class GatewayAuthenticationFilter implements GlobalFilter, Ordered {

    private final GatewayAccessPolicy accessPolicy;
    private final JwtUtil jwtUtil;
    private final GatewaySecurityStateCache securityStateCache;
    private final GatewaySecurityPlaneReadiness securityPlaneReadiness;

    public GatewayAuthenticationFilter(GatewayAccessPolicy accessPolicy,
                                       JwtUtil jwtUtil,
                                       GatewaySecurityStateCache securityStateCache,
                                       GatewaySecurityPlaneReadiness securityPlaneReadiness) {
        this.accessPolicy = accessPolicy;
        this.jwtUtil = jwtUtil;
        this.securityStateCache = securityStateCache;
        this.securityPlaneReadiness = securityPlaneReadiness;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        GatewayAccessPolicy.Decision decision = accessPolicy.decide(
                exchange.getRequest().getMethod(), exchange.getRequest().getPath().value());
        if (!decision.requiresToken()) {
            return chain.filter(exchange);
        }
        if (!securityPlaneReadiness.isReady()) {
            return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE, "安全平面尚未就绪");
        }

        String token = token(exchange, decision.tokenLocation());
        if (token == null || token.isBlank()) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }

        Claims claims;
        try {
            claims = jwtUtil.parseToken(token);
        } catch (RuntimeException ex) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        long accountId;
        try {
            accountId = Long.parseLong(claims.getSubject());
        } catch (RuntimeException ex) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        if (securityStateCache.isDisabled(accountId)) {
            return reject(exchange, HttpStatus.FORBIDDEN, "账号已被禁用");
        }
        if (decision.access() == GatewayAccessPolicy.Access.ADMIN
                && !"ADMIN".equals(claims.get("role", String.class))) {
            return reject(exchange, HttpStatus.FORBIDDEN, "无权限访问");
        }
        return chain.filter(exchange);
    }

    private String token(ServerWebExchange exchange, String tokenLocation) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring("Bearer ".length()).trim();
        }
        if ("query".equals(tokenLocation) || "header_or_query".equals(tokenLocation)) {
            return exchange.getRequest().getQueryParams().getFirst("token");
        }
        return null;
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":" + status.value()
                + ",\"message\":\"" + message + "\",\"data\":null}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(
                exchange.getResponse().bufferFactory().wrap(bytes)));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}

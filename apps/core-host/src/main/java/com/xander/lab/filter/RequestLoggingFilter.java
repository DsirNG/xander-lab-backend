package com.xander.lab.filter;

import com.xander.lab.observability.CorrelationContext;
import com.xander.lab.observability.CorrelationHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Enumeration;

/**
 * Global Request Logging Filter
 * Logs request/response metadata only. Request and response bodies are never
 * logged, because they may contain passwords, verification codes, refresh
 * tokens or JWTs (e.g. /api/auth/login, /api/auth/refresh). Sensitive headers
 * are fully redacted rather than partially truncated.
 */
@Slf4j
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final String REDACTED = "[REDACTED]";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // SSE/event streams have no useful request body and produce noisier
        // completion logs; skip them entirely.
        String uri = request.getRequestURI();
        return (uri.startsWith("/api/blog-agent/tasks/")
                && (uri.endsWith("/stream") || uri.endsWith("/events")))
                || (uri.startsWith("/api/agent/conversations/")
                && (uri.endsWith("/stream") || uri.endsWith("/events")))
                || (uri.startsWith("/api/img2three/tasks/") && uri.endsWith("/run/stream"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        CorrelationContext correlation = CorrelationContext.fromHeaders(
                request.getHeader(CorrelationHeaders.TRACE_ID),
                request.getHeader(CorrelationHeaders.REQUEST_ID));
        response.setHeader(CorrelationHeaders.TRACE_ID, correlation.traceId());
        String previousTraceId = MDC.get(CorrelationHeaders.MDC_TRACE_ID);
        String previousRequestId = MDC.get(CorrelationHeaders.MDC_REQUEST_ID);
        long startTime = System.currentTimeMillis();
        MDC.put(CorrelationHeaders.MDC_TRACE_ID, correlation.traceId());
        MDC.put(CorrelationHeaders.MDC_REQUEST_ID, correlation.requestId());
        try {
            log.info("Received request: method={} uri={} traceId={} requestId={}",
                    request.getMethod(), request.getRequestURI(), correlation.traceId(), correlation.requestId());
            filterChain.doFilter(request, response);
        } finally {
            logRequestMetadata(request, response, correlation, System.currentTimeMillis() - startTime);
            restoreMdc(CorrelationHeaders.MDC_TRACE_ID, previousTraceId);
            restoreMdc(CorrelationHeaders.MDC_REQUEST_ID, previousRequestId);
        }
    }

    private void logRequestMetadata(HttpServletRequest request, HttpServletResponse response,
                                    CorrelationContext correlation, long duration) {
        String headers = collectHeaders(request);
        String queryString = safeQueryPresence(request.getQueryString());

        if (response.getStatus() >= 400) {
            log.warn("\nRequest: {} {} traceId={} requestId={} query={} status={} time={}ms headers={}",
                    request.getMethod(), request.getRequestURI(),
                    correlation.traceId(), correlation.requestId(),
                    queryString,
                    response.getStatus(), duration, headers);
        } else {
            log.info("\nRequest: {} {} traceId={} requestId={} query={} status={} time={}ms headers={}",
                    request.getMethod(), request.getRequestURI(),
                    correlation.traceId(), correlation.requestId(),
                    queryString,
                    response.getStatus(), duration, headers);
        }
    }

    private void restoreMdc(String key, String previousValue) {
        if (previousValue == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previousValue);
        }
    }

    /** Query values may contain exchange codes, tokens, email addresses, or other secrets. */
    private String safeQueryPresence(String queryString) {
        return queryString == null || queryString.isBlank() ? "-" : "[PRESENT]";
    }

    /**
     * Collect safe headers, fully redacting any header that carries credentials.
     * Never expose Authorization, Cookie or Set-Cookie values, not even partially.
     */
    private String collectHeaders(HttpServletRequest request) {
        StringBuilder sb = new StringBuilder();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames != null && headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            sb.append(name).append('=')
                    .append(isSensitiveHeader(name) ? REDACTED : request.getHeader(name))
                    .append(' ');
        }
        return sb.toString();
    }

    private boolean isSensitiveHeader(String name) {
        return "authorization".equalsIgnoreCase(name)
                || "cookie".equalsIgnoreCase(name)
                || "x-csrf-token".equalsIgnoreCase(name)
                || "proxy-authorization".equalsIgnoreCase(name)
                || CorrelationHeaders.TRACE_ID.equalsIgnoreCase(name)
                || CorrelationHeaders.REQUEST_ID.equalsIgnoreCase(name);
    }
}

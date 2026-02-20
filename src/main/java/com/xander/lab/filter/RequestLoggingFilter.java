package com.xander.lab.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Enumeration;

/**
 * Global Request Logging Filter
 * Logs inbound requests and outbound responses, including headers, parameters, and status.
 * Helps in debugging 404s and monitoring API traffic.
 */
@Slf4j
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final int MAX_PAYLOAD_LENGTH = 1000; // Truncate logs if body is too long

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // Wrap request and response to read body content (if needed) without consuming the stream
        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);

        long startTime = System.currentTimeMillis();
        try {
            // Log Request Basics
            logRequest(requestWrapper);

            // Proceed with the filter chain using wrappers
            filterChain.doFilter(requestWrapper, responseWrapper);
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            
            // Log Response Details
            logResponse(requestWrapper, responseWrapper, duration);
            
            // IMPORTANT: Copy content back to the original response
            responseWrapper.copyBodyToResponse();
        }
    }

    private void logRequest(ContentCachingRequestWrapper request) {
        StringBuilder msg = new StringBuilder();
        msg.append("\n================ INCOMING REQUEST ================\n");
        msg.append("Address: ").append(request.getRemoteAddr()).append("\n");
        msg.append("Method : ").append(request.getMethod()).append("\n");
        msg.append("URI    : ").append(request.getRequestURI()).append("\n");
        
        String queryString = request.getQueryString();
        if (queryString != null) {
            msg.append("Query  : ").append(queryString).append("\n");
        }

        // Log Headers (Optional, useful for auth/client info)
        msg.append("Headers: [");
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            // Skip sensitive headers if necessary (e.g. Authorization, Cookie)? 
            // Logging Authorization token might be useful for debugging but also security risk.
            // Let's log keys for now or truncate.
            msg.append(headerName).append(": ").append(maskSensitive(headerName, request.getHeader(headerName))).append(", ");
        }
        msg.append("]\n");

        log.info(msg.toString());
    }

    private void logResponse(ContentCachingRequestWrapper request, ContentCachingResponseWrapper response, long duration) {
        StringBuilder msg = new StringBuilder();
        msg.append("\n================ OUTGOING RESPONSE ================\n");
        msg.append("Method : ").append(request.getMethod()).append("\n");
        msg.append("URI    : ").append(request.getRequestURI()).append("\n");
        msg.append("Status : ").append(response.getStatus()).append("\n");
        msg.append("Time   : ").append(duration).append(" ms\n");

        // Log Request Body (read from wrapper buffer)
        byte[] requestBody = request.getContentAsByteArray();
        if (requestBody.length > 0) {
            String bodyString = new String(requestBody, StandardCharsets.UTF_8);
            msg.append("Req Body: ").append(truncate(bodyString)).append("\n");
        }

        // Log Response Body (read from wrapper buffer)
        // Only log response body for non-success or specific content types (e.g. JSON)
        // to avoid logging binary files or huge HTML.
        String contentType = response.getContentType();
        if (contentType != null && (contentType.contains("json") || contentType.contains("text") || contentType.contains("xml"))) {
            byte[] responseBody = response.getContentAsByteArray();
            if (responseBody.length > 0) {
                String bodyString = new String(responseBody, StandardCharsets.UTF_8);
                msg.append("Res Body: ").append(truncate(bodyString)).append("\n");
            }
        }
        
        msg.append("==================================================\n");

        if (response.getStatus() >= 400) {
            log.warn(msg.toString());
        } else {
            log.info(msg.toString());
        }
    }

    private String maskSensitive(String header, String value) {
        if ("Authorization".equalsIgnoreCase(header)) {
            if (value != null && value.length() > 20) {
                return value.substring(0, 15) + "...";
            }
        }
        return value;
    }

    private String truncate(String content) {
        if (content.length() > MAX_PAYLOAD_LENGTH) {
            return content.substring(0, MAX_PAYLOAD_LENGTH) + "... (truncated)";
        }
        return content;
    }
}

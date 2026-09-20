package com.xander.lab.integration.mcp.security.api;

import com.xander.lab.integration.mcp.security.McpOAuthException;
import com.xander.lab.integration.mcp.security.McpOAuthProperties;
import com.xander.lab.integration.mcp.security.McpOAuthRequestContext;
import com.xander.lab.integration.mcp.security.application.McpOAuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Applies one standard OAuth bearer-token challenge to every public MCP transport endpoint. */
@Configuration
@RequiredArgsConstructor
public class McpOAuthBearerFilterConfiguration {
    private final McpOAuthService oauthService;
    private final McpOAuthProperties properties;

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> mcpOAuthBearerFilter() {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilterAsyncDispatch() {
                // The MCP Streamable HTTP transport may invoke a tool on an async dispatch. Re-authenticate
                // that dispatch so its thread has the OAuth principal and granted scopes.
                return false;
            }

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws IOException, ServletException {
                try {
                    McpOAuthRequestContext.Principal principal = oauthService.authenticateBearer(request.getHeader("Authorization"));
                    if (principal == null) {
                        challenge(request, response, "invalid_token", "A valid bearer token is required");
                        return;
                    }
                    McpOAuthRequestContext.set(principal.userId(), principal.clientId(), principal.scopes());
                    try { chain.doFilter(request, response); }
                    finally { McpOAuthRequestContext.clear(); }
                } catch (McpOAuthException exception) {
                    challenge(request, response, exception.error(), exception.getMessage(), exception.status());
                }
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/mcp", "/api/mcp", "/api/mcp/csdn", "/api/mcp/dual", "/api/mcp/juejin");
        registration.setOrder(-101);
        return registration;
    }

    private void challenge(HttpServletRequest request, HttpServletResponse response, String error, String description) throws IOException {
        challenge(request, response, error, description, HttpServletResponse.SC_UNAUTHORIZED);
    }

    private void challenge(HttpServletRequest request, HttpServletResponse response, String error, String description, int status) throws IOException {
        String resource = properties.getPublicBaseUrl() + request.getRequestURI();
        String metadata = properties.getPublicBaseUrl() + "/.well-known/oauth-protected-resource?resource="
                + URLEncoder.encode(resource, StandardCharsets.UTF_8);
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("WWW-Authenticate", "Bearer resource_metadata=\"" + metadata + "\", error=\"" + error + "\"");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + error + "\",\"error_description\":\"" + description.replace("\"", "'") + "\"}");
    }

}

package com.xander.lab.integration.mcp.security.api;

import com.xander.lab.integration.mcp.security.McpOAuthProperties;
import com.xander.lab.integration.mcp.security.McpOAuthRequestContext;
import com.xander.lab.integration.mcp.security.application.McpOAuthService;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies OAuth context is restored for asynchronous MCP transport dispatches. */
class McpOAuthBearerFilterConfigurationTest {

    @AfterEach
    void clearContext() {
        McpOAuthRequestContext.clear();
    }

    @Test
    void authenticatesAnAsyncDispatchBeforeInvokingTheMcpTransport() throws Exception {
        McpOAuthService oauthService = mock(McpOAuthService.class);
        McpOAuthProperties properties = new McpOAuthProperties();
        when(oauthService.authenticateBearer("Bearer access-token")).thenReturn(
                new McpOAuthRequestContext.Principal(42L, "codex", Set.of("blog:write")));
        FilterRegistrationBean<OncePerRequestFilter> registration =
                new McpOAuthBearerFilterConfiguration(oauthService, properties).mcpOAuthBearerFilter();
        assertTrue(registration.getUrlPatterns().contains("/mcp"));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mcp");
        request.setDispatcherType(DispatcherType.ASYNC);
        request.addHeader("Authorization", "Bearer access-token");

        registration.getFilter().doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) ->
                assertEquals(42L, McpOAuthRequestContext.userId()));

        verify(oauthService).authenticateBearer("Bearer access-token");
    }
}

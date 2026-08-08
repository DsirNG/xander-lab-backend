package com.xander.lab.identity.security;

import com.xander.lab.identity.application.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthInterceptorTest {
    private JwtUtil jwtUtil;
    private AuthService authService;
    private AuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        jwtUtil = mock(JwtUtil.class);
        authService = mock(AuthService.class);
        interceptor = new AuthInterceptor(jwtUtil, authService);
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void protectedRouteRejectsMissingToken() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/blog/posts");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void protectedRouteSetsAuthenticatedUser() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/blog/posts");
        request.addHeader("Authorization", "Bearer access-token");
        request.addHeader("X-Forwarded-For", "203.0.113.8, 10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(authService.validateAccessToken("access-token")).thenReturn(true);
        when(jwtUtil.getSubject("access-token")).thenReturn("42");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(UserContext.getUserId()).isEqualTo(42L);
        assertThat(UserContext.getClientIp()).isEqualTo("203.0.113.8");
    }

    @Test
    void publicRouteAcceptsAnonymousRequestAndCompletionClearsContext() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/blog/posts");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        UserContext.setUserId(9L);
        interceptor.afterCompletion(request, response, new Object(), null);
        assertThat(UserContext.getUserId()).isNull();
    }

    @Test
    void publicRouteUsesValidOptionalToken() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/blog/posts");
        request.addHeader("Authorization", "Bearer optional-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(authService.validateAccessToken("optional-token")).thenReturn(true);
        when(jwtUtil.getSubject("optional-token")).thenReturn("5");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(UserContext.getUserId()).isEqualTo(5L);
        verify(authService).validateAccessToken("optional-token");
    }

    @Test
    void unmatchedWriteRouteRequiresAuthentication() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/flow/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void unmatchedWriteRouteAcceptsValidToken() {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/flow/canvases/9/snapshot");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(authService.validateAccessToken("valid-token")).thenReturn(true);
        when(jwtUtil.getSubject("valid-token")).thenReturn("42");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(UserContext.getUserId()).isEqualTo(42L);
    }

    @Test
    void readRoutesStayPublicWithoutToken() {
        MockHttpServletRequest menuRequest = new MockHttpServletRequest("GET", "/api/components/menu");
        MockHttpServletRequest detailRequest = new MockHttpServletRequest("GET", "/api/components/toast");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(menuRequest, response, new Object())).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(interceptor.preHandle(detailRequest, response, new Object())).isTrue();
    }

    @Test
    void viewCountAndComponentShareArePublicWrites() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest viewRequest = new MockHttpServletRequest("POST", "/api/blog/posts/5/view");
        MockHttpServletRequest shareRequest = new MockHttpServletRequest("POST", "/api/components/share");

        assertThat(interceptor.preHandle(viewRequest, response, new Object())).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(interceptor.preHandle(shareRequest, response, new Object())).isTrue();
    }

    @Test
    void csdnAuthorizationEndpointsRequireAuthentication() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest statusRequest = new MockHttpServletRequest("GET", "/api/publishing/csdn/authorization/status");
        MockHttpServletRequest startRequest = new MockHttpServletRequest("POST", "/api/publishing/csdn/authorization/start");

        assertThat(interceptor.preHandle(statusRequest, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(interceptor.preHandle(startRequest, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void mcpEndpointsRemainExcluded() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest tokenRequest = new MockHttpServletRequest("POST", "/api/mcp/oauth/token");
        MockHttpServletRequest transportRequest = new MockHttpServletRequest("POST", "/api/mcp");

        assertThat(interceptor.preHandle(tokenRequest, response, new Object())).isTrue();
        assertThat(interceptor.preHandle(transportRequest, response, new Object())).isTrue();
    }
}

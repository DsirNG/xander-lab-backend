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
}

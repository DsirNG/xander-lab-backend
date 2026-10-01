package com.xander.lab.identity.security;

import com.xander.lab.identity.application.AuthService;
import io.jsonwebtoken.Claims;
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
    void preflightOptionsPassesThroughWithoutToken() {
        // CORS 预检（OPTIONS）不带凭据，必须放行返回 2xx，否则真实跨域请求会被浏览器拦截。
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/admin/wechat-credential");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void protectedRouteRejectsMissingToken() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/blog/posts");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void profileOverviewRequiresAuthentication() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/profile/overview");
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
    void wechatVirtualPayNotificationUsesItsOwnSignatureInsteadOfJwt() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/virtual-pay/notify");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void virtualPayOrderStatusRequiresAuthenticationButCatalogStaysPublic() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/virtual-pay/orders/order-1"), response, new Object()))
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(401);

        response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/virtual-pay/products"), response, new Object()))
                .isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
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
    void canvasSnapshotReadRequiresAuthentication() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/flow/canvases/9/snapshot");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void agentConversationEventRecoveryRequiresAuthentication() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/agent/conversations/9/events");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void personalKnowledgeAndAttemptReadsRequireAuthentication() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/recitations/materials"), response, new Object()))
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(401);

        response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/recitations/attempts/9"), response, new Object()))
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void agentImagesGalleryReadRequiresAuthentication() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/blog/media/images/agent");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);

        request.addHeader("Authorization", "Bearer valid-token");
        when(authService.validateAccessToken("valid-token")).thenReturn(true);
        when(jwtUtil.getSubject("valid-token")).thenReturn("42");
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(UserContext.getUserId()).isEqualTo(42L);
    }

    @Test
    void agentConversationMessageStreamRequiresAuthentication() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/agent/conversations/9/messages/stream");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    /**
     * {@code POST /{id}/resume} 必须登录。
     *
     * <p>它没有单独的登记项——覆盖它的是 {@code POST:/api/agent/conversations/*}，而那个 `/*`
     * 是**前缀**通配（任意深度）。这条用例把"前缀通配确实覆盖到了子路径"钉成事实：
     * 一旦有人把 `matchPath` 改成"只匹配一层"（那是个很自然的收敛），会话子路径会集体变成
     * 依赖末尾兜底，而 {@code resume} 会成为一个可以匿名触发他人会话执行的入口。
     * 同类子路径（messages/stream、events）也各有一条，理由相同。</p>
     */
    @Test
    void agentConversationResumeRequiresAuthentication() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/agent/conversations/9/resume");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void agentConversationResumeAcceptsValidToken() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/agent/conversations/9/resume");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer valid-token");
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

    @Test
    void adminRouteRejectsAnonymousRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/users");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void adminRouteRejectsNonAdminRole() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/users");
        request.addHeader("Authorization", "Bearer user-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Claims claims = mock(Claims.class);
        when(authService.validateAccessToken("user-token")).thenReturn(true);
        when(jwtUtil.getSubject("user-token")).thenReturn("7");
        when(jwtUtil.parseToken("user-token")).thenReturn(claims);
        when(claims.get("role", String.class)).thenReturn("USER");

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void adminRouteAcceptsAdminRole() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/users");
        request.addHeader("Authorization", "Bearer admin-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Claims claims = mock(Claims.class);
        when(authService.validateAccessToken("admin-token")).thenReturn(true);
        when(jwtUtil.getSubject("admin-token")).thenReturn("1");
        when(jwtUtil.parseToken("admin-token")).thenReturn(claims);
        when(claims.get("role", String.class)).thenReturn("ADMIN");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(UserContext.getUserId()).isEqualTo(1L);
    }

    @Test
    void adminRouteWithUnparseableTokenIsTreatedAsLoggedOut() {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/admin/model-providers/3");
        request.addHeader("Authorization", "Bearer broken-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(authService.validateAccessToken("broken-token")).thenReturn(true);
        when(jwtUtil.getSubject("broken-token")).thenReturn("1");
        when(jwtUtil.parseToken("broken-token")).thenThrow(new RuntimeException("expired"));

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void blogAgentTaskListRequiresAuthentication() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/blog-agent/tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);

        request.addHeader("Authorization", "Bearer valid-token");
        when(authService.validateAccessToken("valid-token")).thenReturn(true);
        when(jwtUtil.getSubject("valid-token")).thenReturn("42");
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(UserContext.getUserId()).isEqualTo(42L);
    }

    @Test
    void blogPlanWriteRoutesRequireAuthentication() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest aiRequest = new MockHttpServletRequest("POST", "/api/blog-plans/ai-generate");
        MockHttpServletRequest topicRequest = new MockHttpServletRequest("POST", "/api/blog-plans/topics/generate");
        MockHttpServletRequest triggerRequest = new MockHttpServletRequest("POST", "/api/blog-plans/9/trigger");

        assertThat(interceptor.preHandle(aiRequest, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(interceptor.preHandle(topicRequest, response, new Object())).isFalse();
        assertThat(interceptor.preHandle(triggerRequest, response, new Object())).isFalse();
    }

    @Test
    void blogPlanReadRoutesRequireAuthentication() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest runsRequest = new MockHttpServletRequest("GET", "/api/blog-plans/9/runs");
        MockHttpServletRequest nextRequest = new MockHttpServletRequest("GET", "/api/blog-plans/9/next");

        assertThat(interceptor.preHandle(runsRequest, response, new Object())).isFalse();
        assertThat(interceptor.preHandle(nextRequest, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void blogSyncWriteRoutesRequireAuthentication() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest csdnRequest = new MockHttpServletRequest("POST", "/api/blog/posts/5/sync/csdn");
        MockHttpServletRequest juejinRequest = new MockHttpServletRequest("POST", "/api/blog/posts/5/sync/juejin");

        assertThat(interceptor.preHandle(csdnRequest, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(interceptor.preHandle(juejinRequest, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void uploadRoutesRequireAuthentication() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest singleRequest = new MockHttpServletRequest("POST", "/api/upload");
        MockHttpServletRequest batchRequest = new MockHttpServletRequest("POST", "/api/upload/batch");
        MockHttpServletRequest ossRequest = new MockHttpServletRequest("POST", "/api/upload/oss");

        assertThat(interceptor.preHandle(singleRequest, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(interceptor.preHandle(batchRequest, response, new Object())).isFalse();
        assertThat(interceptor.preHandle(ossRequest, response, new Object())).isFalse();
    }

    @Test
    void conversationShareDeleteRequiresAuthentication() {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/agent/conversations/9/share");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void downloadAndExportRoutesStayPublic() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest downloadRequest = new MockHttpServletRequest("GET", "/api/download/a1b2c3.png");
        MockHttpServletRequest csvRequest = new MockHttpServletRequest("GET", "/api/export/csv");
        MockHttpServletRequest excelRequest = new MockHttpServletRequest("GET", "/api/export/excel");

        assertThat(interceptor.preHandle(downloadRequest, response, new Object())).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(interceptor.preHandle(csvRequest, response, new Object())).isTrue();
        assertThat(interceptor.preHandle(excelRequest, response, new Object())).isTrue();
    }

    @Test
    void notificationTestBroadcastMovedUnderAdminPrefix() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest legacyRequest = new MockHttpServletRequest("PATCH", "/api/notifications/admin/test-broadcast");
        MockHttpServletRequest adminRequest = new MockHttpServletRequest("PATCH", "/api/admin/notifications/test-broadcast");

        // 旧路由不再被通配 PATCH:/api/notifications/* 兜底，也不在公开列表内。
        assertThat(interceptor.preHandle(legacyRequest, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        // 新 admin 路由匿名访问 401。
        assertThat(interceptor.preHandle(adminRequest, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }
}

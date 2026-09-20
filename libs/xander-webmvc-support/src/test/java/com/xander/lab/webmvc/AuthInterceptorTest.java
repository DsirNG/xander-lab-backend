package com.xander.lab.webmvc;

import com.xander.lab.auth.core.JwtUtil;
import com.xander.lab.auth.core.UserContext;
import com.xander.lab.core.api.AccessTokenLookupPort;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthInterceptorTest {
    private JwtUtil jwtUtil;
    private AccessTokenLookupPort accessTokenLookupPort;
    private AuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        jwtUtil = mock(JwtUtil.class);
        accessTokenLookupPort = mock(AccessTokenLookupPort.class);
        interceptor = new AuthInterceptor(jwtUtil, accessTokenLookupPort);
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
        when(accessTokenLookupPort.resolveUserId("access-token")).thenReturn(42L);

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
        when(accessTokenLookupPort.resolveUserId("optional-token")).thenReturn(5L);

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(UserContext.getUserId()).isEqualTo(5L);
        verify(accessTokenLookupPort).resolveUserId("optional-token");
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
        when(accessTokenLookupPort.resolveUserId("valid-token")).thenReturn(42L);

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
        when(accessTokenLookupPort.resolveUserId("valid-token")).thenReturn(42L);
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
        when(accessTokenLookupPort.resolveUserId("user-token")).thenReturn(7L);
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
        when(accessTokenLookupPort.resolveUserId("admin-token")).thenReturn(1L);
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
        when(accessTokenLookupPort.resolveUserId("broken-token")).thenReturn(1L);
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
        when(accessTokenLookupPort.resolveUserId("valid-token")).thenReturn(42L);
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

    /**
     * 受保护路径上，端口答不出身份时必须 401（fail-closed），且不再触碰 {@code jwtUtil}。
     */
    @Test
    void protectedRouteRejectsTokenThePortCannotResolve() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/blog/posts");
        request.addHeader("Authorization", "Bearer unparseable-subject");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(accessTokenLookupPort.resolveUserId("unparseable-subject")).thenReturn(null);

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(UserContext.getUserId()).isNull();
        verify(jwtUtil, never()).parseToken(anyString());
    }

    /**
     * 公开路径上拿不到身份时按匿名放行，不阻断请求。
     */
    @Test
    void publicRouteTreatsUnresolvableTokenAsAnonymous() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/blog/posts");
        request.addHeader("Authorization", "Bearer unparseable-subject");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(accessTokenLookupPort.resolveUserId("unparseable-subject")).thenReturn(null);

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(UserContext.getUserId()).isNull();
    }
}

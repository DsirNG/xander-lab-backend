package com.xander.lab.integration.mcp.security.application;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xander.lab.core.api.AccessTokenLookupPort;
import com.xander.lab.integration.mcp.security.McpOAuthException;
import com.xander.lab.integration.mcp.security.McpOAuthProperties;
import com.xander.lab.integration.mcp.security.McpOAuthRequestContext;
import com.xander.lab.integration.mcp.security.infrastructure.McpOAuthClient;
import com.xander.lab.integration.mcp.security.infrastructure.McpOAuthClientMapper;
import com.xander.lab.integration.mcp.security.infrastructure.McpOAuthGrant;
import com.xander.lab.integration.mcp.security.infrastructure.McpOAuthGrantMapper;
import com.xander.lab.integration.mcp.security.infrastructure.McpOAuthToken;
import com.xander.lab.integration.mcp.security.infrastructure.McpOAuthTokenMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpOAuthServiceTest {

    private static final String CLIENT_ID = "client-1";
    private static final String FAMILY = "family-1";
    private static final String BASE_URL = "https://dinqor.cn";
    private static final String AUTHORIZATION_REQUEST_JSON = "{\"requestId\":\"req-1\",\"clientId\":\"client-1\","
            + "\"redirectUri\":\"https://client.example/cb\",\"scopes\":[\"blog:read\",\"blog:write\"],"
            + "\"state\":\"state-1\","
            + "\"codeChallenge\":\"challenge-AAAABBBBCCCCDDDDEEEEFFFFGGGGHHHHIIIIJJJJKKKKLLLLMMMM\","
            + "\"resource\":\"https://dinqor.cn/api/mcp\",\"createdAt\":\"2026-08-14T10:00:00\"}";

    @BeforeAll
    static void registerMybatisEntities() {
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
                new com.baomidou.mybatisplus.core.MybatisConfiguration();
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.xander.lab.integration.mcp.security.infrastructure");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, McpOAuthToken.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, McpOAuthGrant.class);
    }

    @Mock McpOAuthProperties properties;
    @Mock McpOAuthClientMapper clientMapper;
    @Mock McpOAuthGrantMapper grantMapper;
    @Mock McpOAuthTokenMapper tokenMapper;
    @Mock StringRedisTemplate redisTemplate;
    @Mock AccessTokenLookupPort accessTokenLookupPort;

    McpOAuthService service;

    @BeforeEach
    void setUp() {
        service = new McpOAuthService(properties, clientMapper, grantMapper, tokenMapper,
                redisTemplate, new ObjectMapper().registerModule(new JavaTimeModule()), accessTokenLookupPort);
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getAccessTokenTtlSeconds()).thenReturn(3600);
        when(properties.getRefreshTokenTtlSeconds()).thenReturn(2592000);
        McpOAuthClient client = new McpOAuthClient();
        client.setClientId(CLIENT_ID);
        client.setStatus("ACTIVE");
        client.setRedirectUris("[\"https://client.example/cb\"]");
        when(clientMapper.selectOne(any())).thenReturn(client);
        McpOAuthGrant grant = new McpOAuthGrant();
        grant.setId(1L);
        grant.setUserId(123L);
        grant.setClientId(CLIENT_ID);
        grant.setStatus("ACTIVE");
        grant.setScopes("blog:read blog:write");
        when(grantMapper.selectById(1L)).thenReturn(grant);
    }

    private McpOAuthToken refreshToken(String status, boolean expired) {
        McpOAuthToken token = new McpOAuthToken();
        token.setId(10L);
        token.setGrantId(1L);
        token.setTokenHash("hash-of-refresh-token");
        token.setTokenType("REFRESH");
        token.setTokenFamilyId(FAMILY);
        token.setStatus(status);
        token.setExpiresAt(LocalDateTime.now().plusSeconds(expired ? -1 : 3600));
        return token;
    }

    private McpOAuthService.TokenPair rotate(String refreshToken) {
        return service.issueToken("refresh_token", CLIENT_ID, null, null, null, refreshToken);
    }

    @Test
    void refreshRotationConsumesTokenOnceAndKeepsFamily() {
        when(tokenMapper.selectOne(any())).thenReturn(refreshToken("ACTIVE", false));
        when(tokenMapper.update(isNull(), any())).thenReturn(1);

        McpOAuthService.TokenPair pair = rotate("some-refresh-token");

        assertNotNull(pair.accessToken());
        assertNotNull(pair.refreshToken());
        assertEquals("blog:read blog:write", pair.scope());
        ArgumentCaptor<McpOAuthToken> inserted = ArgumentCaptor.forClass(McpOAuthToken.class);
        verify(tokenMapper, org.mockito.Mockito.times(2)).insert(inserted.capture());
        assertEquals(List.of("ACCESS", "REFRESH"), inserted.getAllValues().stream().map(McpOAuthToken::getTokenType).sorted().toList());
        assertTrue(inserted.getAllValues().stream().allMatch(token -> FAMILY.equals(token.getTokenFamilyId())));
    }

    @Test
    void replayOfConsumedRefreshTokenRevokesWholeFamily() {
        // The losing caller reads an ACTIVE token, loses the atomic claim (update=0), then re-reads it as USED.
        when(tokenMapper.selectOne(any())).thenReturn(refreshToken("ACTIVE", false), refreshToken("USED", false));
        when(tokenMapper.update(isNull(), any())).thenReturn(0);

        McpOAuthException error = assertThrows(McpOAuthException.class, () -> rotate("stolen-refresh-token"));

        assertEquals("invalid_grant", error.error());
        assertTrue(error.getMessage().contains("already used"));
        verify(tokenMapper).update(isNull(), argWithSetValue("REVOKED"));
    }

    @Test
    void expiredRefreshTokenIsRejectedWithoutFamilyRevocation() {
        when(tokenMapper.selectOne(any())).thenReturn(refreshToken("ACTIVE", true));

        McpOAuthException error = assertThrows(McpOAuthException.class, () -> rotate("expired-refresh-token"));

        assertEquals("invalid_grant", error.error());
        verify(tokenMapper, org.mockito.Mockito.never()).update(isNull(), any());
    }

    private McpOAuthToken accessToken(String status, boolean expired) {
        McpOAuthToken token = new McpOAuthToken();
        token.setId(20L);
        token.setGrantId(1L);
        token.setTokenHash("hash-of-access-token");
        token.setTokenType("ACCESS");
        token.setStatus(status);
        token.setExpiresAt(LocalDateTime.now().plusSeconds(expired ? -1 : 3600));
        return token;
    }

    @Test
    void validAccessTokenResolvesPrincipalFromDatabase() {
        when(tokenMapper.selectOne(any())).thenReturn(accessToken("ACTIVE", false));

        McpOAuthRequestContext.Principal principal = service.authenticateBearer("Bearer some-access-token");

        assertNotNull(principal);
        assertEquals(123L, principal.userId());
        assertEquals(CLIENT_ID, principal.clientId());
        assertEquals(Set.of("blog:read", "blog:write"), principal.scopes());
    }

    @Test
    void missingOrRevokedAccessTokenReturnsNull() {
        when(tokenMapper.selectOne(any())).thenReturn(null);

        assertNull(service.authenticateBearer("Bearer unknown-access-token"));
    }

    @Test
    void expiredAccessTokenReturnsNull() {
        when(tokenMapper.selectOne(any())).thenReturn(accessToken("ACTIVE", true));

        assertNull(service.authenticateBearer("Bearer expired-access-token"));
    }

    @Test
    void deactivatedGrantReturnsNull() {
        when(tokenMapper.selectOne(any())).thenReturn(accessToken("ACTIVE", false));
        McpOAuthGrant revoked = new McpOAuthGrant();
        revoked.setId(1L);
        revoked.setClientId(CLIENT_ID);
        revoked.setStatus("REVOKED");
        when(grantMapper.selectById(1L)).thenReturn(revoked);

        assertNull(service.authenticateBearer("Bearer some-access-token"));
    }

    @Test
    void deactivatedClientReturnsNull() {
        when(tokenMapper.selectOne(any())).thenReturn(accessToken("ACTIVE", false));
        McpOAuthClient deactivated = new McpOAuthClient();
        deactivated.setClientId(CLIENT_ID);
        deactivated.setStatus("DEACTIVATED");
        when(clientMapper.selectOne(any())).thenReturn(deactivated);

        assertNull(service.authenticateBearer("Bearer some-access-token"));
    }

    @Test
    void malformedOrMissingAuthorizationReturnsNull() {
        assertNull(service.authenticateBearer(null));
        assertNull(service.authenticateBearer("Basic abc"));
        assertNull(service.authenticateBearer("Bearer   "));
    }

    private void mockWebLogin() {
        when(accessTokenLookupPort.resolveUserId(anyString())).thenReturn(123L);
    }

    private void seedAuthorizationRequest() {
        ValueOperations<String, String> ops = org.mockito.Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(AUTHORIZATION_REQUEST_JSON);
    }

    @Test
    void startAuthorizationRejectsScopeNotEnforcedByResource() {
        when(properties.getPublicBaseUrl()).thenReturn(BASE_URL);

        McpOAuthException error = assertThrows(McpOAuthException.class, () -> service.startAuthorization(
                "code", CLIENT_ID, "https://client.example/cb", "csdn:publish", "state-1",
                "challenge-AAAABBBBCCCCDDDDEEEEFFFFGGGGHHHHIIIIJJJJKKKKLLLLMMMM", "S256", BASE_URL + "/api/mcp"));

        assertEquals("invalid_scope", error.error());
    }

    @Test
    void startAuthorizationAcceptsScopeEnforcedByResource() {
        when(properties.getPublicBaseUrl()).thenReturn(BASE_URL);
        ValueOperations<String, String> ops = org.mockito.Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);

        McpOAuthService.AuthorizationStart start = service.startAuthorization(
                "code", CLIENT_ID, "https://client.example/cb", "blog:read blog:write", "state-1",
                "challenge-AAAABBBBCCCCDDDDEEEEFFFFGGGGHHHHIIIIJJJJKKKKLLLLMMMM", "S256", BASE_URL + "/api/mcp");

        assertEquals(Set.of("blog:read", "blog:write"), start.scopes());
        assertTrue(start.consentUrl().startsWith(BASE_URL + "/profile?mcpOAuthRequest="));
    }

    @Test
    void approvingPersistsOnlyUserSelectedScopes() {
        mockWebLogin();
        seedAuthorizationRequest();

        String redirect = service.approveAuthorization("req-1", "Bearer web-token", Set.of("blog:read"));

        assertTrue(redirect.startsWith("https://client.example/cb?"), "redirect was: " + redirect);
        assertTrue(redirect.contains("code="), "redirect was: " + redirect);
        assertTrue(redirect.contains("state=state-1"), "redirect was: " + redirect);
        ArgumentCaptor<McpOAuthGrant> inserted = ArgumentCaptor.forClass(McpOAuthGrant.class);
        verify(grantMapper).insert(inserted.capture());
        assertEquals("blog:read", inserted.getValue().getScopes());
    }

    @Test
    void approvingScopeOutsideClientRequestIsRejected() {
        mockWebLogin();
        seedAuthorizationRequest();

        McpOAuthException error = assertThrows(McpOAuthException.class,
                () -> service.approveAuthorization("req-1", "Bearer web-token", Set.of("csdn:publish")));

        assertEquals("invalid_request", error.error());
        verify(grantMapper, org.mockito.Mockito.never()).insert(any());
    }

    @Test
    void approvingWithoutExplicitSelectionGrantsEverythingRequested() {
        mockWebLogin();
        seedAuthorizationRequest();

        String redirect = service.approveAuthorization("req-1", "Bearer web-token", null);

        assertTrue(redirect.startsWith("https://client.example/cb?"), "redirect was: " + redirect);
        ArgumentCaptor<McpOAuthGrant> inserted = ArgumentCaptor.forClass(McpOAuthGrant.class);
        verify(grantMapper).insert(inserted.capture());
        assertEquals("blog:read blog:write", inserted.getValue().getScopes());
    }

    @Test
    void approvingWithoutAnySelectedScopeIsRejected() {
        mockWebLogin();
        seedAuthorizationRequest();

        assertThrows(McpOAuthException.class,
                () -> service.approveAuthorization("req-1", "Bearer web-token", Set.of()));
        verify(grantMapper, org.mockito.Mockito.never()).insert(any());
    }

    @Test
    void pendingAuthorizationExposesPreviouslyGrantedScopesForPreselection() {
        mockWebLogin();
        seedAuthorizationRequest();
        McpOAuthGrant prior = new McpOAuthGrant();
        prior.setId(5L);
        prior.setUserId(123L);
        prior.setClientId(CLIENT_ID);
        prior.setStatus("ACTIVE");
        prior.setScopes("blog:read");
        when(grantMapper.selectOne(any())).thenReturn(prior);

        McpOAuthService.AuthorizationRequestView view = service.pendingAuthorization("req-1", "Bearer web-token");

        assertEquals(Set.of("blog:read", "blog:write"), view.scopes());
        assertEquals(Set.of("blog:read"), view.previouslyGranted());
    }

    @Test
    void revokeClientGrantRejectsWhenThePortSaysTheTokenIsNotAnIdentity() {
        when(accessTokenLookupPort.resolveUserId("web-token")).thenReturn(null);

        McpOAuthException error = assertThrows(McpOAuthException.class,
                () -> service.revokeClientGrant(CLIENT_ID, "Bearer web-token"));

        assertEquals("login_required", error.error());
        assertEquals(401, error.status());
        verify(grantMapper, org.mockito.Mockito.never()).selectOne(any());
    }

    @Test
    void revokeClientGrantRequiresABearerPrefixedHeader() {
        assertThrows(McpOAuthException.class, () -> service.revokeClientGrant(CLIENT_ID, "web-token"));
        assertThrows(McpOAuthException.class, () -> service.revokeClientGrant(CLIENT_ID, null));
        verify(accessTokenLookupPort, org.mockito.Mockito.never()).resolveUserId(anyString());
    }

    @Test
    void listClientGrantsScopesTheQueryToTheUserIdThePortReturned() {
        when(accessTokenLookupPort.resolveUserId("web-token")).thenReturn(777L);
        when(grantMapper.selectList(any())).thenReturn(List.of());

        service.listClientGrants("Bearer web-token");

        ArgumentCaptor<LambdaQueryWrapper<McpOAuthGrant>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(grantMapper).selectList(captor.capture());
        captor.getValue().getSqlSegment();   // ⚠️ 必须先渲染一次，MyBatis-Plus 是惰性收集参数的
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(777L),
                "查询条件必须用端口返回的 userId（777），不是常量");
    }

    @Test
    void resourceScopesAreTrimmedPerMcpEndpoint() {
        assertEquals(Set.of("blog:read", "blog:write"), McpOAuthService.resourceScopes(BASE_URL + "/api/mcp"));
        assertEquals(Set.of("csdn:status", "csdn:authorize", "csdn:publish"), McpOAuthService.resourceScopes(BASE_URL + "/api/mcp/csdn"));
        assertEquals(Set.of("juejin:status", "juejin:publish", "blog:write"), McpOAuthService.resourceScopes(BASE_URL + "/api/mcp/juejin"));
        assertEquals(Set.of("publish:dual", "blog:write", "csdn:publish", "csdn:authorize"), McpOAuthService.resourceScopes(BASE_URL + "/api/mcp/dual"));
        assertNull(McpOAuthService.resourceScopes(BASE_URL + "/api/unknown"));
    }

    private Wrapper<McpOAuthToken> argWithSetValue(String expectedSetValue) {
        return org.mockito.ArgumentMatchers.argThat(wrapper ->
                wrapper instanceof LambdaUpdateWrapper<?>
                        && ((LambdaUpdateWrapper<?>) wrapper).getParamNameValuePairs().containsValue(expectedSetValue));
    }

    @Test
    void rateLimitAllowsRequestsWithinWindow() {
        when(properties.getRateLimitMaxRequests()).thenReturn(3);
        when(properties.getRateLimitWindowSeconds()).thenReturn(60);
        mockValueOperations(1L, 2L, 3L);

        service.checkRateLimit("token", "127.0.0.1");
        service.checkRateLimit("token", "127.0.0.1");
        service.checkRateLimit("token", "127.0.0.1");

        verify(redisTemplate).expire(any(), org.mockito.ArgumentMatchers.eq(60L),
                org.mockito.ArgumentMatchers.eq(java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void rateLimitRejectsRequestAboveTheWindowLimit() {
        when(properties.getRateLimitMaxRequests()).thenReturn(2);
        when(properties.getRateLimitWindowSeconds()).thenReturn(60);
        mockValueOperations(1L, 2L, 3L);

        service.checkRateLimit("token", "127.0.0.1");
        service.checkRateLimit("token", "127.0.0.1");

        McpOAuthException error = assertThrows(McpOAuthException.class, () -> service.checkRateLimit("token", "127.0.0.1"));
        assertEquals("rate_limited", error.error());
        assertEquals(429, error.status());
    }

@Test
    void rateLimitSkipsBlankDiscriminators() {
        service.checkRateLimit("register", " ");
        verify(redisTemplate, org.mockito.Mockito.never()).opsForValue();
    }

    private org.springframework.data.redis.core.ValueOperations<String, String> mockValueOperations(Long... counts) {
        org.springframework.data.redis.core.ValueOperations<String, String> ops =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        if (counts.length == 1) {
            when(ops.increment(anyString())).thenReturn(counts[0]);
        } else {
            Long[] rest = java.util.Arrays.copyOfRange(counts, 1, counts.length);
            when(ops.increment(anyString())).thenReturn(counts[0], rest);
        }
        return ops;
    }
}

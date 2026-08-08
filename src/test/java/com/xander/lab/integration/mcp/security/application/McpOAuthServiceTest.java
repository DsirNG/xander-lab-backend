package com.xander.lab.integration.mcp.security.application;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.identity.application.AuthService;
import com.xander.lab.identity.security.JwtUtil;
import com.xander.lab.integration.mcp.security.McpOAuthException;
import com.xander.lab.integration.mcp.security.McpOAuthProperties;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpOAuthServiceTest {

    private static final String CLIENT_ID = "client-1";
    private static final String FAMILY = "family-1";

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
    @Mock AuthService authService;
    @Mock JwtUtil jwtUtil;

    McpOAuthService service;

    @BeforeEach
    void setUp() {
        service = new McpOAuthService(properties, clientMapper, grantMapper, tokenMapper,
                redisTemplate, new ObjectMapper(), authService, jwtUtil);
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getAccessTokenTtlSeconds()).thenReturn(3600);
        when(properties.getRefreshTokenTtlSeconds()).thenReturn(2592000);
        McpOAuthClient client = new McpOAuthClient();
        client.setClientId(CLIENT_ID);
        client.setStatus("ACTIVE");
        when(clientMapper.selectOne(any())).thenReturn(client);
        McpOAuthGrant grant = new McpOAuthGrant();
        grant.setId(1L);
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

    private Wrapper<McpOAuthToken> argWithSetValue(String expectedSetValue) {
        return org.mockito.ArgumentMatchers.argThat(wrapper ->
                wrapper instanceof LambdaUpdateWrapper<?>
                        && ((LambdaUpdateWrapper<?>) wrapper).getParamNameValuePairs().containsValue(expectedSetValue));
    }
}

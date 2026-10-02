package com.xander.lab.integration.mcp.client.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.integration.mcp.client.McpClientProperties;
import com.xander.lab.integration.mcp.client.domain.AgentMcpServer;
import com.xander.lab.integration.mcp.client.infrastructure.AgentMcpServerMapper;
import com.xander.lab.integration.mcp.client.infrastructure.McpHeaderCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** MCP OAuth 2.1 + PKCE client flow for remote servers that advertise OAuth. */
@Service
@RequiredArgsConstructor
public class McpOAuthClientService {

    private static final String STATE_PREFIX = "mcp:client:oauth:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AgentMcpServerMapper mapper;
    private final McpClientProperties properties;
    private final McpHeaderCipher headerCipher;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public AuthorizationStart start(Long serverId, long ownerUserId) {
        AgentMcpServer server = require(serverId, ownerUserId);
        JsonNode protectedMetadata = getProtectedMetadata(server.getEndpointUrl());
        String authorizationServer = text(protectedMetadata, "authorization_servers", 0);
        if (authorizationServer == null) throw new IllegalStateException("远端 MCP 未提供 OAuth 授权服务器信息");
        JsonNode oauthMetadata = getJson(trimSlash(authorizationServer) + "/.well-known/oauth-authorization-server");
        String registrationEndpoint = text(oauthMetadata, "registration_endpoint", null);
        String authorizationEndpoint = text(oauthMetadata, "authorization_endpoint", null);
        String tokenEndpoint = text(oauthMetadata, "token_endpoint", null);
        if (registrationEndpoint == null || authorizationEndpoint == null || tokenEndpoint == null) {
            throw new IllegalStateException("远端 MCP OAuth 元数据不完整");
        }

        String redirectUri = properties.getOauthRedirectUri();
        JsonNode registration = RestClient.create().post().uri(registrationEndpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("client_name", properties.getOauthClientName(), "redirect_uris", List.of(redirectUri)))
                .retrieve().body(JsonNode.class);
        String clientId = registration == null ? null : registration.path("client_id").asText(null);
        if (clientId == null) throw new IllegalStateException("远端 MCP OAuth 客户端注册失败");

        String verifier = randomToken(32);
        String challenge = base64Url(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
        String state = randomToken(32);
        ObjectNode stored = objectMapper.createObjectNode()
                .put("serverId", serverId)
                .put("ownerUserId", ownerUserId)
                .put("clientId", clientId)
                .put("redirectUri", redirectUri)
                .put("codeVerifier", verifier)
                .put("tokenEndpoint", tokenEndpoint)
                .put("resource", server.getEndpointUrl());
        redis.opsForValue().set(STATE_PREFIX + state, stored.toString(),
                Math.max(60, properties.getOauthStateTtlSeconds()), TimeUnit.SECONDS);

        String scopes = text(protectedMetadata, "scopes_supported", -1);
        String url = authorizationEndpoint + "?response_type=code"
                + "&client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirectUri)
                + "&scope=" + enc(scopes == null ? "" : scopes)
                + "&state=" + enc(state)
                + "&code_challenge=" + enc(challenge)
                + "&code_challenge_method=S256"
                + "&resource=" + enc(server.getEndpointUrl());
        return new AuthorizationStart(serverId, url);
    }

    public Completion complete(String state, String code, long ownerUserId) {
        if (state == null || state.isBlank() || code == null || code.isBlank()) {
            throw new IllegalArgumentException("OAuth 回调缺少 state 或 code");
        }
        String raw = redis.opsForValue().getAndDelete(STATE_PREFIX + state);
        if (raw == null) throw new IllegalArgumentException("OAuth 授权状态已过期，请重新测试");
        try {
            JsonNode stored = objectMapper.readTree(raw);
            long storedOwner = stored.path("ownerUserId").asLong(-1);
            if (storedOwner != ownerUserId) throw new IllegalArgumentException("OAuth 授权状态不属于当前用户");
            Long serverId = stored.path("serverId").asLong();
            AgentMcpServer server = require(serverId, ownerUserId);
            String form = "grant_type=authorization_code"
                    + "&client_id=" + enc(stored.path("clientId").asText())
                    + "&code=" + enc(code)
                    + "&redirect_uri=" + enc(stored.path("redirectUri").asText())
                    + "&code_verifier=" + enc(stored.path("codeVerifier").asText());
            JsonNode token = RestClient.create().post().uri(stored.path("tokenEndpoint").asText())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(JsonNode.class);
            String accessToken = token == null ? null : token.path("access_token").asText(null);
            if (accessToken == null) throw new IllegalStateException("OAuth 未返回访问令牌");
            server.setHeadersEncrypted(headerCipher.encrypt(objectMapper.writeValueAsString(
                    Map.of("Authorization", "Bearer " + accessToken))));
            server.setEnabled(true);
            server.setLastStatus(null);
            server.setLastError(null);
            mapper.updateById(server);
            return new Completion(serverId);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("OAuth 令牌交换失败，请重新授权", exception);
        }
    }

    private JsonNode getProtectedMetadata(String endpoint) {
        URI uri = URI.create(endpoint);
        String metadataUrl = uri.getScheme() + "://" + uri.getAuthority()
                + "/.well-known/oauth-protected-resource?resource=" + enc(endpoint);
        return getJson(metadataUrl);
    }

    private JsonNode getJson(String url) {
        return RestClient.create().get().uri(url).retrieve().body(JsonNode.class);
    }

    private AgentMcpServer require(Long id, long ownerUserId) {
        AgentMcpServer server = mapper.selectOne(new LambdaQueryWrapper<AgentMcpServer>()
                .eq(AgentMcpServer::getId, id).eq(AgentMcpServer::getOwnerUserId, ownerUserId));
        if (server == null) throw new IllegalArgumentException("MCP 服务器不存在");
        return server;
    }

    private String text(JsonNode node, String field, Integer index) {
        JsonNode value = node == null ? null : node.get(field);
        if (index != null && index >= 0) value = value != null && value.isArray() && value.size() > index ? value.get(index) : null;
        if (index != null && index < 0 && value != null && value.isArray()) {
            return String.join(" ", java.util.stream.StreamSupport.stream(value.spliterator(), false).map(JsonNode::asText).toList());
        }
        return value == null || value.isNull() ? null : value.asText(null);
    }

    private static String trimSlash(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static byte[] sha256(byte[] value) { try { return MessageDigest.getInstance("SHA-256").digest(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static String base64Url(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private static String randomToken(int bytes) { byte[] value = new byte[bytes]; RANDOM.nextBytes(value); return base64Url(value); }

    public record AuthorizationStart(Long serverId, String authorizationUrl) { }
    public record Completion(Long serverId) { }
}

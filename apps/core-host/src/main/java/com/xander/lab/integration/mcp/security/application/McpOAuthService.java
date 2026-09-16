package com.xander.lab.integration.mcp.security.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.identity.application.AuthService;
import com.xander.lab.auth.core.JwtUtil;
import com.xander.lab.integration.mcp.security.McpOAuthException;
import com.xander.lab.integration.mcp.security.McpOAuthProperties;
import com.xander.lab.integration.mcp.security.McpOAuthRequestContext;
import com.xander.lab.integration.mcp.security.infrastructure.McpOAuthClient;
import com.xander.lab.integration.mcp.security.infrastructure.McpOAuthClientMapper;
import com.xander.lab.integration.mcp.security.infrastructure.McpOAuthGrant;
import com.xander.lab.integration.mcp.security.infrastructure.McpOAuthGrantMapper;
import com.xander.lab.integration.mcp.security.infrastructure.McpOAuthToken;
import com.xander.lab.integration.mcp.security.infrastructure.McpOAuthTokenMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Owns OAuth 2.1 + PKCE state and bearer-token lifecycle for all remote MCP resources. */
@Service
@RequiredArgsConstructor
public class McpOAuthService {
    public static final Set<String> SUPPORTED_SCOPES = Set.of(
            "blog:read", "blog:write", "csdn:status", "csdn:authorize", "csdn:publish", "publish:dual",
            "juejin:status", "juejin:publish");

    /** Scopes each MCP endpoint actually enforces, so metadata and authorize never advertise useless ones. */
    public static final Map<String, Set<String>> RESOURCE_SCOPES = Map.of(
            "/api/mcp", Set.of("blog:read", "blog:write"),
            "/api/mcp/csdn", Set.of("csdn:status", "csdn:authorize", "csdn:publish"),
            "/api/mcp/juejin", Set.of("juejin:status", "juejin:publish", "blog:write"),
            "/api/mcp/dual", Set.of("publish:dual", "blog:write", "csdn:publish", "csdn:authorize"));

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String AUTHORIZATION_REQUEST_PREFIX = "mcp:oauth:authorization-request:";
    private static final String AUTHORIZATION_CODE_PREFIX = "mcp:oauth:authorization-code:";
    private static final String RATE_LIMIT_PREFIX = "mcp:oauth:rate:";

    private final McpOAuthProperties properties;
    private final McpOAuthClientMapper clientMapper;
    private final McpOAuthGrantMapper grantMapper;
    private final McpOAuthTokenMapper tokenMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AuthService authService;
    private final JwtUtil jwtUtil;

    /** Registers an RFC 7591 public client. Client secrets are intentionally never issued. */
    public RegisteredClient registerClient(String clientName, List<String> redirectUris) {
        if (clientName == null || clientName.isBlank() || clientName.length() > 120) {
            throw invalidRequest("client_name is required and must be at most 120 characters");
        }
        if (redirectUris == null || redirectUris.isEmpty() || redirectUris.size() > 16) {
            throw invalidRequest("redirect_uris must contain between 1 and 16 values");
        }
        List<String> normalizedUris = redirectUris.stream().map(this::validateRedirectUri).distinct().toList();
        McpOAuthClient client = new McpOAuthClient();
        client.setClientId(randomToken(32));
        client.setClientName(clientName.strip());
        client.setRedirectUris(write(normalizedUris));
        client.setStatus("ACTIVE");
        clientMapper.insert(client);
        return new RegisteredClient(client.getClientId(), client.getClientName(), normalizedUris);
    }

    /** Validates an authorization request and stores it until the logged-in user gives consent. */
    public AuthorizationStart startAuthorization(String responseType, String clientId, String redirectUri,
                                                  String scope, String state, String codeChallenge,
                                                  String codeChallengeMethod, String resource) {
        requireEnabled();
        if (!"code".equals(responseType)) throw invalidRequest("response_type must be code");
        if (state == null || state.isBlank() || state.length() > 2048) throw invalidRequest("state is required");
        if (!"S256".equals(codeChallengeMethod)) throw invalidRequest("code_challenge_method must be S256");
        if (codeChallenge == null || !codeChallenge.matches("[A-Za-z0-9_-]{43,128}")) {
            throw invalidRequest("code_challenge must be a valid S256 value");
        }
        McpOAuthClient client = requireActiveClient(clientId);
        String normalizedRedirectUri = validateRedirectUri(redirectUri);
        if (!redirectUris(client).contains(normalizedRedirectUri)) throw invalidRequest("redirect_uri is not registered");
        Set<String> scopes = parseScopes(scope);
        if (resource != null && !resource.isBlank() && !isMcpResource(resource)) throw invalidRequest("resource is not an DinQorAI MCP endpoint");
        if (resource != null && !resource.isBlank()) {
            Set<String> resourceScopes = resourceScopes(resource);
            // A scope the endpoint never enforces is useless; rejecting it keeps grants honest.
            if (!resourceScopes.containsAll(scopes)) {
                throw new McpOAuthException("invalid_scope", 400, "requested scope is not available on the selected MCP endpoint");
            }
        }

        String requestId = randomToken(24);
        AuthorizationRequest request = new AuthorizationRequest(requestId, client.getClientId(), normalizedRedirectUri,
                scopes, state, codeChallenge, resource, LocalDateTime.now());
        redisTemplate.opsForValue().set(AUTHORIZATION_REQUEST_PREFIX + requestId, write(request),
                properties.getAuthorizationRequestTtlSeconds(), TimeUnit.SECONDS);
        return new AuthorizationStart(requestId, client.getClientName(), scopes,
                properties.getPublicBaseUrl() + "/profile?mcpOAuthRequest=" + url(requestId));
    }

    /** Returns the pending consent details only to an already authenticated DinQorAI user. */
    public AuthorizationRequestView pendingAuthorization(String requestId, String webAuthorization) {
        Long userId = requireWebUser(webAuthorization);
        AuthorizationRequest request = requireAuthorizationRequest(requestId);
        McpOAuthClient client = requireActiveClient(request.clientId());
        McpOAuthGrant priorGrant = activeGrant(userId, client.getClientId());
        Set<String> previouslyGranted = priorGrant == null ? Set.of() : splitScopes(priorGrant.getScopes());
        return new AuthorizationRequestView(request.requestId(), client.getClientName(), request.scopes(), request.resource(),
                previouslyGranted);
    }

    /**
     * Records consent for the scopes the user selected on the consent page and returns the exact registered
     * redirect URI carrying a single-use code. The selection may only narrow what the client requested; it
     * can never grant scopes the client did not ask for.
     */
    public String approveAuthorization(String requestId, String webAuthorization, Set<String> selectedScopes) {
        Long userId = requireWebUser(webAuthorization);
        AuthorizationRequest request = requireAuthorizationRequest(requestId);
        McpOAuthClient client = requireActiveClient(request.clientId());
        if (!redirectUris(client).contains(request.redirectUri())) throw invalidRequest("redirect_uri is not registered");
        // An absent selection (older clients or a plain approve) defaults to granting everything the client requested.
        if (selectedScopes == null) selectedScopes = request.scopes();
        if (selectedScopes.isEmpty()) throw invalidRequest("at least one scope must be selected");
        if (!request.scopes().containsAll(selectedScopes)) {
            throw invalidRequest("selected scopes exceed the scopes requested by the client");
        }

        McpOAuthGrant grant = activeGrant(userId, client.getClientId());
        LocalDateTime now = LocalDateTime.now();
        if (grant == null) {
            grant = new McpOAuthGrant();
            grant.setUserId(userId);
            grant.setClientId(client.getClientId());
        }
        grant.setScopes(joinScopes(selectedScopes));
        grant.setStatus("ACTIVE");
        grant.setGrantedAt(now);
        grant.setRevokedAt(null);
        if (grant.getId() == null) grantMapper.insert(grant); else grantMapper.updateById(grant);

        String code = randomToken(32);
        AuthorizationCode authorizationCode = new AuthorizationCode(grant.getId(), client.getClientId(), request.redirectUri(),
                request.codeChallenge(), request.scopes());
        redisTemplate.opsForValue().set(AUTHORIZATION_CODE_PREFIX + sha256(code), write(authorizationCode),
                properties.getAuthorizationCodeTtlSeconds(), TimeUnit.SECONDS);
        redisTemplate.delete(AUTHORIZATION_REQUEST_PREFIX + requestId);
        return appendQuery(request.redirectUri(), Map.of("code", code, "state", request.state()));
    }

    /** Declining an authorization consumes the browser transaction and returns the OAuth callback URL. */
    public String denyAuthorization(String requestId, String webAuthorization) {
        requireWebUser(webAuthorization);
        AuthorizationRequest request = requireAuthorizationRequest(requestId);
        redisTemplate.delete(AUTHORIZATION_REQUEST_PREFIX + requestId);
        return appendQuery(request.redirectUri(), Map.of("error", "access_denied", "state", request.state()));
    }

    /** Exchanges an authorization code or rotating refresh token for an opaque bearer-token pair. */
    public TokenPair issueToken(String grantType, String clientId, String code, String redirectUri,
                                String codeVerifier, String refreshToken) {
        requireEnabled();
        requireActiveClient(clientId);
        if ("authorization_code".equals(grantType)) {
            if (code == null || redirectUri == null || codeVerifier == null) throw invalidRequest("code, redirect_uri and code_verifier are required");
            String value = redisTemplate.opsForValue().getAndDelete(AUTHORIZATION_CODE_PREFIX + sha256(code));
            if (value == null) throw new McpOAuthException("invalid_grant", 400, "authorization code is invalid or expired");
            AuthorizationCode authorizationCode = read(value, AuthorizationCode.class);
            if (!authorizationCode.clientId().equals(clientId) || !authorizationCode.redirectUri().equals(validateRedirectUri(redirectUri))) {
                throw new McpOAuthException("invalid_grant", 400, "authorization code does not belong to this client or redirect URI");
            }
            if (!sha256Base64Url(codeVerifier).equals(authorizationCode.codeChallenge())) {
                throw new McpOAuthException("invalid_grant", 400, "PKCE verification failed");
            }
            McpOAuthGrant grant = requireActiveGrant(authorizationCode.grantId(), clientId);
            return createTokens(grant);
        }
        if ("refresh_token".equals(grantType)) {
            if (refreshToken == null) throw invalidRequest("refresh_token is required");
            String refreshHash = sha256(refreshToken);
            McpOAuthToken token = tokenMapper.selectOne(new LambdaQueryWrapper<McpOAuthToken>()
                    .eq(McpOAuthToken::getTokenHash, refreshHash).eq(McpOAuthToken::getTokenType, "REFRESH"));
            if (token == null || token.getExpiresAt().isBefore(LocalDateTime.now())) {
                throw new McpOAuthException("invalid_grant", 400, "refresh token is invalid or expired");
            }
            LocalDateTime now = LocalDateTime.now();
            McpOAuthGrant grant = requireActiveGrant(token.getGrantId(), clientId);
            // Atomically consume the refresh token exactly once; a concurrent replay loses this race.
            int claimed = tokenMapper.update(null, new LambdaUpdateWrapper<McpOAuthToken>()
                    .eq(McpOAuthToken::getTokenHash, refreshHash)
                    .eq(McpOAuthToken::getTokenType, "REFRESH")
                    .eq(McpOAuthToken::getStatus, "ACTIVE")
                    .ge(McpOAuthToken::getExpiresAt, now)
                    .set(McpOAuthToken::getStatus, "USED")
                    .set(McpOAuthToken::getUsedAt, now));
            if (claimed == 0) {
                // A token that was already USED is evidence of replay, so revoke its complete token family.
                McpOAuthToken rechecked = tokenMapper.selectOne(new LambdaQueryWrapper<McpOAuthToken>()
                        .eq(McpOAuthToken::getTokenHash, refreshHash).eq(McpOAuthToken::getTokenType, "REFRESH"));
                if (rechecked != null && "USED".equals(rechecked.getStatus())) {
                    revokeTokenFamily(rechecked.getTokenFamilyId());
                    throw new McpOAuthException("invalid_grant", 400, "refresh token was already used");
                }
                throw new McpOAuthException("invalid_grant", 400, "refresh token is invalid or expired");
            }
            return createTokens(grant, token.getTokenFamilyId());
        }
        throw invalidRequest("grant_type must be authorization_code or refresh_token");
    }

    /** Resolves a bearer token for the shared MCP transport filter. */
    public McpOAuthRequestContext.Principal authenticateBearer(String authorization) {
        requireEnabled();
        if (authorization == null || !authorization.startsWith("Bearer ")) return null;
        String rawToken = authorization.substring(7).strip();
        if (rawToken.isBlank()) return null;
        McpOAuthToken token = tokenMapper.selectOne(new LambdaQueryWrapper<McpOAuthToken>()
                .eq(McpOAuthToken::getTokenHash, sha256(rawToken)).eq(McpOAuthToken::getTokenType, "ACCESS")
                .eq(McpOAuthToken::getStatus, "ACTIVE"));
        if (token == null || token.getExpiresAt().isBefore(LocalDateTime.now())) return null;
        McpOAuthGrant grant = grantMapper.selectById(token.getGrantId());
        if (grant == null || !"ACTIVE".equals(grant.getStatus())) return null;
        // A deactivated client must not keep authenticating through its already-issued tokens.
        McpOAuthClient client = clientMapper.selectOne(new LambdaQueryWrapper<McpOAuthClient>()
                .eq(McpOAuthClient::getClientId, grant.getClientId()));
        if (client == null || !"ACTIVE".equals(client.getStatus())) return null;
        return new McpOAuthRequestContext.Principal(grant.getUserId(), grant.getClientId(), splitScopes(grant.getScopes()));
    }

    /** Revokes the supplied access/refresh token. A user may also revoke all grants for one client. */
    public void revokeToken(String tokenValue, String clientId) {
        if (tokenValue == null || tokenValue.isBlank()) return;
        McpOAuthToken token = tokenMapper.selectOne(new LambdaQueryWrapper<McpOAuthToken>().eq(McpOAuthToken::getTokenHash, sha256(tokenValue)));
        if (token == null) return;
        McpOAuthGrant grant = grantMapper.selectById(token.getGrantId());
        if (grant == null || !grant.getClientId().equals(clientId)) return;
        token.setStatus("REVOKED");
        token.setRevokedAt(LocalDateTime.now());
        tokenMapper.updateById(token);
    }

    public List<ClientGrantView> listClientGrants(String webAuthorization) {
        Long userId = requireWebUser(webAuthorization);
        return grantMapper.selectList(new LambdaQueryWrapper<McpOAuthGrant>()
                        .eq(McpOAuthGrant::getUserId, userId).eq(McpOAuthGrant::getStatus, "ACTIVE")
                        .orderByDesc(McpOAuthGrant::getGrantedAt)).stream()
                .map(grant -> {
                    McpOAuthClient client = clientMapper.selectOne(new LambdaQueryWrapper<McpOAuthClient>().eq(McpOAuthClient::getClientId, grant.getClientId()));
                    return new ClientGrantView(grant.getClientId(), client == null ? grant.getClientId() : client.getClientName(),
                            splitScopes(grant.getScopes()), grant.getGrantedAt());
                }).toList();
    }

    public void revokeClientGrant(String clientId, String webAuthorization) {
        Long userId = requireWebUser(webAuthorization);
        McpOAuthGrant grant = activeGrant(userId, clientId);
        if (grant == null) return;
        grant.setStatus("REVOKED");
        grant.setRevokedAt(LocalDateTime.now());
        grantMapper.updateById(grant);
    }

    private TokenPair createTokens(McpOAuthGrant grant) { return createTokens(grant, UUID.randomUUID().toString()); }

    private TokenPair createTokens(McpOAuthGrant grant, String tokenFamilyId) {
        LocalDateTime now = LocalDateTime.now();
        String accessToken = randomToken(48);
        String refreshToken = randomToken(48);
        persistToken(grant.getId(), accessToken, "ACCESS", tokenFamilyId, now.plusSeconds(properties.getAccessTokenTtlSeconds()));
        persistToken(grant.getId(), refreshToken, "REFRESH", tokenFamilyId, now.plusSeconds(properties.getRefreshTokenTtlSeconds()));
        return new TokenPair(accessToken, refreshToken, properties.getAccessTokenTtlSeconds(), joinScopes(splitScopes(grant.getScopes())));
    }

    private void persistToken(Long grantId, String rawToken, String type, String familyId, LocalDateTime expiresAt) {
        McpOAuthToken token = new McpOAuthToken();
        token.setGrantId(grantId);
        token.setTokenHash(sha256(rawToken));
        token.setTokenType(type);
        token.setTokenFamilyId(familyId);
        token.setStatus("ACTIVE");
        token.setExpiresAt(expiresAt);
        tokenMapper.insert(token);
    }

    private void revokeTokenFamily(String familyId) {
        tokenMapper.update(null, new LambdaUpdateWrapper<McpOAuthToken>()
                .eq(McpOAuthToken::getTokenFamilyId, familyId)
                .eq(McpOAuthToken::getStatus, "ACTIVE")
                .set(McpOAuthToken::getStatus, "REVOKED")
                .set(McpOAuthToken::getRevokedAt, LocalDateTime.now()));
    }

    private Long requireWebUser(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) throw new McpOAuthException("login_required", 401, "DinQorAI login is required");
        String token = authorization.substring(7);
        if (!authService.validateAccessToken(token)) throw new McpOAuthException("login_required", 401, "DinQorAI login is required");
        return Long.parseLong(jwtUtil.getSubject(token));
    }

    private McpOAuthClient requireActiveClient(String clientId) {
        if (clientId == null || clientId.isBlank()) throw invalidRequest("client_id is required");
        McpOAuthClient client = clientMapper.selectOne(new LambdaQueryWrapper<McpOAuthClient>().eq(McpOAuthClient::getClientId, clientId));
        if (client == null || !"ACTIVE".equals(client.getStatus())) throw new McpOAuthException("invalid_client", 401, "OAuth client is not registered or active");
        return client;
    }

    private McpOAuthGrant activeGrant(Long userId, String clientId) {
        return grantMapper.selectOne(new LambdaQueryWrapper<McpOAuthGrant>().eq(McpOAuthGrant::getUserId, userId)
                .eq(McpOAuthGrant::getClientId, clientId).eq(McpOAuthGrant::getStatus, "ACTIVE"));
    }

    private McpOAuthGrant requireActiveGrant(Long grantId, String clientId) {
        McpOAuthGrant grant = grantMapper.selectById(grantId);
        if (grant == null || !"ACTIVE".equals(grant.getStatus()) || !grant.getClientId().equals(clientId)) {
            throw new McpOAuthException("invalid_grant", 400, "authorization grant is inactive");
        }
        return grant;
    }

    private AuthorizationRequest requireAuthorizationRequest(String requestId) {
        if (requestId == null || requestId.isBlank()) throw invalidRequest("authorization request is required");
        String value = redisTemplate.opsForValue().get(AUTHORIZATION_REQUEST_PREFIX + requestId);
        if (value == null) throw new McpOAuthException("invalid_request", 400, "authorization request is invalid or expired");
        return read(value, AuthorizationRequest.class);
    }

    private Set<String> parseScopes(String scope) {
        if (scope == null || scope.isBlank()) throw invalidRequest("scope is required");
        Set<String> scopes = splitScopes(scope);
        if (scopes.isEmpty() || !SUPPORTED_SCOPES.containsAll(scopes)) throw new McpOAuthException("invalid_scope", 400, "requested scope is unsupported");
        return scopes;
    }

    private Set<String> splitScopes(String scope) {
        if (scope == null || scope.isBlank()) return Set.of();
        Set<String> values = new LinkedHashSet<>();
        for (String value : scope.strip().split("\\s+")) if (!value.isBlank()) values.add(value);
        return Set.copyOf(values);
    }

    private String joinScopes(Set<String> scopes) { return scopes.stream().sorted().collect(Collectors.joining(" ")); }

    private List<String> redirectUris(McpOAuthClient client) { return read(client.getRedirectUris(), new TypeReference<>() { }); }

    private String validateRedirectUri(String value) {
        try {
            URI uri = URI.create(value);
            boolean https = "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
            boolean loopback = "http".equalsIgnoreCase(uri.getScheme()) && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()));
            if ((!https && !loopback) || uri.getFragment() != null || uri.getUserInfo() != null) throw invalidRequest("redirect_uri must be HTTPS or a loopback URI");
            return uri.normalize().toString();
        } catch (IllegalArgumentException exception) {
            throw invalidRequest("redirect_uri is invalid");
        }
    }

    private boolean isMcpResource(String resource) {
        String base = properties.getPublicBaseUrl();
        return resource.equals(base + "/api/mcp") || resource.equals(base + "/api/mcp/csdn")
                || resource.equals(base + "/api/mcp/dual") || resource.equals(base + "/api/mcp/juejin");
    }

    /** Scopes the given full resource URL is allowed to request; null when the resource is not a public MCP endpoint. */
    public static Set<String> resourceScopes(String resource) {
        if (resource == null) return null;
        try {
            return RESOURCE_SCOPES.get(URI.create(resource).getPath());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) throw new McpOAuthException("temporarily_unavailable", 503, "MCP OAuth is disabled");
    }

    /**
     * Fixed-window Redis rate limiter keyed by a per-caller discriminator (IP or IP+client). The counter is
     * atomically incremented and expires with the window, then requests beyond the limit are rejected with 429.
     */
    public void checkRateLimit(String bucket, String discriminator) {
        if (bucket == null || bucket.isBlank() || discriminator == null || discriminator.isBlank()) return;
        String key = RATE_LIMIT_PREFIX + bucket + ":" + discriminator;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, properties.getRateLimitWindowSeconds(), TimeUnit.SECONDS);
        }
        if (count != null && count > properties.getRateLimitMaxRequests()) {
            throw new McpOAuthException("rate_limited", 429, "too many requests");
        }
    }

    private McpOAuthException invalidRequest(String message) { return new McpOAuthException("invalid_request", 400, message); }
    private String randomToken(int bytes) { byte[] value = new byte[bytes]; RANDOM.nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private String sha256(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(digest(value.getBytes(StandardCharsets.UTF_8))); }
    private String sha256Base64Url(String value) { return sha256(value); }
    private byte[] digest(byte[] value) { try { return MessageDigest.getInstance("SHA-256").digest(value); } catch (Exception exception) { throw new IllegalStateException("SHA-256 unavailable", exception); } }
    private String url(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private String appendQuery(String uri, Map<String, String> values) { return uri + (uri.contains("?") ? "&" : "?") + values.entrySet().stream().map(entry -> url(entry.getKey()) + "=" + url(entry.getValue())).collect(Collectors.joining("&")); }
    private String write(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException("Unable to serialize OAuth state", exception); } }
    private <T> T read(String value, Class<T> type) { try { return objectMapper.readValue(value, type); } catch (Exception exception) { throw new McpOAuthException("invalid_request", 400, "OAuth state is invalid"); } }
    private <T> T read(String value, TypeReference<T> type) { try { return objectMapper.readValue(value, type); } catch (Exception exception) { throw new IllegalStateException("OAuth client data is invalid", exception); } }

    public record RegisteredClient(String clientId, String clientName, List<String> redirectUris) { }
    public record AuthorizationStart(String requestId, String clientName, Set<String> scopes, String consentUrl) { }
    public record AuthorizationRequestView(String requestId, String clientName, Set<String> scopes, String resource,
                                           Set<String> previouslyGranted) { }
    public record TokenPair(String accessToken, String refreshToken, int expiresIn, String scope) { }
    public record ClientGrantView(String clientId, String clientName, Set<String> scopes, LocalDateTime grantedAt) { }
    private record AuthorizationRequest(String requestId, String clientId, String redirectUri, Set<String> scopes,
                                        String state, String codeChallenge, String resource, LocalDateTime createdAt) { }
    private record AuthorizationCode(Long grantId, String clientId, String redirectUri, String codeChallenge, Set<String> scopes) { }
}

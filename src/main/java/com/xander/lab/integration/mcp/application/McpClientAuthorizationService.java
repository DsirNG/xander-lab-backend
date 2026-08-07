package com.xander.lab.integration.mcp.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.identity.api.TokenResponse;
import com.xander.lab.identity.application.AuthService;
import com.xander.lab.identity.security.JwtUtil;
import com.xander.lab.integration.mcp.McpAuthorizationProperties;
import com.xander.lab.integration.mcp.infrastructure.McpClientAuthorization;
import com.xander.lab.integration.mcp.infrastructure.McpClientAuthorizationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Owns browser authorization handoffs and per-client encrypted refresh credentials.
 * A client installation can act only as the user who completed its one-time handoff.
 */
@Service
@RequiredArgsConstructor
public class McpClientAuthorizationService {
    private static final String REQUEST_PREFIX = "mcp:authorization:request:";
    private final McpClientAuthorizationMapper mapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AuthService authService;
    private final JwtUtil jwtUtil;
    private final McpAuthorizationProperties properties;
    private SecretKeySpec key;

    private SecretKeySpec encryptionKey() {
        if (key != null) return key;
        try {
            byte[] bytes = Base64.getDecoder().decode(properties.getCredentialEncryptionKey());
            if (bytes.length != 32) throw new IllegalArgumentException();
            key = new SecretKeySpec(bytes, "AES");
            return key;
        } catch (Exception exception) {
            throw new IllegalStateException("MCP_CREDENTIAL_ENCRYPTION_KEY must be a Base64-encoded 32-byte key");
        }
    }

    /** Creates a one-time browser handoff code for an authenticated MCP client. */
    public String createBrowserAuthorizationRequest(String clientId, String clientSecret) {
        validateClientId(clientId);
        String secretHash = hashClientSecret(clientSecret);
        McpClientAuthorization existing = findAny(clientId);
        if (existing != null && !MessageDigest.isEqual(existing.getClientSecretHash().getBytes(StandardCharsets.US_ASCII), secretHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("MCP client secret is invalid");
        }
        String code = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(REQUEST_PREFIX + code, clientId + "|" + secretHash, properties.getRequestTtlSeconds(), TimeUnit.SECONDS);
        return code;
    }

    /** Completes a handoff after the user authenticated in the browser. */
    public void authorize(String clientId, String code, TokenResponse tokens) {
        validateClientId(clientId);
        String expected = redisTemplate.opsForValue().get(REQUEST_PREFIX + code);
        if (expected == null || !expected.startsWith(clientId + "|")) throw new IllegalArgumentException("MCP authorization request is invalid or expired");
        redisTemplate.delete(REQUEST_PREFIX + code);
        Long userId = userId(tokens);
        McpClientAuthorization authorization = findAny(clientId);
        LocalDateTime now = LocalDateTime.now();
        if (authorization == null) {
            authorization = new McpClientAuthorization();
            authorization.setClientId(clientId);
            authorization.setCreatedAt(now);
        }
        authorization.setUserId(userId);
        authorization.setClientSecretHash(expected.substring(clientId.length() + 1));
        authorization.setEncryptedTokens(encrypt(write(tokens)));
        authorization.setStatus("ACTIVE");
        authorization.setAuthorizedAt(now);
        authorization.setUpdatedAt(now);
        if (authorization.getId() == null) mapper.insert(authorization); else mapper.updateById(authorization);
    }

    /** Resolves the authorized user for a client, refreshing the saved token when needed. */
    public Long currentUserId(String clientId, String clientSecret) {
        McpClientAuthorization authorization = findActive(clientId);
        if (authorization == null || !MessageDigest.isEqual(authorization.getClientSecretHash().getBytes(StandardCharsets.US_ASCII), hashClientSecret(clientSecret).getBytes(StandardCharsets.US_ASCII))) return null;
        TokenResponse tokens = read(authorization.getEncryptedTokens());
        if (tokens == null) return null;
        try {
            if (authService.validateAccessToken(tokens.getAccessToken())) return userId(tokens);
            TokenResponse refreshed = authService.refresh(tokens.getRefreshToken());
            authorization.setEncryptedTokens(encrypt(write(refreshed)));
            authorization.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(authorization);
            return userId(refreshed);
        } catch (Exception exception) {
            authorization.setStatus("REVOKED");
            authorization.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(authorization);
            return null;
        }
    }

    public boolean isAuthorized(String clientId, String clientSecret) { return currentUserId(clientId, clientSecret) != null; }

    private McpClientAuthorization findActive(String clientId) {
        return mapper.selectOne(new LambdaQueryWrapper<McpClientAuthorization>()
                .eq(McpClientAuthorization::getClientId, clientId)
                .eq(McpClientAuthorization::getStatus, "ACTIVE"));
    }

    private McpClientAuthorization findAny(String clientId) {
        return mapper.selectOne(new LambdaQueryWrapper<McpClientAuthorization>()
                .eq(McpClientAuthorization::getClientId, clientId));
    }

    private Long userId(TokenResponse tokens) {
        return Long.parseLong(jwtUtil.getSubject(tokens.getAccessToken()));
    }

    private String write(TokenResponse tokens) {
        try { return objectMapper.writeValueAsString(tokens); }
        catch (Exception exception) { throw new IllegalStateException("Unable to serialize MCP authorization", exception); }
    }

    private TokenResponse read(String encrypted) {
        try { return objectMapper.readValue(decrypt(encrypted), TokenResponse.class); }
        catch (Exception exception) { return null; }
    }

    private String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, iv));
            return Base64.getEncoder().encodeToString(iv) + "."
                    + Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException("Unable to encrypt MCP authorization", exception); }
    }

    private String decrypt(String value) throws Exception {
        String[] parts = value.split("\\.", 2);
        if (parts.length != 2) throw new IllegalArgumentException("Invalid MCP authorization");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
        return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
    }

    private void validateClientId(String clientId) {
        if (clientId == null || !clientId.matches("[A-Za-z0-9._-]{16,128}")) {
            throw new IllegalArgumentException("X-MCP-Client-Id must contain 16-128 letters, numbers, dots, underscores, or hyphens");
        }
    }

    private String hashClientSecret(String clientSecret) {
        if (clientSecret == null || !clientSecret.matches("[A-Za-z0-9._~-]{32,256}")) {
            throw new IllegalArgumentException("X-MCP-Client-Secret must contain 32-256 URL-safe characters");
        }
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(clientSecret.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash MCP client secret", exception);
        }
    }
}

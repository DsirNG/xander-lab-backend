package com.xander.lab.identity.application;

import com.xander.lab.identity.api.TokenResponse;
import com.xander.lab.identity.infrastructure.wechat.WechatMiniAppClient;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/** Coordinates the short-lived, user-confirmed PC QR login handoff. */
@Service
@RequiredArgsConstructor
public class QrLoginService {
    private static final String PREFIX = "login:qr:";
    private static final long TTL_SECONDS = 120;
    private final SecureRandom random = new SecureRandom();
    private final StringRedisTemplate redis;
    private final WechatMiniAppClient wechat;
    private final AuthService authService;

    /** Creates a two-minute QR ticket and its WeChat Mini Program image. */
    public CreateResult create() {
        String ticket = randomToken(16);
        redis.opsForHash().put(PREFIX + ticket, "status", "WAITING");
        redis.expire(PREFIX + ticket, TTL_SECONDS, TimeUnit.SECONDS);
        return new CreateResult(ticket, wechat.generateLoginCode(ticket), TTL_SECONDS);
    }

    /** Reads the current public ticket state without exposing user identity. */
    public StatusResult status(String ticket) {
        validateTicket(ticket);
        String key = PREFIX + ticket;
        Object status = redis.opsForHash().get(key, "status");
        if (status == null) return new StatusResult("EXPIRED", null);
        String value = String.valueOf(status);
        Object code = "CONFIRMED".equals(value) ? redis.opsForHash().get(key, "exchangeCode") : null;
        return new StatusResult(value, code == null ? null : String.valueOf(code));
    }

    /** Confirms a ticket using the authenticated mini program session. */
    public void confirm(String ticket, String bearerToken) {
        validateTicket(ticket);
        String token = bearerToken != null && bearerToken.startsWith("Bearer ") ? bearerToken.substring(7) : "";
        Long userId = authService.resolveUserId(token);
        if (userId == null) throw new IllegalArgumentException("Please sign in to the mini program first");
        String key = PREFIX + ticket;
        Object current = redis.opsForHash().get(key, "status");
        if (!("WAITING".equals(current) || "SCANNED".equals(current))) throw new IllegalArgumentException("QR code is expired or already confirmed");
        if (!redis.opsForHash().putIfAbsent(key, "exchangeCode", randomToken(24))) throw new IllegalArgumentException("QR code is expired or already confirmed");
        redis.opsForHash().put(key, "userId", String.valueOf(userId));
        redis.opsForHash().put(key, "status", "CONFIRMED");
    }

    /** Exchanges a confirmed one-time code for a fresh browser token pair. */
    public TokenResponse exchange(String ticket, String exchangeCode) {
        validateTicket(ticket);
        String key = PREFIX + ticket;
        Object stored = redis.opsForHash().get(key, "exchangeCode");
        Object userId = redis.opsForHash().get(key, "userId");
        if (stored == null || userId == null || !stored.equals(exchangeCode)) throw new IllegalArgumentException("Login credential is invalid or already used");
        if (!redis.opsForHash().putIfAbsent(key, "consumed", "1")) throw new IllegalArgumentException("Login credential is invalid or already used");
        redis.delete(key);
        return authService.issueTokenForUser(Long.valueOf(String.valueOf(userId)));
    }

    private void validateTicket(String ticket) {
        if (ticket == null || !ticket.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("QR ticket is invalid");
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    public record CreateResult(String ticket, String qrCode, long expiresIn) {}
    public record StatusResult(String status, String exchangeCode) {}
}

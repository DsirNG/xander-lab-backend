package com.xander.lab.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * JWT 工具类
 * 对应前端 axios 封装中的 Token 自动携带 & 无感刷新机制
 *
 * <pre>
 * Access Token  有效期：2 小时（前端自动刷新）
 * Refresh Token 有效期：7 天
 * </pre>
 */
@Component
public class JwtUtil {

    /** 签名密钥（至少 32 字符），从配置文件读取 */
    @Value("${jwt.secret:xander-lab-secret-key-must-be-at-least-32-chars}")
    private String secret;

    /** Access Token 有效期（毫秒），默认 2 小时 */
    @Value("${jwt.access-token-expire:7200000}")
    private long accessTokenExpire;

    /** Refresh Token 有效期（毫秒），默认 7 天 */
    @Value("${jwt.refresh-token-expire:604800000}")
    private long refreshTokenExpire;

    private SecretKey getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 生成 Access Token
     *
     * @param subject  主题（通常为用户ID或用户名）
     * @param claims   附加声明
     * @return JWT 字符串
     */
    public String generateAccessToken(String subject, Map<String, Object> claims) {
        return buildToken(subject, claims, accessTokenExpire);
    }

    /**
     * 生成 Refresh Token
     *
     * @param subject 主题（通常为用户ID或用户名）
     * @return JWT 字符串
     */
    public String generateRefreshToken(String subject) {
        return buildToken(subject, Map.of("type", "refresh"), refreshTokenExpire);
    }

    private String buildToken(String subject, Map<String, Object> claims, long expireMs) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expireMs);
        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 解析 Token，返回 Claims
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 获取 Token 中的 subject（用户名/ID）
     */
    public String getSubject(String token) {
        return parseToken(token).getSubject();
    }

    /**
     * 验证 Token 是否有效（未过期、签名正确）
     */
    public boolean isValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 判断是否为 Refresh Token
     */
    public boolean isRefreshToken(String token) {
        try {
            Claims claims = parseToken(token);
            return "refresh".equals(claims.get("type", String.class));
        } catch (Exception e) {
            return false;
        }
    }

    public long getAccessTokenExpire() {
        return accessTokenExpire;
    }

    public long getRefreshTokenExpire() {
        return refreshTokenExpire;
    }
}

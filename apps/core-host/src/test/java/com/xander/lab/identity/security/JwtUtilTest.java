package com.xander.lab.identity.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilTest {
    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", "test-secret-key-that-is-at-least-32-bytes-long");
        ReflectionTestUtils.setField(jwtUtil, "accessTokenExpire", 60_000L);
        ReflectionTestUtils.setField(jwtUtil, "refreshTokenExpire", 120_000L);
    }

    @Test
    void accessTokenCarriesSubjectAndClaims() {
        String token = jwtUtil.generateAccessToken("42", Map.of("role", "USER", "type", "access"));

        Claims claims = jwtUtil.parseToken(token);
        assertThat(jwtUtil.isValid(token)).isTrue();
        assertThat(jwtUtil.isRefreshToken(token)).isFalse();
        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("role", String.class)).isEqualTo("USER");
    }

    @Test
    void refreshTokenIsRecognized() {
        String token = jwtUtil.generateRefreshToken("7");

        assertThat(jwtUtil.isValid(token)).isTrue();
        assertThat(jwtUtil.isRefreshToken(token)).isTrue();
        assertThat(jwtUtil.getSubject(token)).isEqualTo("7");
    }

    @Test
    void tamperedAndExpiredTokensAreRejected() {
        String token = jwtUtil.generateAccessToken("42", Map.of("type", "access"));
        assertThat(jwtUtil.isValid(token + "x")).isFalse();

        ReflectionTestUtils.setField(jwtUtil, "accessTokenExpire", -1L);
        String expired = jwtUtil.generateAccessToken("42", Map.of("type", "access"));
        assertThat(jwtUtil.isValid(expired)).isFalse();
    }
}

package com.xander.lab.identity.application;

import com.xander.lab.common.Constants;
import com.xander.lab.identity.api.LoginRequest;
import com.xander.lab.identity.api.TokenResponse;
import com.xander.lab.identity.domain.User;
import com.xander.lab.identity.infrastructure.UserMapper;
import com.xander.lab.identity.security.JwtUtil;
import com.xander.lab.integration.mail.MailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock private JwtUtil jwtUtil;
    @Mock private MailService mailService;
    @Mock private UserMapper userMapper;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private SetOperations<String, String> setOperations;

    private AuthService service;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        service = new AuthService(jwtUtil, mailService, userMapper, redisTemplate, passwordEncoder);
        // 速率限制默认放行（increment 返回 1），只被涉及限流的用例使用
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.increment(anyString())).thenReturn(1L);
    }

    @Test
    void passwordLoginIssuesAndPersistsTokenPair() {
        User user = user(42L, "alice", "secret", 1);
        LoginRequest request = new LoginRequest();
        request.setType("password");
        request.setAccount("alice");
        request.setPassword("secret");
        when(userMapper.selectOne(any())).thenReturn(user);
        stubTokenIssuance();

        TokenResponse response = service.login(request, "127.0.0.1");

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getRefreshToken()).isEqualTo("refresh-token");
        assertThat(response.getUserInfo().getUsername()).isEqualTo("alice");
        verify(valueOperations).set(Constants.REDIS_TOKEN_PREFIX + "access-token", "42", 60_000L, TimeUnit.MILLISECONDS);
        verify(setOperations).add(Constants.REDIS_USER_TOKENS_PREFIX + "42", "access-token");
    }

    @Test
    void passwordLoginRejectsUnknownOrIncorrectCredentials() {
        LoginRequest request = new LoginRequest();
        request.setType("password");
        request.setAccount("missing");
        request.setPassword("wrong");
        when(userMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.login(request, "127.0.0.1")).isInstanceOf(IllegalArgumentException.class);
        verify(jwtUtil, never()).generateAccessToken(anyString(), any(Map.class));
    }

    @Test
    void refreshRejectsNonRefreshTokenBeforeDatabaseLookup() {
        when(jwtUtil.isValid("bad-token")).thenReturn(false);

        assertThatThrownBy(() -> service.refresh("bad-token")).isInstanceOf(IllegalArgumentException.class);
        verify(userMapper, never()).selectById(any());
    }

    @Test
    void refreshIssuesTokensForActiveUser() {
        User user = user(7L, "bob", "secret", 1);
        when(jwtUtil.isValid("refresh-input")).thenReturn(true);
        when(jwtUtil.isRefreshToken("refresh-input")).thenReturn(true);
        when(jwtUtil.getSubject("refresh-input")).thenReturn("7");
        when(userMapper.selectById("7")).thenReturn(user);
        stubTokenIssuance();

        TokenResponse response = service.refresh("refresh-input");

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getUserInfo().getUsername()).isEqualTo("bob");
    }

    @Test
    void accessTokenRequiresJwtAndRedisPresence() {
        when(jwtUtil.isValid("access")).thenReturn(true);
        when(jwtUtil.isRefreshToken("access")).thenReturn(false);
        when(redisTemplate.hasKey(Constants.REDIS_TOKEN_PREFIX + "access")).thenReturn(true);

        assertThat(service.validateAccessToken("access")).isTrue();
        when(jwtUtil.isRefreshToken("access")).thenReturn(true);
        assertThat(service.validateAccessToken("access")).isFalse();
    }

    @Test
    void logoutRevokesDeviceTokenAndUserTokenMembership() {
        when(jwtUtil.isValid("access")).thenReturn(true);
        when(jwtUtil.getSubject("access")).thenReturn("42");
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        service.logout("access");

        verify(setOperations).remove(Constants.REDIS_USER_TOKENS_PREFIX + "42", "access");
        verify(redisTemplate).delete(Constants.REDIS_TOKEN_PREFIX + "access");
    }

    @Test
    void verificationCodeIsStoredAndSent() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        service.sendCode("alice@example.com", "127.0.0.1");

        verify(valueOperations).set(eq(Constants.REDIS_CODE_PREFIX + "alice@example.com"), anyString(),
                eq(Constants.CODE_EXPIRE_MINUTES), eq(TimeUnit.MINUTES));
        verify(mailService).sendVerificationCode(eq("alice@example.com"), anyString());
    }

    @Test
    void sendCodeAndLoginAreRateLimitedPerAccountAndIp() {
        // 验证码：同一邮箱限 1 次/分钟，超过后第二次触发 429
        when(valueOperations.increment(anyString())).thenReturn(2L);
        assertThatThrownBy(() -> service.sendCode("limited@example.com", "10.0.0.1"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(429);

        // 密码登录：同一账号尝试次数超过 5 次/分钟触发 429
        LoginRequest request = new LoginRequest();
        request.setType("password");
        request.setAccount("bob");
        request.setPassword("x");
        when(valueOperations.increment(anyString())).thenReturn(6L);
        assertThatThrownBy(() -> service.login(request, "10.0.0.1"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(429);
    }

    private void stubTokenIssuance() {
        when(jwtUtil.generateAccessToken(anyString(), any(Map.class))).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken(anyString())).thenReturn("refresh-token");
        when(jwtUtil.getAccessTokenExpire()).thenReturn(60_000L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
    }

    private User user(Long id, String username, String password, int status) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(username);
        user.setAvatar("avatar");
        user.setRole("USER");
        user.setStatus(status);
        return user;
    }

    @Test
    void legacyPlaintextPasswordStillLogsInAndUpgradesToBcrypt() {
        User user = new User();
        user.setId(9L);
        user.setUsername("legacy");
        user.setPassword("plain-secret");
        user.setNickname("legacy");
        user.setRole("USER");
        user.setStatus(1);
        LoginRequest request = new LoginRequest();
        request.setType("password");
        request.setAccount("legacy");
        request.setPassword("plain-secret");
        when(userMapper.selectOne(any())).thenReturn(user);
        stubTokenIssuance();

        TokenResponse response = service.login(request, "127.0.0.1");

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getPassword()).startsWith("$2");
        assertThat(passwordEncoder.matches("plain-secret", captor.getValue().getPassword())).isTrue();
    }

    @Test
    void passwordLoginRejectsIncorrectPasswordForHashedAccount() {
        User user = user(11L, "hashed", "correct-horse", 1);
        LoginRequest request = new LoginRequest();
        request.setType("password");
        request.setAccount("hashed");
        request.setPassword("wrong");
        when(userMapper.selectOne(any())).thenReturn(user);

        assertThatThrownBy(() -> service.login(request, "127.0.0.1")).isInstanceOf(IllegalArgumentException.class);
        verify(jwtUtil, never()).generateAccessToken(anyString(), any(Map.class));
    }
}

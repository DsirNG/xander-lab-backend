package com.xander.lab.identity.application;

import com.xander.lab.common.Constants;
import com.xander.lab.billing.application.BillingService;
import com.xander.lab.identity.api.LoginRequest;
import com.xander.lab.identity.api.TokenResponse;
import com.xander.lab.identity.domain.User;
import com.xander.lab.identity.infrastructure.UserMapper;
import com.xander.lab.identity.infrastructure.wechat.WechatMiniAppClient;
import com.xander.lab.identity.infrastructure.wechat.WechatMiniAppClient.WechatSession;
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
import static org.mockito.Mockito.mock;
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
    @Mock private WechatMiniAppClient wechatMiniAppClient;

    private AuthService service;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private BillingService billingService;

    @BeforeEach
    void setUp() {
        billingService = mock(BillingService.class);
        service = new AuthService(jwtUtil, mailService, userMapper, redisTemplate, passwordEncoder,
                billingService, wechatMiniAppClient);
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

    @Test
    void wechatLoginIssuesTokenForExistingBoundUser() {
        User user = user(21L, "wx_user", "secret", 1);
        user.setOpenid("oWX-openid-001");
        when(wechatMiniAppClient.code2Session("wx-code")).thenReturn(sessionOk("oWX-openid-001"));
        when(userMapper.selectOne(any())).thenReturn(user);
        stubTokenIssuance();

        TokenResponse response = service.wechatLogin("wx-code", "127.0.0.1");

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getUserInfo().getUsername()).isEqualTo("wx_user");
        verify(userMapper, never()).insert(any());
    }

    @Test
    void wechatLoginAutoRegistersFirstTimeUserAndGrantsBonus() {
        when(wechatMiniAppClient.code2Session("first-code")).thenReturn(sessionOk("oWX-new-user"));
        when(userMapper.selectOne(any())).thenReturn(null);
        when(userMapper.insert(any())).thenAnswer(invocation -> {
            User inserted = invocation.getArgument(0);
            inserted.setId(99L);
            return 1;
        });
        stubTokenIssuance();

        TokenResponse response = service.wechatLogin("first-code", "127.0.0.1");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertThat(captor.getValue().getOpenid()).isEqualTo("oWX-new-user");
        assertThat(captor.getValue().getUsername()).startsWith("wx_").hasSize(19);
        assertThat(captor.getValue().getNickname()).startsWith("微信用户");
        assertThat(captor.getValue().getPassword()).matches("[0-9a-f]{64}");
        assertThat(response.getUserInfo().getUsername()).isEqualTo(captor.getValue().getUsername());
        verify(billingService).grantRegister(99L, "user:99");
    }

    @Test
    void wechatLoginFailsWhenWechatCodeIsRejected() {
        when(wechatMiniAppClient.code2Session("bad-code"))
                .thenReturn(new WechatSession(40029, "invalid code", null, null, null));

        assertThatThrownBy(() -> service.wechatLogin("bad-code", "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("微信登录失败");
        verify(userMapper, never()).selectOne(any());
        verify(userMapper, never()).insert(any());
    }

    @Test
    void wechatLoginMapsUnconfiguredChannelToBusinessError() {
        when(wechatMiniAppClient.code2Session(anyString()))
                .thenThrow(new IllegalStateException("微信登录未配置，请联系管理员"));

        assertThatThrownBy(() -> service.wechatLogin("code", "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未配置");
    }

    @Test
    void wechatLoginRejectsDisabledAccount() {
        User user = user(22L, "wx_banned", "secret", 0);
        user.setOpenid("oWX-banned");
        when(wechatMiniAppClient.code2Session("code")).thenReturn(sessionOk("oWX-banned"));
        when(userMapper.selectOne(any())).thenReturn(user);

        assertThatThrownBy(() -> service.wechatLogin("code", "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("禁用");
        verify(jwtUtil, never()).generateAccessToken(anyString(), any(Map.class));
    }

    @Test
    void wechatLoginIsRateLimitedPerIp() {
        when(valueOperations.increment(anyString())).thenReturn(21L);

        assertThatThrownBy(() -> service.wechatLogin("code", "10.0.0.1"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(429);
        verify(wechatMiniAppClient, never()).code2Session(anyString());
    }

    private WechatSession sessionOk(String openid) {
        return new WechatSession(0, null, openid, "session-key", null);
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

    @Test
    void updateProfileRejectsInvalidTokenWithUnauthorized() {
        when(jwtUtil.isValid("bad-token")).thenReturn(false);

        assertThatThrownBy(() -> service.updateProfile("Bearer bad-token", "新昵称", null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(401);
        verify(userMapper, never()).updateById(any());
    }

    @Test
    void updateProfileRejectsBlankNickname() {
        when(jwtUtil.isValid("access")).thenReturn(true);

        assertThatThrownBy(() -> service.updateProfile("Bearer access", "   ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("昵称");
        verify(userMapper, never()).updateById(any());
    }

    @Test
    void updateProfileRejectsNonHttpAvatar() {
        when(jwtUtil.isValid("access")).thenReturn(true);

        assertThatThrownBy(() -> service.updateProfile("Bearer access", "新昵称", "ftp://x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http");
        verify(userMapper, never()).updateById(any());
    }

    @Test
    void updateProfilePersistsNicknameAndAvatarAndReturnsUserInfo() {
        User user = user(5L, "alice", "secret", 1);
        user.setEmail("alice@example.com");
        when(jwtUtil.isValid("access")).thenReturn(true);
        when(jwtUtil.getSubject("access")).thenReturn("5");
        when(userMapper.selectById("5")).thenReturn(user);

        TokenResponse.UserInfo info = service.updateProfile("Bearer access", " 新昵称 ", "https://example.com/a.png");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getNickname()).isEqualTo("新昵称");
        assertThat(captor.getValue().getAvatar()).isEqualTo("https://example.com/a.png");
        assertThat(info.getNickname()).isEqualTo("新昵称");
        assertThat(info.getEmail()).isEqualTo("alice@example.com");
    }

    @Test
    void updateProfileKeepsExistingAvatarWhenBlank() {
        User user = user(5L, "alice", "secret", 1);
        when(jwtUtil.isValid("access")).thenReturn(true);
        when(jwtUtil.getSubject("access")).thenReturn("5");
        when(userMapper.selectById("5")).thenReturn(user);

        service.updateProfile("Bearer access", "新昵称", "  ");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getAvatar()).isNull();
    }
}

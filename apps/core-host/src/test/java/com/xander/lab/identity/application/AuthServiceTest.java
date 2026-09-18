package com.xander.lab.identity.application;

import com.xander.lab.common.Constants;
import com.xander.lab.payment.api.BillingPort;
import com.xander.lab.identity.api.LoginRequest;
import com.xander.lab.identity.api.TokenResponse;
import com.xander.lab.identity.domain.User;
import com.xander.lab.identity.infrastructure.AccountMergeMapper;
import com.xander.lab.identity.infrastructure.UserMapper;
import com.xander.lab.identity.infrastructure.wechat.WechatMiniAppClient;
import com.xander.lab.identity.infrastructure.wechat.WechatMiniAppClient.WechatSession;
import com.xander.lab.auth.core.JwtUtil;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
    @Mock private AccountMergeMapper accountMergeMapper;

    private AuthService service;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private BillingPort billingPort;

    @BeforeEach
    void setUp() {
        billingPort = mock(BillingPort.class);
        service = new AuthService(jwtUtil, mailService, userMapper, redisTemplate, passwordEncoder,
                billingPort, wechatMiniAppClient, accountMergeMapper);
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

    /**
     * {@code AccessTokenLookupPort} 的实现本体。
     *
     * <p>⚠️ 这几条是这一刀补的：在此之前 {@code getUserIdFromAccessToken}
     * <b>一个测试都没有</b> —— 它只是 identity 内部的一个便捷方法，
     * 没有测试也没人发现。现在它变成了 {@code core.api.AccessTokenLookupPort}
     * 的实现，也就是 agent 的 WebSocket 握手唯一的身份来源，
     * 于是"返回什么"必须是钉住的。</p>
     */
    @Test
    void resolveUserIdReturnsTheSubjectOfALiveAccessToken() {
        when(jwtUtil.isValid("access")).thenReturn(true);
        when(jwtUtil.isRefreshToken("access")).thenReturn(false);
        when(redisTemplate.hasKey(Constants.REDIS_TOKEN_PREFIX + "access")).thenReturn(true);
        when(jwtUtil.getSubject("access")).thenReturn("42");

        assertThat(service.resolveUserId("access")).isEqualTo(42L);
    }

    @Test
    void resolveUserIdReturnsNullWhenTheTokenIsNoLongerARegisteredSession() {
        when(jwtUtil.isValid("access")).thenReturn(true);
        when(jwtUtil.isRefreshToken("access")).thenReturn(false);
        when(redisTemplate.hasKey(Constants.REDIS_TOKEN_PREFIX + "access")).thenReturn(false);

        assertThat(service.resolveUserId("access")).isNull();
        // 登出过的令牌不该再被解析出身份 —— 这条断言是"短路"的证据：
        // 少一次 getSubject 意味着 Redis 检查真的挡住了它。
        verify(jwtUtil, never()).getSubject("access");
    }

    @Test
    void resolveUserIdReturnsNullForARefreshToken() {
        when(jwtUtil.isValid("refresh")).thenReturn(true);
        when(jwtUtil.isRefreshToken("refresh")).thenReturn(true);

        assertThat(service.resolveUserId("refresh")).isNull();
        // refresh 令牌即使还在 Redis 里也不能当身份用，所以连 Redis 都不该查。
        verify(redisTemplate, never()).hasKey(anyString());
    }

    @Test
    void resolveUserIdReturnsNullWhenTheSubjectIsNotAUserId() {
        when(jwtUtil.isValid("weird")).thenReturn(true);
        when(jwtUtil.isRefreshToken("weird")).thenReturn(false);
        when(redisTemplate.hasKey(Constants.REDIS_TOKEN_PREFIX + "weird")).thenReturn(true);
        when(jwtUtil.getSubject("weird")).thenReturn("alice");

        // subject 不是数字时返回 null 而不是抛 NumberFormatException ——
        // 握手阶段收到奇怪令牌是预期结果，不是故障。
        assertThat(service.resolveUserId("weird")).isNull();
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
    void wechatLoginFirstTimeReturnsPendingBindTokenInsteadOfAutoRegistering() {
        when(wechatMiniAppClient.code2Session("first-code")).thenReturn(sessionOk("oWX-new-user"));
        when(userMapper.selectOne(any())).thenReturn(null);
        when(jwtUtil.generateBindToken("oWX-new-user")).thenReturn("bind-token");

        TokenResponse response = service.wechatLogin("first-code", "127.0.0.1");

        assertThat(response.isPendingBind()).isTrue();
        assertThat(response.getPendingBindToken()).isEqualTo("bind-token");
        assertThat(response.getAccessToken()).isNull();
        // 未建号：不得自动注册、不得发 token、不触发注册赠送
        verify(userMapper, never()).insert(any());
        verify(jwtUtil, never()).generateAccessToken(anyString(), any(Map.class));
        verify(billingPort, never()).grantRegister(any(), any());
    }

    @Test
    void bindWechatCreatesUserWithEmailWhenEmailIsNew() {
        when(jwtUtil.isValid("bind-token")).thenReturn(true);
        when(jwtUtil.isBindToken("bind-token")).thenReturn(true);
        when(jwtUtil.getSubject("bind-token")).thenReturn("oWX-new-user");
        when(userMapper.selectOne(any())).thenReturn(null);
        when(valueOperations.get(Constants.REDIS_CODE_PREFIX + "new@example.com")).thenReturn("123456");
        when(userMapper.insert(any())).thenAnswer(invocation -> {
            User inserted = invocation.getArgument(0);
            inserted.setId(88L);
            return 1;
        });
        stubTokenIssuance();

        TokenResponse response = service.bindWechat("bind-token", "NEW@EXAMPLE.com", "123456", "127.0.0.1");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("new@example.com"); // 规范化小写
        assertThat(captor.getValue().getOpenid()).isEqualTo("oWX-new-user");
        assertThat(captor.getValue().getUsername()).isEqualTo("new@example.com");
        assertThat(captor.getValue().getPassword()).matches("[0-9a-f]{64}"); // 随机口令仅存哈希
        assertThat(response.getUserInfo().getEmail()).isEqualTo("new@example.com");
        // 验证码一次性消费
        verify(valueOperations).get(Constants.REDIS_CODE_PREFIX + "new@example.com");
        verify(redisTemplate).delete(Constants.REDIS_CODE_PREFIX + "new@example.com");
        verify(billingPort).grantRegister(88L, "user:88");
    }

    @Test
    void bindWechatMergesIntoExistingPcAccountByEmail() {
        User temp = user(30L, "wx_temp", "secret", 1);
        temp.setOpenid("oWX-merge");
        User pc = user(31L, "pc@example.com", "secret", 1);
        pc.setEmail("pc@example.com");
        when(jwtUtil.isValid("bind-token")).thenReturn(true);
        when(jwtUtil.isBindToken("bind-token")).thenReturn(true);
        when(jwtUtil.getSubject("bind-token")).thenReturn("oWX-merge");
        when(userMapper.selectOne(any())).thenReturn(null, pc); // openid 无既有账号 → 邮箱已存在
        when(valueOperations.get(Constants.REDIS_CODE_PREFIX + "pc@example.com")).thenReturn("654321");
        stubTokenIssuance();

        TokenResponse response = service.bindWechat("bind-token", "pc@example.com", "654321", "127.0.0.1");

        // 邮箱已有 PC 账号：不建新号，openid 挂到既有账号（微信与 PC 同一账号）
        verify(userMapper, never()).insert(any());
        verify(userMapper).updateById(argThat(u -> u.getId().equals(31L) && "oWX-merge".equals(u.getOpenid())));
        assertThat(response.getUserInfo().getUsername()).isEqualTo("pc@example.com");
    }

    @Test
    void skipBindCreatesTempWechatUser() {
        when(jwtUtil.isValid("bind-token")).thenReturn(true);
        when(jwtUtil.isBindToken("bind-token")).thenReturn(true);
        when(jwtUtil.getSubject("bind-token")).thenReturn("oWX-skip");
        when(userMapper.selectOne(any())).thenReturn(null);
        when(userMapper.insert(any())).thenAnswer(invocation -> {
            User inserted = invocation.getArgument(0);
            inserted.setId(77L);
            return 1;
        });
        stubTokenIssuance();

        TokenResponse response = service.createWechatUser("bind-token", "127.0.0.1");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertThat(captor.getValue().getOpenid()).isEqualTo("oWX-skip");
        assertThat(captor.getValue().getEmail()).isNull(); // 临时账号未绑定邮箱
        assertThat(response.isNeedsEmailBinding()).isTrue();
        verify(billingPort).grantRegister(77L, "user:77");
    }

    @Test
    void bindExistingOnTempUserMergesBusinessDataAndBalanceIntoPcAccount() {
        User temp = user(40L, "wx_temp", "secret", 1);
        temp.setOpenid("oWX-existing");
        User pc = user(41L, "pc@example.com", "secret", 1);
        pc.setEmail("pc@example.com");
        // PC 账号尚未绑定过微信，处于可合并状态
        pc.setOpenid(null);
        when(jwtUtil.isValid("access")).thenReturn(true);
        when(jwtUtil.getSubject("access")).thenReturn("40");
        when(userMapper.selectById("40")).thenReturn(temp);
        when(userMapper.selectOne(any())).thenReturn(pc); // 邮箱已是既有 PC 账号
        when(valueOperations.get(Constants.REDIS_CODE_PREFIX + "pc@example.com")).thenReturn("111222");
        stubTokenIssuance();

        TokenResponse response = service.bindWechatExisting("Bearer access", "pc@example.com", "111222", "127.0.0.1");

        // 业务数据迁转至目标账号
        verify(accountMergeMapper).reparentAgentConversations(40L, 41L);
        verify(accountMergeMapper).reparentBlogAgentTasks(40L, 41L);
        verify(accountMergeMapper).reparentFlowProjects(40L, 41L);
        // 积分并入目标账号
        verify(billingPort).transferBalance(40L, 41L, "merge:40:41", "账号合并转入");
        // 临时账号删除，openid 挂到目标账号
        verify(userMapper).deleteById(40L);
        // 解绑 openid 用显式 SQL 而非 updateById：null 字段会被 MyBatis-Plus 跳过生成空 SET（回归）
        verify(userMapper).clearOpenid(40L);
        verify(userMapper).updateById(argThat(u -> u.getId().equals(41L) && "oWX-existing".equals(u.getOpenid())));
        assertThat(response.getUserInfo().getUsername()).isEqualTo("pc@example.com");
    }

    @Test
    void bindExistingInvalidTokenReturnsUnauthorized() {
        when(jwtUtil.isValid("bad-token")).thenReturn(false);

        assertThatThrownBy(() -> service.bindWechatExisting("Bearer bad-token", "a@b.com", "000000", "127.0.0.1"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(401);
        verify(accountMergeMapper, never()).reparentAgentConversations(any(), any());
    }

    @Test
    void bindWechatAfterSkipMergesTempUserIntoPcAccount() {
        User temp = user(50L, "wx_skip", "secret", 1);
        temp.setOpenid("oWX-skip");
        User pc = user(51L, "pc@example.com", "secret", 1);
        pc.setEmail("pc@example.com");
        pc.setOpenid(null);
        when(jwtUtil.isValid("bind-token")).thenReturn(true);
        when(jwtUtil.isBindToken("bind-token")).thenReturn(true);
        when(jwtUtil.getSubject("bind-token")).thenReturn("oWX-skip");
        when(userMapper.selectOne(any())).thenReturn(temp, pc); // 先查 openid 命中临时号，再查邮箱命中 PC 号
        when(valueOperations.get(Constants.REDIS_CODE_PREFIX + "pc@example.com")).thenReturn("555666");
        stubTokenIssuance();

        TokenResponse response = service.bindWechat("bind-token", "pc@example.com", "555666", "127.0.0.1");

        // 临时号数据/积分并入 PC 账号并删除临时号，openid 挂到 PC 账号
        verify(billingPort).transferBalance(50L, 51L, "merge:50:51", "账号合并转入");
        verify(userMapper).deleteById(50L);
        verify(userMapper).clearOpenid(50L);
        verify(userMapper).updateById(argThat(u -> u.getId().equals(51L) && "oWX-skip".equals(u.getOpenid())));
        assertThat(response.getUserInfo().getUsername()).isEqualTo("pc@example.com");
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

        assertThatThrownBy(() -> service.updateProfile("Bearer bad-token", null, "新昵称", null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(401);
        verify(userMapper, never()).updateById(any());
    }

    @Test
    void updateProfileRejectsBlankNickname() {
        when(jwtUtil.isValid("access")).thenReturn(true);

        assertThatThrownBy(() -> service.updateProfile("Bearer access", null, "   ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("昵称");
        verify(userMapper, never()).updateById(any());
    }

    @Test
    void updateProfileRejectsNonHttpAvatar() {
        when(jwtUtil.isValid("access")).thenReturn(true);

        assertThatThrownBy(() -> service.updateProfile("Bearer access", null, "新昵称", "ftp://x"))
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

        TokenResponse.UserInfo info = service.updateProfile("Bearer access", null, " 新昵称 ", "https://example.com/a.png");

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

        service.updateProfile("Bearer access", null, "新昵称", "  ");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getAvatar()).isNull();
    }

    @Test
    void changePasswordVerifiesCurrentPasswordAndStoresBcryptHash() {
        User user = user(5L, "alice", "old-secret", 1);
        when(jwtUtil.isValid("access")).thenReturn(true);
        when(jwtUtil.getSubject("access")).thenReturn("5");
        when(userMapper.selectById("5")).thenReturn(user);

        service.changePassword("Bearer access", "old-secret", "new-secret");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(passwordEncoder.matches("new-secret", captor.getValue().getPassword())).isTrue();
    }

    @Test
    void resetPasswordConsumesCodeUpdatesHashAndRevokesSessions() {
        User user = user(6L, "alice", "old-secret", 1);
        user.setEmail("alice@example.com");
        when(valueOperations.get(Constants.REDIS_CODE_PREFIX + "alice@example.com")).thenReturn("123456");
        when(userMapper.selectOne(any())).thenReturn(user);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(Constants.REDIS_USER_TOKENS_PREFIX + "6")).thenReturn(Set.of());

        service.resetPassword("Alice@example.com", "123456", "new-secret");

        verify(redisTemplate).delete(Constants.REDIS_CODE_PREFIX + "alice@example.com");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(passwordEncoder.matches("new-secret", captor.getValue().getPassword())).isTrue();
    }
}

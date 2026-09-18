package com.xander.lab.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xander.lab.payment.api.BillingPort;
import com.xander.lab.core.api.AccessTokenLookupPort;
import com.xander.lab.common.Constants;
import com.xander.lab.common.Result;
import com.xander.lab.identity.api.LoginRequest;
import com.xander.lab.identity.api.TokenResponse;
import com.xander.lab.identity.domain.User;
import com.xander.lab.identity.infrastructure.AccountMergeMapper;
import com.xander.lab.identity.infrastructure.UserMapper;
import com.xander.lab.identity.infrastructure.wechat.WechatMiniAppClient;
import com.xander.lab.identity.infrastructure.wechat.WechatMiniAppClient.WechatSession;
import com.xander.lab.auth.core.JwtUtil;
import com.xander.lab.integration.api.MailPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 认证服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService implements AccessTokenLookupPort {

    private static final SecureRandom CODE_RANDOM = new SecureRandom();

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final JwtUtil jwtUtil;
    private final MailPort mailPort;
    private final UserMapper userMapper;
    private final StringRedisTemplate redisTemplate;
    private final PasswordEncoder passwordEncoder;
    private final BillingPort billingPort;
    private final WechatMiniAppClient wechatMiniAppClient;
    private final AccountMergeMapper accountMergeMapper;

/**
     * 发送登录验证码
     * 限流：同一邮箱每 60 秒最多 1 次（验证码本身覆盖旧值），防止验证码轰炸
     */
    public void sendCode(String email, String clientIp) {
        checkRateLimit("code", email, 1, 60);
        checkRateLimit("code:ip", sanitize(clientIp), 20, 60);

        // 生成 6 位随机验证码
        String code = String.valueOf(100000 + CODE_RANDOM.nextInt(900000));

        // 入参邮箱按与校验方一致的规范化形式存储（大小写不敏感），发送给原始地址
        String keyEmail = email == null ? "" : email.trim().toLowerCase();
        String key = Constants.REDIS_CODE_PREFIX + keyEmail;
        redisTemplate.opsForValue().set(key, code, Constants.CODE_EXPIRE_MINUTES, TimeUnit.MINUTES);

        // 发送邮件
        mailPort.sendVerificationCode(email, code);

        log.info("[Auth] 验证码已发送至 {}", email);
    }

    /**
     * Fixed-window Redis rate limiter：窗口内原子递增，超限返回 HTTP 429
     */
    private void checkRateLimit(String bucket, String key, int limit, int windowSeconds) {
        String redisKey = Constants.REDIS_RATE_PREFIX + bucket + ":" + key;
        Long count = redisTemplate.opsForValue().increment(redisKey);
        if (count != null && count == 1) {
            redisTemplate.expire(redisKey, windowSeconds, TimeUnit.SECONDS);
        }
        if (count != null && count > limit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试");
        }
    }

    private String sanitize(String value) {
        if (value == null) return "unknown";
        return value.replaceAll("[^A-Za-z0-9._:-]", "");
    }

    /**
     * 登录
     * 限流：同一账号每 60 秒最多 5 次尝试，同一 IP 每 60 秒最多 20 次，防暴力枚举
     */
    public TokenResponse login(LoginRequest request, String clientIp) {
        String account = request.getAccount();
        checkRateLimit("login", sanitize(account), 5, 60);
        checkRateLimit("login:ip", sanitize(clientIp), 20, 60);

        User user;

        if ("code".equalsIgnoreCase(request.getType())) {
            // 验证码登录逻辑（键与 sendCode 写入的规范化形式一致）
            String key = Constants.REDIS_CODE_PREFIX + account.trim().toLowerCase();
            String cachedCode = redisTemplate.opsForValue().get(key);
            
            if (cachedCode == null || !cachedCode.equals(request.getCode())) {
                throw new IllegalArgumentException("验证码错误或已过期");
            }
            
            // 根据邮箱查询用户，若不存在则自动注册
            user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, account));
            if (user == null) {
                user = registerUser(account, null, account, "USER");
            }
            // 登录成功，移除验证码
            redisTemplate.delete(key);
        } else {
            // 密码登录逻辑
            user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                    .eq(User::getUsername, account)
                    .or()
                    .eq(User::getEmail, account));

            if (user == null || !matchesPassword(user, request.getPassword())) {
                throw new IllegalArgumentException("用户名或密码错误");
            }
        }

        if (user.getStatus() == 0) {
            throw new IllegalArgumentException("账号已被禁用，请联系管理员");
        }

        return generateTokenResponse(user);
    }

    /**
     * 校验密码
     * 兼容历史明文存储：若匹配成功且库中仍是明文，则原地升级为 BCrypt，避免明文长期留存
     */
    private boolean matchesPassword(User user, String rawPassword) {
        String storedPassword = user.getPassword();
        if (storedPassword == null) {
            return false;
        }
        boolean matched;
        if (isBcryptHash(storedPassword)) {
            matched = passwordEncoder.matches(rawPassword, storedPassword);
        } else {
            // 历史明文兼容：仅当全部为明文时比对，保证旧账号可继续登录
            matched = storedPassword.equals(rawPassword);
        }
        if (matched) {
            upgradePasswordIfLegacy(user, rawPassword);
        }
        return matched;
    }

    private boolean isBcryptHash(String storedPassword) {
        return storedPassword.startsWith("$2a$") || storedPassword.startsWith("$2b$") || storedPassword.startsWith("$2y$");
    }

    /**
     * 若密码仍是历史明文，升级为 BCrypt 哈希存储（仅在登录成功后触发）
     */
    private void upgradePasswordIfLegacy(User user, String rawPassword) {
        if (isBcryptHash(user.getPassword())) {
            return;
        }
        try {
            User update = new User();
            update.setId(user.getId());
            update.setPassword(passwordEncoder.encode(rawPassword));
            userMapper.updateById(update);
            log.info("[Auth] 用户 {} 的明文密码已升级为 BCrypt", user.getId());
        } catch (Exception e) {
            log.warn("[Auth] 升级历史明文密码失败，不影响本次登录: {}", e.getMessage());
        }
    }

    /**
     * 自动注册用户
     * 验证码登录注册的用户不知道登录密码，生成随机口令并哈希存储，避免明文默认密码
     */
    private User registerUser(String username, String password, String email, String role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(password != null ? passwordEncoder.encode(password) : generateRandomPassword());
        user.setNickname(username.contains("@") ? username.split("@")[0] : username);
        user.setEmail(email);
        user.setAvatar("https://api.dicebear.com/7.x/avataaars/svg?seed=" + username);
        user.setRole(role);
        user.setStatus(1);
        userMapper.insert(user);
        billingPort.grantRegister(user.getId(), "user:" + user.getId());
        return user;
    }

    /**
     * 生成长随机密码，用于验证码登录自动注册的账号（无明文，仅存 BCrypt 哈希）
     */
    private String generateRandomPassword() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 微信小程序登录：code 换 openid 后按「先绑定后建号」策略处理。
     * <ul>
     *   <li>openid 已注册（或已绑定邮箱的既有账号）→ 直接签发 token。</li>
     *   <li>openid 未注册 → 不建号，返回待绑定凭证（pendingBind=true），
     *       由前端引导绑定邮箱（邮箱是 PC 端账号；若已是既有账号则合并为同一账号）或跳过建号。</li>
     * </ul>
     * 限流：同一 IP 每 60 秒最多 20 次；微信通道未配置或服务不可用时归一化为 400 业务错误。
     */
    public TokenResponse wechatLogin(String code, String clientIp) {
        checkRateLimit("login:ip", sanitize(clientIp), 20, 60);

        WechatSession session;
        try {
            session = wechatMiniAppClient.code2Session(code);
        } catch (IllegalStateException e) {
            // 微信通道未配置或服务不可用：登录失败，不暴露原始错误细节
            throw new IllegalArgumentException(e.getMessage());
        }
        if (!session.isOk()) {
            log.warn("[Auth] 微信 code 校验失败: errcode={}", session.errcode());
            throw new IllegalArgumentException("微信登录失败，请重试");
        }

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getOpenid, session.openid()));
        if (user != null) {
            if (user.getStatus() == 0) {
                throw new IllegalArgumentException("账号已被禁用，请联系管理员");
            }
            return generateTokenResponse(user);
        }

        // 首次登录：未建号，返回待绑定凭证；绑定邮箱或跳过后再签发正式 token
        return TokenResponse.builder()
                .pendingBind(true)
                .pendingBindToken(jwtUtil.generateBindToken(session.openid()))
                .needsEmailBinding(true)
                .build();
    }

    /**
     * 首次微信登录「绑定邮箱」：校验验证码后把 openid 与邮箱建立绑定并签发 token。
     * 邮箱如果已是 PC 端既有账号，则直接给该账号挂载 openid（微信与 PC 为同一账号）。
     * 幂等：重复提交/并发时重查已有账号，不重复建号。
     * 整个绑定/合并过程在单个事务内执行，避免半合并残留状态。
     */
    @Transactional
    public TokenResponse bindWechat(String pendingBindToken, String email, String code, String clientIp) {
        checkRateLimit("bind:ip", sanitize(clientIp), 20, 60);
        String openid = resolveOpenidFromPendingToken(pendingBindToken);
        String normalized = normalizeEmail(email);
        verifyEmailCode(normalized, code);

        User existingByOpenid = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getOpenid, openid));
        if (existingByOpenid != null) {
            if (existingByOpenid.getStatus() == 0) {
                throw new IllegalArgumentException("账号已被禁用，请联系管理员");
            }
            // 跳过绑定建过的临时账号再补绑：邮箱若已是既有 PC 账号，则合并而非重复绑邮箱
            if (existingByOpenid.getEmail() == null || existingByOpenid.getEmail().isBlank()) {
                User byEmail = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, normalized));
                if (byEmail != null) {
                    if (byEmail.getStatus() == 0) {
                        throw new IllegalArgumentException("账号已被禁用，请联系管理员");
                    }
                    if (byEmail.getOpenid() != null && !byEmail.getOpenid().equals(openid)) {
                        throw new IllegalArgumentException("该邮箱已绑定其他微信账号");
                    }
                    if (!byEmail.getId().equals(existingByOpenid.getId())) {
                        mergeWechatUserInto(existingByOpenid, byEmail);
                        return generateTokenResponse(byEmail);
                    }
                    return generateTokenResponse(existingByOpenid); // 幂等
                }
                attachEmail(existingByOpenid, normalized);
            }
            return generateTokenResponse(existingByOpenid);
        }

        User existingByEmail = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, normalized));
        if (existingByEmail != null) {
            if (existingByEmail.getStatus() == 0) {
                throw new IllegalArgumentException("账号已被禁用，请联系管理员");
            }
            if (existingByEmail.getOpenid() != null && !existingByEmail.getOpenid().equals(openid)) {
                throw new IllegalArgumentException("该邮箱已绑定其他微信账号");
            }
            if (existingByEmail.getOpenid() == null) {
                attachOpenid(existingByEmail, openid);
            }
            return generateTokenResponse(existingByEmail); // 微信与 PC 为同一账号
        }

        User created = createUserWithEmail(normalized, openid);
        return generateTokenResponse(created);
    }

    /**
     * 首次微信登录「跳过绑定」：给 openid 创建临时微信账号并签发 token（needsEmailBinding=true）。
     * 幂等：并发注册由 uk_openid 唯一索引兜底，冲突时重查已有账号继续登录。
     */
    public TokenResponse createWechatUser(String pendingBindToken, String clientIp) {
        checkRateLimit("bind:ip", sanitize(clientIp), 20, 60);
        String openid = resolveOpenidFromPendingToken(pendingBindToken);

        User existing = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getOpenid, openid));
        if (existing != null) {
            if (existing.getStatus() == 0) {
                throw new IllegalArgumentException("账号已被禁用，请联系管理员");
            }
            return generateTokenResponse(existing);
        }
        return generateTokenResponse(registerWechatUser(openid));
    }

    /**
     * 账号设置补绑邮箱：已登录的未绑定微信账号（跳过过绑定）绑定邮箱。
     * 邮箱已是既有 PC 账号时执行合并：迁转名下的对话/计划/提醒/任务/积分后删除临时账号，
     * 从微信与 PC 登录都指向同一账号。token 无效或缺失返回 401。
     * 整个绑定/合并过程在单个事务内执行，避免半合并残留状态。
     */
    @Transactional
    public TokenResponse bindWechatExisting(String bearerToken, String email, String code, String clientIp) {
        String token;
        try {
            token = extractToken(bearerToken);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        if (!jwtUtil.isValid(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        String userId = jwtUtil.getSubject(token);
        User temp = userMapper.selectById(userId);
        if (temp == null || temp.getOpenid() == null || temp.getOpenid().isBlank()) {
            throw new IllegalArgumentException("当前账号不是可绑定邮箱的微信账号");
        }
        if (temp.getStatus() == 0) {
            throw new IllegalArgumentException("账号已被禁用，请联系管理员");
        }
        if (temp.getEmail() != null && !temp.getEmail().isBlank()) {
            throw new IllegalArgumentException("当前账号已绑定邮箱");
        }

        checkRateLimit("bind:ip", sanitize(clientIp), 20, 60);
        String normalized = normalizeEmail(email);
        verifyEmailCode(normalized, code);

        User existingByEmail = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, normalized));
        if (existingByEmail != null) {
            if (existingByEmail.getStatus() == 0) {
                throw new IllegalArgumentException("账号已被禁用，请联系管理员");
            }
            if (existingByEmail.getOpenid() != null && !existingByEmail.getOpenid().equals(temp.getOpenid())) {
                throw new IllegalArgumentException("该邮箱已绑定其他微信账号");
            }
            if (existingByEmail.getId().equals(temp.getId())) {
                return generateTokenResponse(temp); // 幂等
            }
            mergeWechatUserInto(temp, existingByEmail);
            return generateTokenResponse(existingByEmail);
        }

        // 邮箱未注册：直接挂到当前微信账号
        attachEmail(temp, normalized);
        return generateTokenResponse(temp);
    }

    /** 校验并消费邮箱验证码（sendCode 以“规范化邮箱”为键写入，此处用同一键读取） */
    private void verifyEmailCode(String email, String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("请输入验证码");
        }
        String key = Constants.REDIS_CODE_PREFIX + email;
        String cachedCode = redisTemplate.opsForValue().get(key);
        if (cachedCode == null || !cachedCode.equals(code.trim())) {
            throw new IllegalArgumentException("验证码错误或已过期");
        }
        redisTemplate.delete(key);
    }

    private String normalizeEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase();
        if (!EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }
        return normalized;
    }

    /** 校验待绑定凭证有效性并取出 openid */
    private String resolveOpenidFromPendingToken(String pendingBindToken) {
        if (pendingBindToken == null || !jwtUtil.isValid(pendingBindToken) || !jwtUtil.isBindToken(pendingBindToken)) {
            throw new IllegalArgumentException("绑定凭证无效或已过期，请重新登录");
        }
        return jwtUtil.getSubject(pendingBindToken);
    }

    /**
     * 首次微信绑定邮箱：邮箱没有既有账号时创建新账号（username=邮箱，与 PC 注册约定一致，
     * 密码为随机口令仅存哈希；账号实时已通过验证码验证邮箱归属，后续可用验证码登录）。
     */
    private User createUserWithEmail(String email, String openid) {
        User user = new User();
        user.setUsername(email);
        user.setPassword(generateRandomPassword());
        user.setNickname(email.contains("@") ? email.split("@")[0] : email);
        user.setEmail(email);
        user.setOpenid(openid);
        user.setAvatar("https://api.dicebear.com/7.x/avataaars/svg?seed=" + email);
        user.setRole("USER");
        user.setStatus(1);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发建号：uk_openid / uk_email 冲突时重查已有账号，保证幂等
            User existing = userMapper.selectOne(new LambdaQueryWrapper<User>()
                    .eq(User::getOpenid, openid)
                    .or()
                    .eq(User::getEmail, email));
            if (existing != null) {
                return existing;
            }
            throw e;
        }
        billingPort.grantRegister(user.getId(), "user:" + user.getId());
        log.info("[Auth] 微信绑定邮箱新建账号 userId={}", user.getId());
        return user;
    }

    /**
     * 首次微信登录自动注册（跳过绑定路径）：openid 作为绑定凭证，username 取 openid 哈希。
     * 并发注册由 uk_openid 唯一索引兜底，冲突时重查已有账号继续登录（幂等）。
     */
    private User registerWechatUser(String openid) {
        String username = "wx_" + sha256Hex(openid).substring(0, 16);
        try {
            User user = new User();
            user.setUsername(username);
            user.setPassword(generateRandomPassword());
            user.setNickname("微信用户" + (1000 + CODE_RANDOM.nextInt(9000)));
            user.setOpenid(openid);
            user.setAvatar("https://api.dicebear.com/7.x/avataaars/svg?seed=" + username);
            user.setRole("USER");
            user.setStatus(1);
            userMapper.insert(user);
            billingPort.grantRegister(user.getId(), "user:" + user.getId());
            log.info("[Auth] 微信用户自动注册 userId={}", user.getId());
            return user;
        } catch (DuplicateKeyException e) {
            User existing = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getOpenid, openid));
            if (existing != null) {
                return existing;
            }
            throw e;
        }
    }

    /** 把微信临时账号名下数据迁到既有账号：迁移业务数据、合并积分、清理登录态后删除临时号。
     *  先迁走数据再删行，避免外键级联误删用户数据；先解绑 openid 再挂到目标账号规避唯一键冲突。 */
    private void mergeWechatUserInto(User temp, User target) {
        String openid = temp.getOpenid();
        // 1. 迁转名下的业务数据到既有账号（子记录随外键级联跟随）
        accountMergeMapper.reparentAgentConversations(temp.getId(), target.getId());
        accountMergeMapper.reparentBlogAgentTasks(temp.getId(), target.getId());
        accountMergeMapper.reparentBlogContentPlans(temp.getId(), target.getId());
        accountMergeMapper.reparentBlogNotifications(temp.getId(), target.getId());
        accountMergeMapper.reparentBlogPublishRecords(temp.getId(), target.getId());
        accountMergeMapper.reparentBlogMediaAssets(temp.getId(), target.getId());
        accountMergeMapper.reparentEmailReminders(temp.getId(), target.getId());
        accountMergeMapper.reparentImg2threeTasks(temp.getId(), target.getId());
        accountMergeMapper.reparentFlowProjects(temp.getId(), target.getId());
        accountMergeMapper.reparentFlowAiTasks(temp.getId(), target.getId());
        accountMergeMapper.reparentStudioProjects(temp.getId(), target.getId());
        // 2. 积分余额并入目标账号（含账户行删除）
        billingPort.transferBalance(temp.getId(), target.getId(),
                "merge:" + temp.getId() + ":" + target.getId(), "账号合并转入");
        // 3. 撤销临时账号的登录态
        revokeUserTokens(temp.getId());
        // 4. 解绑临时号 openid → 挂到目标账号 → 删除临时号
        userMapper.clearOpenid(temp.getId());
        attachOpenid(target, openid);
        userMapper.deleteById(temp.getId());
        log.info("[Auth] 微信账号合并: temp={} -> target={} openid={}", temp.getId(), target.getId(), openid);
    }

    private void revokeUserTokens(Long userId) {
        String userIdStr = String.valueOf(userId);
        String setKey = Constants.REDIS_USER_TOKENS_PREFIX + userIdStr;
        try {
            Set<String> tokens = new HashSet<>(redisTemplate.opsForSet().members(setKey));
            for (String token : tokens) {
                if (token != null) {
                    redisTemplate.delete(Constants.REDIS_TOKEN_PREFIX + token);
                }
            }
        } catch (RuntimeException e) {
            log.warn("[Auth] 撤销临时账号 {} 的 token 失败: {}", userId, e.getMessage());
            return;
        }
        redisTemplate.delete(setKey);
    }

    private void attachOpenid(User user, String openid) {
        User update = new User();
        update.setId(user.getId());
        update.setOpenid(openid);
        userMapper.updateById(update);
        user.setOpenid(openid);
    }

    private void attachEmail(User user, String email) {
        User update = new User();
        update.setId(user.getId());
        update.setEmail(email);
        userMapper.updateById(update);
        user.setEmail(email);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 刷新 Access Token
     * 验证 refreshToken JWT 有效性，生成新的 token 对
     */
    public TokenResponse refresh(String refreshToken) {
        if (!jwtUtil.isValid(refreshToken) || !jwtUtil.isRefreshToken(refreshToken)) {
            throw new IllegalArgumentException("无效的 Token");
        }

        String userId = jwtUtil.getSubject(refreshToken);
        User user = userMapper.selectById(userId);
        if (user == null || user.getStatus() == 0) {
            throw new IllegalArgumentException("用户状态异常");
        }

        return generateTokenResponse(user);
    }

    /** Issues an independent browser session after a verified QR handoff. */
    public TokenResponse issueTokenForUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || user.getStatus() == 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在或已停用");
        }
        return generateTokenResponse(user);
    }

    /**
     * 登出当前设备
     * 从 Redis 移除该 accessToken，不影响其它设备的 token
     *
     * @param accessToken 当前设备的 access token（不含 Bearer 前缀）
     */
    public void logout(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) return;

        try {
            if (jwtUtil.isValid(accessToken)) {
                String userId = jwtUtil.getSubject(accessToken);
                // 从用户活跃 token 集合中移除
                redisTemplate.opsForSet().remove(Constants.REDIS_USER_TOKENS_PREFIX + userId, accessToken);
            }
        } catch (Exception e) {
            // JWT 解析失败也要尝试删除 Redis key；记录原因便于排查
            log.warn("[Auth] 登出时移除用户活跃 token 失败: {}", e.getMessage());
        }

        // 删除 token 在 Redis 中的记录
        redisTemplate.delete(Constants.REDIS_TOKEN_PREFIX + accessToken);
        log.info("[Auth] 用户登出，当前设备 Token 已失效");
    }

    /**
     * 获取当前用户信息
     */
    public TokenResponse.UserInfo getCurrentUser(String bearerToken) {
        String token = extractToken(bearerToken);
        if (!jwtUtil.isValid(token)) {
            throw new IllegalArgumentException("Token 无效或已过期");
        }
        String userId = jwtUtil.getSubject(token);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        return buildUserInfo(user);
    }

    /**
     * 按已鉴权用户 ID 读取公开账户信息，供站内聚合读模型使用。
     *
     * <p>调用方必须先通过统一鉴权链路获得 userId；用户已被删除时按登录失效处理，
     * 避免其他业务模块直接依赖 identity 的 mapper。</p>
     */
    public TokenResponse.UserInfo getUserInfo(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        return buildUserInfo(user);
    }

    private TokenResponse generateTokenResponse(User user) {
        String userIdStr = String.valueOf(user.getId());
        Map<String, Object> claims = Map.of("role", user.getRole(), "type", "access");
        String accessToken = jwtUtil.generateAccessToken(userIdStr, claims);
        String refreshToken = jwtUtil.generateRefreshToken(userIdStr);

        // 多设备支持：每个 token 独立存储，互不覆盖
        redisTemplate.opsForValue().set(
                Constants.REDIS_TOKEN_PREFIX + accessToken,
                userIdStr,
                jwtUtil.getAccessTokenExpire(),
                TimeUnit.MILLISECONDS
        );
        // 记录该用户所有活跃 token，方便强制全部下线
        redisTemplate.opsForSet().add(Constants.REDIS_USER_TOKENS_PREFIX + userIdStr, accessToken);

        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType(Constants.TOKEN_PREFIX.trim())
                .expiresIn(jwtUtil.getAccessTokenExpire() / 1000)
                .needsEmailBinding(user.getEmail() == null || user.getEmail().isBlank())
                .userInfo(buildUserInfo(user))
                .build();
    }

    private TokenResponse.UserInfo buildUserInfo(User user) {
        return TokenResponse.UserInfo.builder()
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .role(user.getRole())
                .tier(user.getTier())
                .tierExpiresAt(user.getTierExpiresAt())
                .email(user.getEmail())
                .createdAt(user.getCreatedAt())
                .build();
    }

    /**
     * 更新账户信息（昵称 / 头像）。
     * 与 {@link #getCurrentUser} 同样在控制器内自行鉴权（/api/auth 路径在拦截器中整体排除），
     * token 无效或过期抛 {@link ResponseStatusException}（401），参数不合法抛
     * IllegalArgumentException（400）。
     */
    public TokenResponse.UserInfo updateProfile(String bearerToken, String username, String nickname, String avatar) {
        String token;
        try {
            token = extractToken(bearerToken);
        } catch (IllegalArgumentException e) {
            // 与 /me 保持一致：Authorization 头缺失或格式错误按未登录处理。
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        if (!jwtUtil.isValid(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        if (nickname == null || nickname.isBlank()) {
            throw new IllegalArgumentException("昵称不能为空");
        }
        String trimmedNickname = nickname.trim();
        if (trimmedNickname.length() > 30) {
            throw new IllegalArgumentException("昵称长度不能超过 30 个字符");
        }
        String normalizedAvatar = null;
        if (avatar != null && !avatar.isBlank()) {
            String trimmedAvatar = avatar.trim();
            if (!trimmedAvatar.startsWith("http://") && !trimmedAvatar.startsWith("https://")) {
                throw new IllegalArgumentException("头像地址必须以 http(s):// 开头");
            }
            if (trimmedAvatar.length() > 255) {
                throw new IllegalArgumentException("头像地址过长");
            }
            normalizedAvatar = trimmedAvatar;
        }

        String userId = jwtUtil.getSubject(token);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        // 只更新可修改字段，username/email 等登录凭据保持只读。
        String normalizedUsername = username == null ? user.getUsername() : username.trim();
        if (normalizedUsername.length() < 3 || normalizedUsername.length() > 30) {
            throw new IllegalArgumentException("用户名长度需在 3-30 个字符之间");
        }
        if (!normalizedUsername.matches("^[A-Za-z0-9_@.+-]+$")) {
            throw new IllegalArgumentException("用户名只能包含字母、数字及 _@.+-");
        }
        User existing = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, normalizedUsername));
        if (existing != null && !existing.getId().equals(user.getId())) {
            throw new IllegalArgumentException("该用户名已被使用");
        }
        User update = new User();
        update.setId(Long.valueOf(userId));
        update.setUsername(normalizedUsername);
        update.setNickname(trimmedNickname);
        if (normalizedAvatar != null) {
            update.setAvatar(normalizedAvatar);
        }
        userMapper.updateById(update);

        // 重新查询以返回数据库中的最新值（含未修改的头像原值）。
        user.setUsername(normalizedUsername);
        user.setNickname(trimmedNickname);
        if (normalizedAvatar != null) {
            user.setAvatar(normalizedAvatar);
        }
        return buildUserInfo(user);
    }

    public void changePassword(String bearerToken, String currentPassword, String newPassword) {
        String token = extractToken(bearerToken);
        if (!jwtUtil.isValid(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        User user = userMapper.selectById(jwtUtil.getSubject(token));
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        if (!matchesPassword(user, currentPassword)) {
            throw new IllegalArgumentException("当前密码不正确");
        }
        validateNewPassword(newPassword);
        updatePassword(user, newPassword);
    }

    public void resetPassword(String email, String code, String newPassword) {
        String normalizedEmail = normalizeEmail(email);
        validateNewPassword(newPassword);
        verifyEmailCode(normalizedEmail, code);
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, normalizedEmail));
        if (user == null) {
            throw new IllegalArgumentException("该邮箱尚未绑定账户");
        }
        updatePassword(user, newPassword);
        revokeUserTokens(user.getId());
    }

    private void validateNewPassword(String password) {
        if (password == null || password.length() < 6 || password.length() > 72) {
            throw new IllegalArgumentException("新密码长度需在 6-72 个字符之间");
        }
    }

    private void updatePassword(User user, String newPassword) {
        User update = new User();
        update.setId(user.getId());
        update.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(update);
        user.setPassword(update.getPassword());
    }

    public String extractToken(String bearerToken) {
        if (bearerToken != null && bearerToken.startsWith(Constants.TOKEN_PREFIX)) {
            return bearerToken.substring(Constants.TOKEN_PREFIX.length());
        }
        throw new IllegalArgumentException("Authorization 头格式错误");
    }

    /**
     * 验证 Access Token 是否有效（JWT 合法 + Redis 中仍存在）
     */
    public boolean validateAccessToken(String token) {
        if (!jwtUtil.isValid(token) || jwtUtil.isRefreshToken(token)) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(Constants.REDIS_TOKEN_PREFIX + token));
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现落在 identity 域内：令牌怎么签、会话登记表的键名是什么、refresh / bind
     * 令牌怎么区分，都不出这个域。消费方（agent 的 WebSocket 握手）只看到
     * {@link AccessTokenLookupPort} 这一个方法。</p>
     *
     * <p>⚠️ 这里<b>没有</b> {@code @Transactional}，也不该有：本方法纯读，
     * 而 {@link #validateAccessToken(String)} 只是一次 JWT 校验加一次 Redis
     * {@code hasKey}，没有任何需要原子性的写。加注解只会白占一个连接。</p>
     *
     * <p>⚠️ 本方法名由 {@code getUserIdFromAccessToken} 改名而来。改名是因为
     * 跨域位置现在通过端口调用，域内没有必要再留一个别名 —— 唯一的同域调用点
     * （{@code QrLoginService#confirm}）已一并更新。</p>
     */
    @Override
    public Long resolveUserId(String accessToken) {
        if (!validateAccessToken(accessToken)) {
            return null;
        }
        try {
            return Long.valueOf(jwtUtil.getSubject(accessToken));
        } catch (RuntimeException exception) {
            log.debug("access token subject parse failed: {}", exception.getMessage());
            return null;
        }
    }

    /**
     * FlowCraft 注册（邮箱 + 密码 + 用户名）
     * 邮箱唯一，重复注册抛异常
     * 限流：同一邮箱每 60 秒最多 1 次，同一 IP 每 60 秒最多 5 次
     */
    public TokenResponse register(String email, String password, String name, String clientIp) {
        checkRateLimit("register", sanitize(email), 1, 60);
        checkRateLimit("register:ip", sanitize(clientIp), 5, 60);

        User existing = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email));
        if (existing != null) {
            throw new IllegalArgumentException("该邮箱已注册");
        }
        User user = new User();
        user.setUsername(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(name);
        user.setEmail(email);
        user.setAvatar("https://api.dicebear.com/7.x/avataaars/svg?seed=" + email);
        user.setRole("USER");
        user.setStatus(1);
        userMapper.insert(user);
        billingPort.grantRegister(user.getId(), "user:" + user.getId());
        return generateTokenResponse(user);
    }
}

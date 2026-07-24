package com.xander.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xander.lab.common.Constants;
import com.xander.lab.dto.auth.LoginRequest;
import com.xander.lab.dto.auth.TokenResponse;
import com.xander.lab.entity.User;
import com.xander.lab.mapper.UserMapper;
import com.xander.lab.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 认证服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final SecureRandom CODE_RANDOM = new SecureRandom();

    private final JwtUtil jwtUtil;
    private final MailService mailService;
    private final UserMapper userMapper;
    private final StringRedisTemplate redisTemplate;

    /**
     * 发送登录验证码
     */
    public void sendCode(String email) {
        // 生成 6 位随机验证码
        String code = String.valueOf(100000 + CODE_RANDOM.nextInt(900000));
        
        // 存入 Redis (有效期 5 分钟)
        String key = Constants.REDIS_CODE_PREFIX + email;
        redisTemplate.opsForValue().set(key, code, Constants.CODE_EXPIRE_MINUTES, TimeUnit.MINUTES);
        
        // 发送邮件
        mailService.sendVerificationCode(email, code);
        
        log.info("[Auth] 验证码已发送至 {}", email);
    }

    /**
     * 登录
     */
    public TokenResponse login(LoginRequest request) {
        String account = request.getAccount();
        User user;

        if ("code".equalsIgnoreCase(request.getType())) {
            // 验证码登录逻辑
            String key = Constants.REDIS_CODE_PREFIX + account;
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
                    
            if (user == null || !user.getPassword().equals(request.getPassword())) {
                throw new IllegalArgumentException("用户名或密码错误");
            }
        }

        if (user.getStatus() == 0) {
            throw new IllegalArgumentException("账号已被禁用，请联系管理员");
        }

        return generateTokenResponse(user);
    }

    /**
     * 自动注册用户
     */
    private User registerUser(String username, String password, String email, String role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(password != null ? password : "123456");
        user.setNickname(username.contains("@") ? username.split("@")[0] : username);
        user.setEmail(email);
        user.setAvatar("https://api.dicebear.com/7.x/avataaars/svg?seed=" + username);
        user.setRole(role);
        user.setStatus(1);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);
        return user;
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
        } catch (Exception ignored) {
            // JWT 解析失败也要尝试删除 Redis key
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
                .userInfo(buildUserInfo(user))
                .build();
    }

    private TokenResponse.UserInfo buildUserInfo(User user) {
        return TokenResponse.UserInfo.builder()
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .role(user.getRole())
                .build();
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
     * FlowCraft 注册（邮箱 + 密码 + 用户名）
     * 邮箱唯一，重复注册抛异常
     */
    public TokenResponse register(String email, String password, String name) {
        User existing = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email));
        if (existing != null) {
            throw new IllegalArgumentException("该邮箱已注册");
        }
        User user = new User();
        user.setUsername(email);
        user.setPassword(password);
        user.setNickname(name);
        user.setEmail(email);
        user.setAvatar("https://api.dicebear.com/7.x/avataaars/svg?seed=" + email);
        user.setRole("USER");
        user.setStatus(1);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);
        return generateTokenResponse(user);
    }
}

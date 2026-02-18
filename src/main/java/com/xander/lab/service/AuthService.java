package com.xander.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xander.lab.dto.auth.LoginRequest;
import com.xander.lab.dto.auth.TokenResponse;
import com.xander.lab.entity.User;
import com.xander.lab.mapper.UserMapper;
import com.xander.lab.util.JwtUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证服务
 * 切换为真实的数据库操作（MyBatis-Plus）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final JwtUtil jwtUtil;
    private final MailService mailService;
    private final UserMapper userMapper;

    /**
     * 验证码缓存 (邮箱 -> {code, expireTime})
     * 生产环境请替换为 Redis
     */
    private static final Map<String, CodeRecord> CODE_CACHE = new ConcurrentHashMap<>();

    /**
     * 已失效的 Refresh Token 黑名单
     * 生产环境建议使用 Redis 存储，设置过期时间为 7 天
     */
    private static final Set<String> TOKEN_BLACKLIST = ConcurrentHashMap.newKeySet();

    @Data
    @AllArgsConstructor
    private static class CodeRecord {
        private String code;
        private long expireTime;
    }

    /**
     * 发送登录验证码
     */
    public void sendCode(String email) {
        // 生成 6 位随机验证码
        String code = String.valueOf((int) ((Math.random() * 9 + 1) * 100000));
        
        // 存入缓存 (有效期 5 分钟)
        CODE_CACHE.put(email, new CodeRecord(code, System.currentTimeMillis() + 5 * 60 * 1000));
        
        // 发送邮件
        mailService.sendVerificationCode(email, code);
        
        log.info("[Auth] 验证码已发送至 {}: {}", email, code);
    }

    /**
     * 登录
     */
    public TokenResponse login(LoginRequest request) {
        String account = request.getAccount();
        User user;

        if ("code".equalsIgnoreCase(request.getType())) {
            // 验证码登录逻辑
            CodeRecord record = CODE_CACHE.get(account);
            if (record == null || !record.getCode().equals(request.getCode())) {
                throw new IllegalArgumentException("验证码错误");
            }
            if (System.currentTimeMillis() > record.getExpireTime()) {
                CODE_CACHE.remove(account);
                throw new IllegalArgumentException("验证码已过期");
            }
            
            // 根据邮箱查询用户，若不存在则自动注册
            user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, account));
            if (user == null) {
                user = registerUser(account, null, account, "USER");
            }
            // 登录成功，移除验证码
            CODE_CACHE.remove(account);
        } else {
            // 密码登录逻辑：支持用户名或邮箱登录
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
     * 自动注册用户（用于验证码首次登录）
     */
    private User registerUser(String username, String password, String email, String role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(password != null ? password : "123456"); // 随机密码
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
     */
    public TokenResponse refresh(String refreshToken) {
        if (TOKEN_BLACKLIST.contains(refreshToken)) {
            throw new IllegalArgumentException("Token 已失效，请重新登录");
        }
        if (!jwtUtil.isValid(refreshToken) || !jwtUtil.isRefreshToken(refreshToken)) {
            throw new IllegalArgumentException("无效的 Refresh Token");
        }

        String username = jwtUtil.getSubject(refreshToken);
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null || user.getStatus() == 0) {
            throw new IllegalArgumentException("用户状态异常");
        }

        // 旧 Token 加入黑名单
        TOKEN_BLACKLIST.add(refreshToken);

        return generateTokenResponse(user);
    }

    /**
     * 登出
     */
    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            TOKEN_BLACKLIST.add(refreshToken);
        }
        log.info("[Auth] 用户登出，Token 已失效");
    }

    /**
     * 获取当前用户信息
     */
    public TokenResponse.UserInfo getCurrentUser(String bearerToken) {
        String token = extractToken(bearerToken);
        if (!jwtUtil.isValid(token)) {
            throw new IllegalArgumentException("Token 无效或已过期");
        }
        String username = jwtUtil.getSubject(token);
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        return buildUserInfo(user);
    }

    private TokenResponse generateTokenResponse(User user) {
        Map<String, Object> claims = Map.of("role", user.getRole(), "type", "access");
        String accessToken = jwtUtil.generateAccessToken(user.getUsername(), claims);
        String refreshToken = jwtUtil.generateRefreshToken(user.getUsername());

        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
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
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        throw new IllegalArgumentException("Authorization 头格式错误");
    }

    public boolean validateAccessToken(String token) {
        return jwtUtil.isValid(token) && !jwtUtil.isRefreshToken(token);
    }
}

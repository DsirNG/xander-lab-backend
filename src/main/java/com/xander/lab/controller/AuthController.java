package com.xander.lab.controller;

import com.xander.lab.common.Result;
import com.xander.lab.dto.auth.LoginRequest;
import com.xander.lab.dto.auth.RefreshTokenRequest;
import com.xander.lab.dto.auth.TokenResponse;
import com.xander.lab.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器
 * 完整对应前端 axios 封装中的认证相关接口：
 *
 * <pre>
 *   POST /api/auth/login     登录，返回 accessToken + refreshToken
 *   POST /api/auth/refresh   无感刷新 Access Token（前端 401 自动触发）
 *   POST /api/auth/logout    登出，使 Refresh Token 失效
 *   GET  /api/auth/me        获取当前登录用户信息
 * </pre>
 *
 * 前端 axios 封装对应逻辑（src/api/http.js）：
 * <ul>
 *   <li>登录后调用 tokenStorage.setToken() / setRefreshToken() 存储</li>
 *   <li>每次请求自动携带 Authorization: Bearer {accessToken}</li>
 *   <li>收到 401 时自动 POST /api/auth/refresh 刷新，刷新成功后重试原请求</li>
 *   <li>刷新失败时触发 auth:logout 事件，前端跳转登录页</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 发送登录验证码
     * GET /api/auth/code?email=xxx@qq.com
     */
    @GetMapping("/code")
    public Result<Void> sendCode(@RequestParam String email) {
        authService.sendCode(email);
        return Result.success();
    }

    /**
     * 登录
     * 前端调用：post('/api/auth/login', { type: 'password', account: 'admin', password: '...' })
     * 或：post('/api/auth/login', { type: 'code', account: 'xxx@qq.com', code: '123456' })
     *
     * @param request 登录请求体
     * @return accessToken + refreshToken + 用户信息
     */
    @PostMapping("/login")
    public Result<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        try {
            TokenResponse response = authService.login(request);
            return Result.success(response);
        } catch (IllegalArgumentException e) {
            // 对应前端 BIZ_ERROR_MAP[1001] = '用户名或密码错误'
            return Result.error(1001, e.getMessage());
        }
    }

    /**
     * 刷新 Access Token（无感刷新）
     * 前端 axios 封装在收到 401 时自动调用此接口
     *
     * 请求体：{ "refreshToken": "eyJ..." }
     * 响应体：{ "accessToken": "...", "refreshToken": "..." }
     *
     * @param request 包含 refreshToken 的请求体
     * @return 新的 accessToken + refreshToken
     */
    @PostMapping("/refresh")
    public Result<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        try {
            TokenResponse response = authService.refresh(request.getRefreshToken());
            return Result.success(response);
        } catch (IllegalArgumentException e) {
            return Result.unauthorized(e.getMessage());
        }
    }

    /**
     * 登出
     * 前端监听 auth:logout 事件后调用，或用户主动点击退出
     *
     * 请求体：{ "refreshToken": "eyJ..." }
     *
     * @param request 包含 refreshToken 的请求体（可选，用于使 Token 失效）
     * @return 成功响应
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestBody(required = false) RefreshTokenRequest request) {
        String refreshToken = request != null ? request.getRefreshToken() : null;
        authService.logout(refreshToken);
        return Result.success();
    }

    /**
     * 获取当前登录用户信息
     * 前端调用：get('/api/auth/me')，自动携带 Authorization 头
     *
     * @param authorization 请求头中的 Authorization: Bearer {token}
     * @return 当前用户信息
     */
    @GetMapping("/me")
    public Result<TokenResponse.UserInfo> getCurrentUser(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return Result.unauthorized("未提供认证信息");
        }
        try {
            TokenResponse.UserInfo userInfo = authService.getCurrentUser(authorization);
            return Result.success(userInfo);
        } catch (IllegalArgumentException e) {
            return Result.unauthorized(e.getMessage());
        }
    }

    /**
     * 验证 Token 是否有效（供前端静默检查）
     * GET /api/auth/validate
     *
     * @param authorization Authorization 头
     * @return 是否有效
     */
    @GetMapping("/validate")
    public Result<Boolean> validateToken(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return Result.success(false);
        }
        String token = authorization.substring(7);
        return Result.success(authService.validateAccessToken(token));
    }
}

package com.xander.lab.identity.api;

import com.xander.lab.common.Result;
import com.xander.lab.identity.api.LoginRequest;
import com.xander.lab.identity.api.RefreshTokenRequest;
import com.xander.lab.identity.api.RegisterRequest;
import com.xander.lab.identity.api.TokenResponse;
import com.xander.lab.identity.application.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器
 * 完整对应前端 axios 封装中的认证相关接口：
 *
 * <pre>
 *   POST /api/auth/login     登录，返回 accessToken + refreshToken
 *   POST /api/auth/refresh   无感刷新 Access Token（前端 401 自动触发）
 *   POST /api/auth/logout    登出当前设备，移除 Redis 中的 accessToken
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
    public Result<Void> sendCode(@RequestParam String email, HttpServletRequest request) {
        authService.sendCode(email, clientIp(request));
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
    public Result<TokenResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        try {
            TokenResponse response = authService.login(request, clientIp(httpRequest));
            return Result.success(response);
        } catch (IllegalArgumentException e) {
            // 对应前端 BIZ_ERROR_MAP[1001] = '用户名或密码错误'
            return Result.error(1001, e.getMessage());
        }
    }

    /**
     * 微信小程序登录
     * 前端调用：post('/api/auth/wechat-login', { code: '临时登录凭证' })
     * 与 /login 一致：业务失败统一映射 code=1001；微信通道未配置或服务不可用视为业务失败。
     *
     * @param request 微信登录请求体
     * @return accessToken + refreshToken + 用户信息
     */
    @PostMapping("/wechat-login")
    public Result<TokenResponse> wechatLogin(@Valid @RequestBody WechatLoginRequest request, HttpServletRequest httpRequest) {
        try {
            TokenResponse response = authService.wechatLogin(request.getCode(), clientIp(httpRequest));
            return Result.success(response);
        } catch (IllegalArgumentException e) {
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
    public ResponseEntity<Result<TokenResponse>> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        try {
            TokenResponse response = authService.refresh(request.getRefreshToken());
            return ResponseEntity.ok(Result.success(response));
        } catch (IllegalArgumentException e) {
            return unauthorizedResponse();
        }
    }

    /**
     * 登出当前设备
     * 前端携带 Authorization 头中的 accessToken 调用，移除 Redis 中该 token 的记录
     * 不影响其它设备的登录状态
     *
     * @param authorization Authorization: Bearer {accessToken}
     * @return 成功响应
     */
    @PostMapping("/logout")
    public Result<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String accessToken = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            accessToken = authorization.substring(7);
        }
        authService.logout(accessToken);
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
    public ResponseEntity<Result<TokenResponse.UserInfo>> getCurrentUser(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return unauthorizedResponse();
        }
        try {
            TokenResponse.UserInfo userInfo = authService.getCurrentUser(authorization);
            return ResponseEntity.ok(Result.success(userInfo));
        } catch (IllegalArgumentException e) {
            return unauthorizedResponse();
        }
    }

    /**
     * 更新账户信息（昵称 / 头像）
     * PUT /api/auth/profile，携带 Authorization 头；返回更新后的用户信息。
     */
    @PutMapping("/profile")
    public ResponseEntity<Result<TokenResponse.UserInfo>> updateProfile(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ProfileUpdateRequest request) {
        if (authorization == null || authorization.isBlank()) {
            return unauthorizedResponse();
        }
        try {
            TokenResponse.UserInfo userInfo = authService.updateProfile(
                    authorization, request.getNickname(), request.getAvatar());
            return ResponseEntity.ok(Result.success(userInfo));
        } catch (IllegalArgumentException e) {
            // 业务校验失败（昵称/头像不合法）映射为 400；token 无效由服务抛 401 交给全局异常处理。
            return ResponseEntity.badRequest()
                    .body(Result.error(400, e.getMessage()));
        }
    }

    /**
     * FlowCraft 注册（邮箱 + 密码 + 用户名）
     * POST /api/auth/register
     */
    @PostMapping("/register")
    public Result<TokenResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        try {
            TokenResponse response = authService.register(request.getEmail(), request.getPassword(), request.getName(), clientIp(httpRequest));
            return Result.success(response);
        } catch (IllegalArgumentException e) {
            return Result.badRequest(e.getMessage());
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

private static <T> ResponseEntity<Result<T>> unauthorizedResponse() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.unauthorized("未登录或登录已过期"));
    }

    /**
     * 提取客户端 IP，优先信任代理注入的 header，回退到 socket 对端
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }
        return request.getRemoteAddr();
    }
}

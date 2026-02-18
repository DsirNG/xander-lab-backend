package com.xander.lab.config;

import com.xander.lab.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器
 * 对应前端 axios 封装中自动携带的 Authorization 头
 * 
 * 逻辑：
 * 1. 检查请求头中的 Authorization
 * 2. 如果不存在或无效，且该接口需要认证，则返回 401
 * 3. 401 会触发前端 axios 封装中的无感刷新 (POST /api/auth/refresh)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthService authService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 放行 OPTIONS 请求 (CORS 预检)
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 获取 Token
        String authHeader = request.getHeader("Authorization");
        
        // 如果是认证相关的接口，直接通过 (登录、刷新、登出)
        String path = request.getRequestURI();
        if (path.contains("/api/auth/login") || path.contains("/api/auth/refresh")) {
            return true;
        }

        // 这里仅作演示：如果带了 Token 就校验，没带且不是受限接口就放行
        // 在实际企业项目中，通常除了白名单接口外，其他都需要校验
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (authService.validateAccessToken(token)) {
                return true;
            }
        }

        // 如果校验失败，返回 401
        // 注意：这里为了不破坏你现有的 Blog 浏览功能，默认即使不登录也能看博客
        // 但如果是一个需要写权限的接口 (如 POST /api/blog/posts)，你可以在这里拦截并返回 401
        log.debug("[AuthInterceptor] 请求 path: {}, 未提供有效 Token", path);
        
        return true; 
    }
}

package com.xander.lab.config;

import com.xander.lab.common.Constants;
import com.xander.lab.common.UserContext;
import com.xander.lab.service.AuthService;
import com.xander.lab.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * 认证拦截器
 * 验证请求头中的 JWT Token，并将用户ID存入 ThreadLocal
 * 同时提取客户端IP地址存入 UserContext
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final AuthService authService;

    /** 需要登录的写操作路径 */
    private static final List<String> PROTECTED_WRITE_PATTERNS = List.of(
            "POST:/api/blog/posts",
            "POST:/api/blog-agent/tasks",
            "POST:/api/blog-agent/tasks/*",
            // Agent tasks contain private user material, so their reads are protected too.
            "GET:/api/blog-agent/tasks/*",
            "POST:/api/component/items",
            "POST:/api/studio/upload",
            "POST:/api/studio/save"
    );

    /** 公开的读操作路径（支持通配符） */
    private static final List<String> GET_PUBLIC_PATTERNS = List.of(
            "GET:/api/blog/posts",
            "GET:/api/blog/posts/*",
            "GET:/api/blog/categories",
            "GET:/api/blog/tags",
            "GET:/api/blog/tags/popular",
            "GET:/api/blog/posts/recent",
            "GET:/api/component/items",
            "GET:/api/component/items/*",
            "GET:/api/component/categories",
            "GET:/api/component/tags",
            "GET:/api/studio/preview/*",
            "GET:/api/studio/project/*"
    );

    /** 完全排除的路径 */
    private static final List<String> EXCLUDED_PATTERNS = List.of(
            "/api/auth/",
            "/api/studio/preview/",
            "/swagger-ui",
            "/v3/api-docs"
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        String method = request.getMethod();

        // 提取并存储客户端IP（所有请求都执行，供后续业务使用）
        UserContext.setClientIp(extractClientIp(request));

        // 完全排除的路径直接放行
        for (String pattern : EXCLUDED_PATTERNS) {
            if (path.contains(pattern)) {
                return true;
            }
        }

        // 公开的GET读操作直接放行（但仍尝试解析token以便记录用户信息）
        for (String pattern : GET_PUBLIC_PATTERNS) {
            String[] parts = pattern.split(":", 2);
            if (parts[0].equals(method) && matchPath(parts[1], path)) {
                // 尝试解析token，设置用户上下文（不阻断请求）
                trySetUserContext(request);
                return true;
            }
        }

        // 需要登录的写操作
        for (String pattern : PROTECTED_WRITE_PATTERNS) {
            String[] parts = pattern.split(":", 2);
            if (parts[0].equals(method) && matchPath(parts[1], path)) {
                return requireAuth(request, response);
            }
        }

        // 其他未匹配的路径默认放行
        trySetUserContext(request);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    /**
     * 尝试解析请求中的token并设置用户上下文
     * 不抛出异常，解析失败则上下文为空
     */
    private void trySetUserContext(HttpServletRequest request) {
        String authHeader = request.getHeader(Constants.AUTHORIZATION_HEADER);
        if (authHeader != null && authHeader.startsWith(Constants.TOKEN_PREFIX)) {
            String token = authHeader.substring(Constants.TOKEN_PREFIX.length());
            try {
                if (authService.validateAccessToken(token)) {
                    String userId = jwtUtil.getSubject(token);
                    UserContext.setUserId(Long.parseLong(userId));
                }
            } catch (Exception e) {
                // 解析失败不阻断请求
                log.debug("[Auth] Token 解析失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 强制要求认证
     */
    private boolean requireAuth(HttpServletRequest request, HttpServletResponse response) {
        String authHeader = request.getHeader(Constants.AUTHORIZATION_HEADER);
        if (authHeader == null || !authHeader.startsWith(Constants.TOKEN_PREFIX)) {
            unauthorized(response);
            return false;
        }

        String token = authHeader.substring(Constants.TOKEN_PREFIX.length());
        if (!authService.validateAccessToken(token)) {
            unauthorized(response);
            return false;
        }

        String userId = jwtUtil.getSubject(token);
        UserContext.setUserId(Long.parseLong(userId));
        return true;
    }

    /**
     * 简单路径匹配（支持单层通配符 *）
     */
    private boolean matchPath(String pattern, String path) {
        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return path.startsWith(prefix + "/") || path.equals(prefix);
        }
        return pattern.equals(path);
    }

    /**
     * 从请求头中提取客户端真实IP
     * 优先读取代理透传头（X-Forwarded-For / X-Real-IP），回退到 remoteAddr
     *
     * @param request HTTP请求
     * @return 客户端IP地址
     */
    private String extractClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            // X-Forwarded-For 可能包含多个IP，取第一个（真实客户端IP）
            return ip.split(",")[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }
        return request.getRemoteAddr();
    }

    private void unauthorized(HttpServletResponse response) {
        try {
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write("{\"code\":401,\"message\":\"未登录或登录已过期\",\"data\":null}");
        } catch (Exception e) {
            log.error("[Auth] 响应写入失败", e);
        }
    }
}

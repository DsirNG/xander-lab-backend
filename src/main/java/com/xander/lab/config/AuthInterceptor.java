package com.xander.lab.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.common.Constants;
import com.xander.lab.common.Result;
import com.xander.lab.common.UserContext;
import com.xander.lab.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * 认证拦截器
 *
 * <p>鉴权逻辑：
 * <ol>
 *   <li>解析 Bearer Token → JWT 签名 + 过期校验</li>
 *   <li>查 Redis login:token:{token} 是否存在（确认 token 未被登出清除）</li>
 *   <li>校验通过 → 存入 UserContext(ThreadLocal)</li>
 * </ol>
 *
 * <p>路径策略：
 * <ul>
 *   <li>需要鉴权的路径（上传、创建等写操作）：无有效 token → 返回 401</li>
 *   <li>其它路径：不强制要求 token，有则解析 UserContext，无则放行</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /** 需要登录才能访问的路径模式 */
    private static final List<String> AUTH_REQUIRED_PATTERNS = List.of(
            "/api/upload/**",
            "/api/components/share",
            "/api/export/**"
    );

    /** GET 请求无需鉴权的路径（同路径的 POST/PUT/DELETE 需要鉴权） */
    private static final List<String> GET_PUBLIC_PATTERNS = List.of(
            "/api/blog/posts"
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI();
        String method = request.getMethod();
        String token = extractToken(request);
        String userId = validateTokenAndGetUserId(token);

        if (userId != null) {
            UserContext.setUserId(Long.valueOf(userId));
            log.info("[Auth] {} {} → userId={}", method, path, userId);
            return true;
        }

        // token 无效或缺失，检查当前路径是否强制要求鉴权
        if (isAuthRequired(path, method)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");

            String message = "未登录或登录已过期";
            log.warn("[Auth] 401 {} {} → {}", method, path, message);

            response.getWriter().write(objectMapper.writeValueAsString(
                    Result.unauthorized(message)
            ));
            return false;
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    /**
     * 验证 token 有效性并返回 userId
     *
     * @param token JWT token 字符串
     * @return userId 字符串，token 无效时返回 null
     */
    private String validateTokenAndGetUserId(String token) {
        if (token == null) return null;

        try {
            if (!jwtUtil.isValid(token) || jwtUtil.isRefreshToken(token)) {
                return null;
            }

            String userId = jwtUtil.getSubject(token);
            // 查 Redis 确认该 token 仍处于活跃状态（未被登出清除）
            String redisValue = redisTemplate.opsForValue().get(Constants.REDIS_TOKEN_PREFIX + token);
            if (redisValue != null) {
                return userId;
            }
        } catch (Exception e) {
            log.error("[AuthInterceptor] Token 验证异常: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 从 Authorization 请求头中提取 token 字符串
     *
     * @param request HTTP 请求
     * @return token 字符串，不存在或格式错误时返回 null
     */
    private String extractToken(HttpServletRequest request) {
        String authHeader = request.getHeader(Constants.AUTHORIZATION_HEADER);
        if (authHeader != null && authHeader.startsWith(Constants.TOKEN_PREFIX)) {
            return authHeader.substring(Constants.TOKEN_PREFIX.length());
        }
        return null;
    }

    /**
     * 判断当前路径 + 方法组合是否需要鉴权
     *
     * @param path   请求路径
     * @param method HTTP 方法
     * @return true 表示必须携带有效 token
     */
    private boolean isAuthRequired(String path, String method) {
        // 匹配写操作路径（上传、分享、导出等）
        for (String pattern : AUTH_REQUIRED_PATTERNS) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }

        // GET 请求公开、其它方法需鉴权的路径（如 POST /api/blog/posts）
        if (!"GET".equalsIgnoreCase(method)) {
            for (String pattern : GET_PUBLIC_PATTERNS) {
                if (pathMatcher.match(pattern, path)) {
                    return true;
                }
            }
        }

        return false;
    }
}

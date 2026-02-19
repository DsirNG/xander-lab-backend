package com.xander.lab.config;

import com.xander.lab.common.Constants;
import com.xander.lab.common.UserContext;
import com.xander.lab.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器
 * 流程：解析 Token -> Redis 验证 -> 存入 UserContext -> 请求结束清理
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 放行 OPTIONS 请求
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 获取 Token
        String authHeader = request.getHeader(Constants.AUTHORIZATION_HEADER);
        
        if (authHeader != null && authHeader.startsWith(Constants.TOKEN_PREFIX)) {
            String token = authHeader.substring(Constants.TOKEN_PREFIX.length());
            
            try {
                // 1. JWT 解析与基础验证
                if (jwtUtil.isValid(token) && !jwtUtil.isRefreshToken(token)) {
                    String userId = jwtUtil.getSubject(token);
                    
                    // 2. Redis 活跃状态验证 (可选：验证 Token 是否与 Redis 中一致，或是否在黑名单)
                    // 如果存在黑名单机制，此处应校验 redisTemplate.hasKey(Constants.REDIS_BLACKLIST_PREFIX + token)
                    String redisToken = redisTemplate.opsForValue().get(Constants.REDIS_TOKEN_PREFIX + userId);
                    
                    if (redisToken != null) {
                        // 3. 验证通过，存入 UserContext (ThreadLocal)
                        UserContext.setUserId(Long.valueOf(userId));
                        return true;
                    }
                }
            } catch (Exception e) {
                log.error("[AuthInterceptor] Token 验证异常: {}", e.getMessage());
            }
        }

        // 注意：目前为了兼容博客浏览，不强制返回 401
        // 后续如需增加权限控制，可根据注解或路径在此处拦截
        return true; 
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 请求结束后必须清理 ThreadLocal，防止内存泄漏和数据污染
        UserContext.clear();
    }
}

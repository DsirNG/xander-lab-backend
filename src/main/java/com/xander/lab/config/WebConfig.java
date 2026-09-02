package com.xander.lab.config;

import com.xander.lab.identity.security.AuthInterceptor;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 * 配置拦截器、CORS、资源映射等
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    /**
     * 允许跨域的前端来源，由环境变量 CORS_ALLOWED_ORIGINS 注入（逗号分隔）
     * 禁止通配 + 携带凭据的组合；未显式配置时仅放行本地开发来源
     * 注意：nginx 反代 /api 时会保留浏览器 Origin 头，白名单必须包含实际页面 Origin
     */
    @Value("${app.cors.allowed-origins:*}")
    private String[] allowedOrigins;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册认证拦截器
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/**",       // 登录/注册/刷新/登出/验证，自行处理鉴权
                        "/api/download/**"    // 文件下载公开
                );
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 配置全局 CORS，确保前端 axios 跨域请求正常
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}

package com.xander.lab.webmvc;

import java.util.Arrays;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
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
     * 未显式配置时使用 * 放行所有来源；配置具体来源时按白名单放行
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
        // 配置全局 CORS，确保前端 axios 跨域请求正常。
        // Spring 不允许 allowedOrigins("*") 与 allowCredentials(true) 组合，
        // 因此通配模式下关闭凭据；具体白名单下保留凭据支持。
        String[] configuredOrigins = Arrays.stream(allowedOrigins)
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
        boolean allowAllOrigins = Arrays.stream(configuredOrigins)
                .anyMatch("*"::equals);

        CorsRegistration registration = registry.addMapping("/**")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);

        if (allowAllOrigins) {
            registration.allowedOriginPatterns("*")
                    .allowCredentials(false);
        } else {
            registration.allowedOrigins(configuredOrigins)
                    .allowCredentials(true);
        }
    }
}

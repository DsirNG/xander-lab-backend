package com.xander.lab.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Gateway route for the Wave 2 platform-integration compatibility boundary. */
@Configuration
public class PlatformIntegrationRouteConfiguration {

    @Bean
    RouteLocator platformIntegrationRoutes(
            RouteLocatorBuilder builder,
            @Value("${gateway.platform-integration-uri:lb://dinqor-platform-integration}")
            String integrationUri) {
        return builder.routes()
                .route("platform-integration-cutover", route -> route
                        .path("/.well-known/**", "/api/blog/posts/*/sync/**", "/api/download/**",
                                "/api/export/**", "/api/mcp/**", "/api/publishing/csdn/**",
                                "/api/publishing/juejin/**", "/api/upload/**", "/mcp/**")
                        .uri(integrationUri))
                .build();
    }
}

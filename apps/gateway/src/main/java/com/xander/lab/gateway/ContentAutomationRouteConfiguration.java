package com.xander.lab.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Gateway route for the Wave 2 content-automation compatibility boundary. */
@Configuration
public class ContentAutomationRouteConfiguration {

    @Bean
    RouteLocator contentAutomationRoutes(
            RouteLocatorBuilder builder,
            @Value("${gateway.content-automation-uri:lb://dinqor-content-automation}")
            String contentAutomationUri) {
        return builder.routes()
                .route("content-automation-cutover", route -> route
                        .path("/api/blog-agent/**", "/api/blog-plans/**", "/api/email-reminders/**",
                                "/api/notifications/**")
                        .uri(contentAutomationUri))
                .build();
    }
}

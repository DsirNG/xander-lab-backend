package com.xander.lab.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Gateway routes for the Wave 2 content-automation deployment boundary. */
@Configuration
public class ContentAutomationRouteConfiguration {

    @Bean
    RouteLocator contentAutomationRoutes(
            RouteLocatorBuilder builder,
            @Value("${gateway.content-automation-uri:lb://dinqor-content-automation}")
            String contentAutomationUri) {
        return builder.routes()
                .route("content-publishing-private-post-reads", route -> route
                        .path("/api/blog/posts/mine", "/api/blog/posts/mine/{id}")
                        .and()
                        .method(org.springframework.http.HttpMethod.GET)
                        .uri(contentAutomationUri))
                .route("content-publishing-public-reads", route -> route
                        .path("/api/blog/posts", "/api/blog/posts/recent")
                        .and()
                        .method(org.springframework.http.HttpMethod.GET)
                        .uri(contentAutomationUri))
                .route("content-publishing-public-detail", route -> route
                        .path("/api/blog/posts/{id}")
                        .and()
                        .method(org.springframework.http.HttpMethod.GET)
                        .uri(contentAutomationUri))
                .route("content-publishing-public-taxonomy", route -> route
                        .path("/api/blog/categories", "/api/blog/tags", "/api/blog/tags/popular")
                        .and()
                        .method(org.springframework.http.HttpMethod.GET)
                        .uri(contentAutomationUri))
                .route("content-publishing-public-view", route -> route
                        .path("/api/blog/posts/{id}/view")
                        .and()
                        .method(org.springframework.http.HttpMethod.POST)
                        .uri(contentAutomationUri))
                .route("content-publishing-private-media-reads", route -> route
                        .path("/api/blog/media/images", "/api/blog/media/images/agent")
                        .and()
                        .method(org.springframework.http.HttpMethod.GET)
                        .uri(contentAutomationUri))
                .route("content-automation-cutover", route -> route
                        .path("/api/blog-agent/**", "/api/blog-plans/**", "/api/email-reminders/**",
                                "/api/notifications/**")
                        .uri(contentAutomationUri))
                .build();
    }
}

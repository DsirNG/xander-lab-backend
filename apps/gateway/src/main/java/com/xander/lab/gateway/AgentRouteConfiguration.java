package com.xander.lab.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Routes agent HTTP APIs and its WebSocket stream to the agent service. */
@Configuration
public class AgentRouteConfiguration {

    @Bean
    RouteLocator agentRoutes(
            RouteLocatorBuilder builder,
            @Value("${gateway.agent-uri:lb://dinqor-agent}") String agentUri,
            @Value("${gateway.agent-ws-uri:lb:ws://dinqor-agent}") String agentWebSocketUri) {
        return builder.routes()
                .route("agent-http", route -> route
                        .path("/api/agent/**")
                        .uri(agentUri))
                .route("agent-websocket", route -> route
                        .path("/ws/agent")
                        .uri(agentWebSocketUri))
                .build();
    }
}

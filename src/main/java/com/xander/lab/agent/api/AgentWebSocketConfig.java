package com.xander.lab.agent.api;

import com.xander.lab.agent.application.AgentConversationService;
import com.xander.lab.agent.application.AgentEventService;
import com.xander.lab.identity.application.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册智能体会话 WebSocket 订阅端点 /ws/agent。
 * 握手鉴权在拦截器内完成（token 校验 + 会话归属），业务事件流与 SSE 共用同一持久化事件源。
 */
@Configuration
@EnableWebSocket
public class AgentWebSocketConfig implements WebSocketConfigurer {

    private final AgentEventService eventService;
    private final AuthService authService;
    private final AgentConversationService conversationService;
    private final ObjectMapper objectMapper;
    private final TaskExecutor agentSseExecutor;
    private final TaskScheduler agentHeartbeatScheduler;

    public AgentWebSocketConfig(AgentEventService eventService,
                                AuthService authService,
                                AgentConversationService conversationService,
                                ObjectMapper objectMapper,
                                @Qualifier("agentSseExecutor") TaskExecutor agentSseExecutor,
                                @Qualifier("agentHeartbeatScheduler") TaskScheduler agentHeartbeatScheduler) {
        this.eventService = eventService;
        this.authService = authService;
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
        this.agentSseExecutor = agentSseExecutor;
        this.agentHeartbeatScheduler = agentHeartbeatScheduler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agentStreamWebSocketHandler(), "/ws/agent")
                .addInterceptors(new AgentStreamAuthHandshakeInterceptor(authService, conversationService))
                .setAllowedOriginPatterns("*");
    }

    @Bean
    public AgentStreamWebSocketHandler agentStreamWebSocketHandler() {
        return new AgentStreamWebSocketHandler(
                eventService, objectMapper, agentSseExecutor, agentHeartbeatScheduler);
    }
}
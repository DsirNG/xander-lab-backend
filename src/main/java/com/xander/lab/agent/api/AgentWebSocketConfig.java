package com.xander.lab.agent.api;

import com.xander.lab.agent.application.AgentConversationService;
import com.xander.lab.agent.application.AgentEventBus;
import com.xander.lab.agent.application.AgentEventService;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
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
 * 握手鉴权在拦截器内完成（token 校验 + 会话归属）；事件经进程内总线直推，
 * 数据库重放仅用于连接建立时补齐历史。
 */
@Configuration
@EnableWebSocket
public class AgentWebSocketConfig implements WebSocketConfigurer {

    private final AgentEventService eventService;
    private final AgentEventBus eventBus;
    private final AuthService authService;
    private final AgentConversationService conversationService;
    private final ObjectMapper objectMapper;
    private final TaskExecutor agentSseExecutor;
    private final TaskScheduler agentHeartbeatScheduler;
    private final AgentConversationMapper conversationMapper;

    public AgentWebSocketConfig(AgentEventService eventService,
                                AgentEventBus eventBus,
                                AuthService authService,
                                AgentConversationService conversationService,
                                ObjectMapper objectMapper,
                                @Qualifier("agentSseExecutor") TaskExecutor agentSseExecutor,
                                @Qualifier("agentHeartbeatScheduler") TaskScheduler agentHeartbeatScheduler,
                                AgentConversationMapper conversationMapper) {
        this.eventService = eventService;
        this.eventBus = eventBus;
        this.authService = authService;
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
        this.agentSseExecutor = agentSseExecutor;
        this.agentHeartbeatScheduler = agentHeartbeatScheduler;
        this.conversationMapper = conversationMapper;
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
                eventService, eventBus, objectMapper, agentSseExecutor, agentHeartbeatScheduler,
                conversationMapper);
    }
}
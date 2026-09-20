package com.xander.lab.agent.application;

import com.xander.lab.agent.infrastructure.AgentEventBusProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** Agent 事件频道订阅容器，仅在多实例快路径显式开启时装配。 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "agent.event-bus", name = "pubsub-enabled", havingValue = "true")
public class RedisAgentEventPubSubConfiguration {

    private final AgentEventBusProperties properties;

    @Bean
    public RedisMessageListenerContainer agentEventRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisAgentEventSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(properties.getPubsubChannel()));
        return container;
    }
}

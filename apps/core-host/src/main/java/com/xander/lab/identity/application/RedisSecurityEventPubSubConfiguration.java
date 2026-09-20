package com.xander.lab.identity.application;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** Redis 安全事件频道订阅容器，仅在多实例快路径显式开启时装配。 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "auth.refresh", name = "security-pubsub-enabled", havingValue = "true")
public class RedisSecurityEventPubSubConfiguration {

    private final RefreshTokenProperties properties;

    @Bean
    public RedisMessageListenerContainer securityEventRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisSecurityEventSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber,
                new ChannelTopic(properties.getSecurityPubsubChannel()));
        return container;
    }
}

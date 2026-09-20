package com.xander.lab.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** Redis 安全事件频道订阅容器；默认关闭，避免本地开发强制依赖 Redis。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gateway.security-state", name = "pubsub-enabled", havingValue = "true")
public class GatewaySecurityPubSubConfiguration {

    private final GatewaySecurityStateProperties properties;

    public GatewaySecurityPubSubConfiguration(GatewaySecurityStateProperties properties) {
        this.properties = properties;
    }

    @Bean
    public RedisMessageListenerContainer gatewaySecurityRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            GatewaySecurityEventSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(properties.getPubsubChannel()));
        return container;
    }
}

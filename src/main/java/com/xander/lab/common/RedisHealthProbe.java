package com.xander.lab.common;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Performs a real Redis connectivity check shared by health endpoints and monitoring. */
@Component
public class RedisHealthProbe {

    private final StringRedisTemplate redis;

    public RedisHealthProbe(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Sends PING to Redis and throws the translated data-access exception on failure. */
    public void ping() {
        RedisConnection connection = redis.getConnectionFactory().getConnection();
        try {
            connection.ping();
        } finally {
            connection.close();
        }
    }
}

package com.xander.lab.bloghtml.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Redis-backed storage for preview session code. Keys are unguessable session
 * ids and expire with the session TTL; the code is stored as plain UTF-8 text.
 */
@Component
@RequiredArgsConstructor
public class BlogHtmlPreviewStore {

    private static final String KEY_PREFIX = "blog-html:preview:";

    private final StringRedisTemplate redisTemplate;

    public void save(String sessionId, String code, int ttlSeconds) {
        redisTemplate.opsForValue().set(KEY_PREFIX + sessionId, code, ttlSeconds, TimeUnit.SECONDS);
    }

    /** @return the stored code, or {@code null} when the session is unknown or expired */
    public String load(String sessionId) {
        return redisTemplate.opsForValue().get(KEY_PREFIX + sessionId);
    }

    /**
     * Atomically increments a fixed-window rate counter, setting the window
     * expiry on the first hit in the window.
     *
     * @return the new counter value, or {@code null} when Redis is unavailable
     */
    public Long rateCount(String key, int windowSeconds) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
        }
        return count;
    }
}
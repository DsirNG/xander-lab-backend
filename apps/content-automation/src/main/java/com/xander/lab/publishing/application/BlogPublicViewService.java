package com.xander.lab.publishing.application;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.TimeUnit;

/**
 * Public blog view recording owned by content-automation.
 *
 * <p>The Redis deduplication call intentionally happens before the database transaction. The
 * transaction contains only the two related MySQL writes, so Redis/network I/O cannot hold a
 * database connection open. A failed database transaction releases the deduplication key so the
 * client can retry instead of losing a legitimate view for 24 hours.</p>
 */
@Service
public class BlogPublicViewService {

    private static final String VIEW_PREFIX = "blog:view:";
    private static final long DEDUP_HOURS = 24;

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final TransactionTemplate transactionTemplate;

    public BlogPublicViewService(JdbcTemplate jdbcTemplate,
                                 StringRedisTemplate redisTemplate,
                                 TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    public boolean recordView(long postId, Long userId, String ip, String userAgent) {
        String identifier = userId == null ? "ip:" + normalizeIp(ip) : "user:" + userId;
        String dedupKey = VIEW_PREFIX + postId + ":" + identifier;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(dedupKey, "1", DEDUP_HOURS, TimeUnit.HOURS);
        if (!Boolean.TRUE.equals(acquired)) {
            return false;
        }

        try {
            Boolean counted = transactionTemplate.execute(status -> {
                int updated = jdbcTemplate.update(
                        "UPDATE blog_post SET views = views + 1 WHERE id = ? AND status = 1",
                        postId);
                if (updated == 0) {
                    return false;
                }
                jdbcTemplate.update(
                        "INSERT INTO blog_post_view (post_id, user_id, ip_address, user_agent)"
                                + " VALUES (?, ?, ?, ?)",
                        postId, userId, normalizeIp(ip), truncateUserAgent(userAgent));
                return true;
            });
            if (!Boolean.TRUE.equals(counted)) {
                redisTemplate.delete(dedupKey);
                return false;
            }
            return true;
        } catch (RuntimeException exception) {
            redisTemplate.delete(dedupKey);
            throw exception;
        }
    }

    private String normalizeIp(String ip) {
        return ip == null || ip.isBlank() ? "unknown" : ip;
    }

    private String truncateUserAgent(String userAgent) {
        return userAgent != null && userAgent.length() > 500
                ? userAgent.substring(0, 500) : userAgent;
    }
}

package com.xander.lab.publishing.application;

import com.xander.lab.automation.infrastructure.PlatformBlogSyncClient;
import com.xander.lab.integration.api.BlogSyncResult;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/** Content-owned idempotency ledger for Platform vendor publications. */
@Service
public class ContentExternalPublicationService {

    private static final int CSDN_DAILY_SYNC_LIMIT = 5;
    private static final long CSDN_COOLDOWN_SECONDS = 30;
    private static final String CSDN_COOLDOWN_PREFIX = "csdn:sync:cooldown:";

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final PlatformBlogSyncClient platformClient;

    public ContentExternalPublicationService(JdbcTemplate jdbcTemplate,
                                             StringRedisTemplate redisTemplate,
                                             PlatformBlogSyncClient platformClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.platformClient = platformClient;
    }

    /** Publishes through Platform and records the result without holding a DB transaction open. */
    public BlogSyncResult sync(Long userId, Long postId, String platform) {
        validate(userId, postId, platform);
        BlogSyncResult existing = findSucceeded(userId, postId, platform);
        if (existing != null) {
            return existing;
        }
        if ("csdn".equals(platform)) {
            enforceCsdnSyncPolicy(userId);
        }
        BlogSyncResult concurrent = prepareAttempt(userId, postId, platform);
        if (concurrent != null) {
            return concurrent;
        }
        try {
            BlogSyncResult result = "csdn".equals(platform)
                    ? platformClient.syncCsdn(userId, postId)
                    : platformClient.syncJuejin(userId, postId);
            if (result == null || result.externalId() == null || result.externalId().isBlank()) {
                throw new IllegalStateException("站外同步服务返回了无效结果");
            }
            markSucceeded(userId, postId, platform, result);
            return result;
        } catch (RuntimeException exception) {
            markFailed(userId, postId, platform, exception);
            throw exception;
        }
    }

    /** Returns durable proof only; this method never contacts Platform or a vendor. */
    public BlogSyncResult findSucceeded(Long userId, Long postId, String platform) {
        if (userId == null || postId == null || platform == null || platform.isBlank()) {
            return null;
        }
        return jdbcTemplate.query("""
                        SELECT external_id, url
                        FROM blog_publish_record
                        WHERE task_id = ? AND user_id = ? AND platform = ? AND status = 'SUCCEEDED'
                          AND external_id IS NOT NULL AND external_id <> ''
                        ORDER BY id DESC LIMIT 1
                        """, (rs, rowNum) -> new BlogSyncResult(platform,
                        rs.getString("external_id"), rs.getString("url")),
                -postId, userId, platform).stream().findFirst().orElse(null);
    }

    private BlogSyncResult prepareAttempt(Long userId, Long postId, String platform) {
        jdbcTemplate.update("""
                INSERT INTO blog_publish_record
                    (task_id, user_id, platform, status, attempts, error_code, error_message)
                VALUES (?, ?, ?, 'PENDING', 1, NULL, NULL)
                ON DUPLICATE KEY UPDATE
                    status = IF(status = 'SUCCEEDED', 'SUCCEEDED', 'PENDING'),
                    attempts = attempts + 1,
                    error_code = NULL, error_message = NULL
                """, -postId, userId, platform);
        return findSucceeded(userId, postId, platform);
    }

    private void markSucceeded(Long userId, Long postId, String platform, BlogSyncResult result) {
        jdbcTemplate.update("""
                UPDATE blog_publish_record
                SET status = 'SUCCEEDED', external_id = ?, url = ?, error_code = NULL,
                    error_message = NULL, published_at = ?
                WHERE task_id = ? AND user_id = ? AND platform = ?
                """, result.externalId(), result.url(), LocalDateTime.now(),
                -postId, userId, platform);
    }

    private void markFailed(Long userId, Long postId, String platform, RuntimeException exception) {
        String message = exception.getMessage() == null ? "publication failed" : exception.getMessage();
        jdbcTemplate.update("""
                UPDATE blog_publish_record
                SET status = 'FAILED', error_code = 'PUBLISH_FAILED', error_message = ?
                WHERE task_id = ? AND user_id = ? AND platform = ?
                """, message.substring(0, Math.min(1000, message.length())),
                -postId, userId, platform);
    }

    private void enforceCsdnSyncPolicy(Long userId) {
        boolean acquired = Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                CSDN_COOLDOWN_PREFIX + userId, "1", CSDN_COOLDOWN_SECONDS, TimeUnit.SECONDS));
        if (!acquired) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "CSDN 同步过于频繁，请稍后再试");
        }
        Long syncedToday = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM blog_publish_record
                WHERE user_id = ? AND platform = 'csdn' AND status = 'SUCCEEDED'
                  AND published_at >= ?
                """, Long.class, userId, LocalDate.now().atStartOfDay());
        if (syncedToday != null && syncedToday >= CSDN_DAILY_SYNC_LIMIT) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "今日 CSDN 同步已达上限（" + CSDN_DAILY_SYNC_LIMIT + " 篇）");
        }
    }

    private void validate(Long userId, Long postId, String platform) {
        if (userId == null || userId <= 0 || postId == null || postId <= 0
                || !("csdn".equals(platform) || "juejin".equals(platform))) {
            throw new IllegalArgumentException("站外同步请求参数非法");
        }
    }
}

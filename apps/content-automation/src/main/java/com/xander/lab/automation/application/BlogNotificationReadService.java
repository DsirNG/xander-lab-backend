package com.xander.lab.automation.application;

import com.xander.lab.automation.api.BlogNotificationListResult;
import com.xander.lab.automation.api.BlogNotificationVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/** JDBC-backed notification list and acknowledgement use cases. */
@Service
public class BlogNotificationReadService {

    private static final String SELECT_COLUMNS = """
            SELECT id, type, title, message, plan_id, run_id,
                   resource_type, resource_id, is_read, created_at
            FROM blog_notification
            WHERE user_id = ?
            ORDER BY created_at DESC, id DESC
            LIMIT ? OFFSET ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public BlogNotificationReadService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public BlogNotificationListResult list(long userId, long page, long size) {
        long safePage = Math.max(page, 1);
        int safeSize = (int) Math.min(Math.max(size, 1), 20);
        long offset = (safePage - 1) * safeSize;
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM blog_notification WHERE user_id = ?",
                Long.class, userId);
        List<BlogNotificationVO> records = jdbcTemplate.query(
                SELECT_COLUMNS,
                this::map,
                userId, safeSize, offset);
        return BlogNotificationListResult.builder()
                .records(records)
                .total(total == null ? 0 : total)
                .unreadCount(unreadCount(userId))
                .build();
    }

    @Transactional(readOnly = true)
    public long unreadCount(long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM blog_notification WHERE user_id = ? AND is_read = 0",
                Long.class, userId);
        return count == null ? 0 : count;
    }

    @Transactional
    public boolean markRead(long userId, long notificationId) {
        return jdbcTemplate.update("""
                UPDATE blog_notification
                SET is_read = 1
                WHERE id = ? AND user_id = ? AND is_read = 0
                """, notificationId, userId) == 1
                || existsForUser(userId, notificationId);
    }

    @Transactional
    public void markAllRead(long userId) {
        jdbcTemplate.update("""
                UPDATE blog_notification
                SET is_read = 1
                WHERE user_id = ? AND is_read = 0
                """, userId);
    }

    private boolean existsForUser(long userId, long notificationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM blog_notification WHERE id = ? AND user_id = ?",
                Integer.class, notificationId, userId);
        return count != null && count > 0;
    }

    private BlogNotificationVO map(ResultSet rs, int rowNum) throws SQLException {
        return BlogNotificationVO.builder()
                .id(rs.getObject("id", Long.class))
                .type(rs.getString("type"))
                .title(rs.getString("title"))
                .message(rs.getString("message"))
                .planId(rs.getObject("plan_id", Long.class))
                .runId(rs.getObject("run_id", Long.class))
                .resourceType(rs.getString("resource_type"))
                .resourceId(rs.getObject("resource_id", Long.class))
                .isRead(rs.getBoolean("is_read"))
                .createdAt(toInstant(rs))
                .build();
    }

    private Instant toInstant(ResultSet rs) throws SQLException {
        java.sql.Timestamp createdAt = rs.getTimestamp("created_at");
        return createdAt == null ? null : createdAt.toInstant();
    }
}

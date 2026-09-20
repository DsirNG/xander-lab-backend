package com.xander.lab.publishing.application;

import com.xander.lab.publishing.api.BlogManageStatsVO;
import com.xander.lab.publishing.api.BlogPublishRhythmVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Database/Redis-backed private publishing status and dashboard reads. */
@Service
public class BlogPrivateStatsReadService {

    private static final String PUBLISH_REQUEST_PREFIX = "blog:publish:request:";

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;

    public BlogPrivateStatsReadService(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
    }

    public Long publishedPostId(long userId, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        String key = PUBLISH_REQUEST_PREFIX + userId + ":" + requestId.trim();
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return null;
        }
        try {
            long postId = Long.parseLong(value);
            Long ownedPostId = jdbcTemplate.query(
                    "SELECT id FROM blog_post WHERE id = ? AND user_id = ?",
                    rs -> rs.next() ? rs.getLong("id") : null,
                    postId, userId);
            if (ownedPostId != null) {
                return ownedPostId;
            }
        } catch (NumberFormatException ignored) {
            // A malformed id is not a successful publication result.
        }
        redisTemplate.delete(key);
        return null;
    }

    public BlogPublishRhythmVO weeklyPublishRhythm(long userId, LocalDate today) {
        LocalDate reference = today == null ? LocalDate.now() : today;
        LocalDate weekStart = reference.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = weekStart.plusDays(6);
        Map<LocalDate, Long> counts = new HashMap<>();
        jdbcTemplate.query(
                "SELECT published_at FROM blog_post"
                        + " WHERE user_id = ? AND status = 1 AND published_at BETWEEN ? AND ?",
                rs -> {
                    while (rs.next()) {
                        LocalDate date = rs.getObject("published_at", LocalDate.class);
                        if (date != null) {
                            counts.merge(date, 1L, Long::sum);
                        }
                    }
                    return null;
                }, userId, weekStart, weekEnd);
        List<BlogPublishRhythmVO.DayCount> days = weekStart.datesUntil(weekStart.plusDays(7))
                .map(date -> new BlogPublishRhythmVO.DayCount(date, counts.getOrDefault(date, 0L)))
                .toList();
        long total = days.stream().mapToLong(BlogPublishRhythmVO.DayCount::count).sum();
        return BlogPublishRhythmVO.builder()
                .weekStart(weekStart)
                .dailyAverage(BigDecimal.valueOf(total)
                        .divide(BigDecimal.valueOf(7), 1, RoundingMode.HALF_UP))
                .days(days)
                .build();
    }

    public BlogManageStatsVO manageStats(long userId, LocalDate today) {
        LocalDate endDate = today == null ? LocalDate.now() : today;
        LocalDate startDate = endDate.minusDays(6);
        StatusCounts counts = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN status IN (0, 1) THEN 1 ELSE 0 END), 0) AS totalArticles,"
                        + " COALESCE(SUM(CASE WHEN status = 0 THEN 1 ELSE 0 END), 0) AS drafts,"
                        + " COALESCE(SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END), 0) AS published,"
                        + " COALESCE(SUM(CASE WHEN status = -1 THEN 1 ELSE 0 END), 0) AS trashed"
                        + " FROM blog_post WHERE user_id = ?",
                (rs, rowNum) -> new StatusCounts(
                        rs.getLong("totalArticles"), rs.getLong("drafts"),
                        rs.getLong("published"), rs.getLong("trashed")), userId);

        Map<LocalDate, Long> viewCounts = new HashMap<>();
        jdbcTemplate.query(
                "SELECT DATE(v.created_at) AS viewDate, COUNT(*) AS views"
                        + " FROM blog_post_view v JOIN blog_post p ON p.id = v.post_id"
                        + " WHERE p.user_id = ? AND v.created_at >= ?"
                        + " AND v.created_at < DATE_ADD(?, INTERVAL 1 DAY)"
                        + " GROUP BY DATE(v.created_at) ORDER BY DATE(v.created_at)",
                rs -> {
                    while (rs.next()) {
                        viewCounts.put(rs.getObject("viewDate", LocalDate.class), rs.getLong("views"));
                    }
                    return null;
                }, userId, startDate, endDate);
        List<BlogManageStatsVO.DailyViews> days = startDate.datesUntil(endDate.plusDays(1))
                .map(date -> new BlogManageStatsVO.DailyViews(date, viewCounts.getOrDefault(date, 0L)))
                .toList();
        long recentViews = days.stream().mapToLong(BlogManageStatsVO.DailyViews::views).sum();
        long recentPublished = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM blog_post WHERE user_id = ? AND status = 1"
                        + " AND published_at BETWEEN ? AND ?",
                Long.class, userId, startDate, endDate);
        return BlogManageStatsVO.builder()
                .totalArticles(counts.totalArticles())
                .drafts(counts.drafts())
                .published(counts.published())
                .trashed(counts.trashed())
                .recentViews(recentViews)
                .recentPublished(recentPublished)
                .averageViews(recentPublished == 0 ? BigDecimal.ZERO.setScale(1)
                        : BigDecimal.valueOf(recentViews)
                        .divide(BigDecimal.valueOf(recentPublished), 1, RoundingMode.HALF_UP))
                .dailyViews(days)
                .build();
    }

    private record StatusCounts(long totalArticles, long drafts, long published, long trashed) {
    }
}

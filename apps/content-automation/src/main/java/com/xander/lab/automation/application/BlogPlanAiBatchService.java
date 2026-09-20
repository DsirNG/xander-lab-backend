package com.xander.lab.automation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.automation.api.BlogContentPlanAiGenerateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Persists the one-shot plans produced by an external topic-generation call. */
@Service
@RequiredArgsConstructor
public class BlogPlanAiBatchService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${blog-content-plan.max-active-per-user:50}")
    private long maxActivePerUser;

    @Value("${blog-content-plan.max-created-per-day:20}")
    private long maxCreatedPerDay;

    @Transactional
    public List<Long> create(Long userId, BlogContentPlanAiGenerateRequest request,
                             ZoneId zone, LocalTime time, List<String> topics) {
        if (jdbcTemplate.query("SELECT id FROM sys_user WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getLong("id"), userId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        int days = topics.size();
        Long activeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM blog_content_plan WHERE user_id = ? "
                        + "AND deleted_at IS NULL AND status IN ('ACTIVE', 'RUNNING', 'PAUSED')",
                Long.class, userId);
        if ((activeCount == null ? 0 : activeCount) + days > maxActivePerUser) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "定时发文计划数量已达到上限，请先暂停或删除部分计划");
        }
        Long createdPastDay = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM blog_content_plan WHERE user_id = ? "
                        + "AND created_at >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY)",
                Long.class, userId);
        if ((createdPastDay == null ? 0 : createdPastDay) + days > maxCreatedPerDay) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "过去 24 小时创建的定时发文计划已达到上限");
        }

        LocalDate startDate = LocalDate.now(zone).plusDays(1);
        String timeText = TIME_FORMAT.format(time);
        List<Long> ids = new java.util.ArrayList<>();
        for (int index = 0; index < topics.size(); index++) {
            int planIndex = index;
            KeyHolder keyHolder = new GeneratedKeyHolder();
            LocalDate scheduledDate = startDate.plusDays(planIndex);
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO blog_content_plan (
                            user_id, topic, audience, tone, schedule_type, scheduled_date,
                            ai_option, auto_publish, timezone, trigger_time, trigger_times_json,
                            topics_json, next_topic_index, sync_csdn, sync_juejin, run_once,
                            next_run_at, status
                        ) VALUES (?, ?, ?, ?, 'ONCE', ?, 'DEEP', 1, ?, ?, ?, '[]', 0, ?, ?, 1, ?, 'ACTIVE')
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, userId);
                statement.setString(2, topics.get(planIndex));
                statement.setString(3, strip(request.getAudience()));
                statement.setString(4, strip(request.getTone()));
                statement.setObject(5, scheduledDate);
                statement.setString(6, zone.getId());
                statement.setString(7, timeText);
                statement.setString(8, toJson(List.of(timeText)));
                statement.setBoolean(9, Boolean.TRUE.equals(request.getSyncCsdn()));
                statement.setBoolean(10, Boolean.TRUE.equals(request.getSyncJuejin()));
                statement.setObject(11, java.sql.Timestamp.from(
                        ZonedDateTime.of(scheduledDate, time, zone).toInstant()));
                return statement;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key == null) {
                throw new IllegalStateException("创建 AI 计划后未返回主键");
            }
            ids.add(key.longValue());
        }
        return ids;
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法序列化计划配置", exception);
        }
    }

    private String strip(String value) {
        if (value == null) {
            return "";
        }
        String stripped = value.strip();
        return stripped.length() > 120 ? stripped.substring(0, 120) : stripped;
    }
}

package com.xander.lab.automation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.automation.api.BlogContentPlanCreateRequest;
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
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Transactional creation of scheduled content plans. */
@Service
@RequiredArgsConstructor
public class BlogPlanCreateService {

    private static final String UNAUTHORIZED_MESSAGE = "未登录或登录已过期";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${blog-content-plan.max-active-per-user:50}")
    private long maxActivePerUser;

    @Value("${blog-content-plan.max-created-per-day:20}")
    private long maxCreatedPerDay;

    @Transactional
    public Long create(Long userId, BlogContentPlanCreateRequest request) {
        requireUser(userId);
        if (jdbcTemplate.query("SELECT id FROM sys_user WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getLong("id"), userId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MESSAGE);
        }

        String clientRequestId = normalize(request.getClientRequestId());
        if (clientRequestId != null) {
            List<ExistingPlan> existing = jdbcTemplate.query(
                    "SELECT id, deleted_at FROM blog_content_plan WHERE user_id = ? AND client_request_id = ?",
                    (rs, rowNum) -> new ExistingPlan(rs.getLong("id"), rs.getTimestamp("deleted_at") != null),
                    userId, clientRequestId);
            if (!existing.isEmpty()) {
                if (existing.get(0).deleted()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "该幂等请求标识已被删除计划使用，请生成新的标识");
                }
                return existing.get(0).id();
            }
        }

        String scheduleType = request.getScheduleType() == null || request.getScheduleType().isBlank()
                ? "DAILY" : request.getScheduleType();
        ZoneId zone = BlogPlanScheduleCalculator.resolveZone(request.getTimezone());
        List<LocalTime> times = BlogPlanScheduleCalculator.resolveTriggerTimes(
                request.getTriggerTimes(), request.getTriggerTime());
        long activeCount = countActivePlans(userId);
        if (activeCount >= maxActivePerUser) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "定时发文计划数量已达到上限，请先暂停或删除部分计划");
        }
        long createdPastDay = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM blog_content_plan WHERE user_id = ? "
                        + "AND created_at >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY)",
                Long.class, userId);
        if (createdPastDay >= maxCreatedPerDay) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "过去 24 小时创建的定时发文计划已达到上限");
        }

        Instant nextRun = BlogPlanScheduleCalculator.firstRun(
                scheduleType, zone, times, request.getScheduledDate(), Instant.now());
        List<String> topics = normalizeTopics(request.getTopics());
        String topic = request.getTopic().strip();
        String timesJson = toJson(times.stream().map(TIME_FORMAT::format).toList());
        String topicsJson = toJson(topics);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO blog_content_plan (
                        user_id, client_request_id, topic, audience, tone, schedule_type,
                        scheduled_date, ai_option, knowledge_material_id, auto_publish,
                        timezone, trigger_time, trigger_times_json, topics_json, next_topic_index,
                        sync_csdn, sync_juejin, run_once, next_run_at, status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setString(2, clientRequestId);
            statement.setString(3, topic);
            statement.setString(4, strip(request.getAudience()));
            statement.setString(5, strip(request.getTone()));
            statement.setString(6, scheduleType);
            statement.setObject(7, request.getScheduledDate());
            statement.setString(8, request.getAiOption() == null ? "DEEP" : request.getAiOption());
            if (request.getKnowledgeMaterialId() == null) {
                statement.setObject(9, null);
            } else {
                statement.setLong(9, request.getKnowledgeMaterialId());
            }
            statement.setBoolean(10, request.getAutoPublish() == null || request.getAutoPublish());
            statement.setString(11, zone.getId());
            statement.setString(12, TIME_FORMAT.format(times.get(0)));
            statement.setString(13, timesJson);
            statement.setString(14, topicsJson);
            statement.setInt(15, 0);
            statement.setBoolean(16, Boolean.TRUE.equals(request.getSyncCsdn()));
            statement.setBoolean(17, Boolean.TRUE.equals(request.getSyncJuejin()));
            statement.setBoolean(18, "ONCE".equals(scheduleType));
            statement.setObject(19, java.sql.Timestamp.from(nextRun));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建计划后未返回主键");
        }
        return key.longValue();
    }

    private long countActivePlans(Long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM blog_content_plan WHERE user_id = ? "
                        + "AND deleted_at IS NULL AND status IN ('ACTIVE', 'RUNNING', 'PAUSED')",
                Long.class, userId);
        return count == null ? 0 : count;
    }

    private List<String> normalizeTopics(List<String> topics) {
        if (topics == null) {
            return List.of();
        }
        return topics.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .limit(30)
                .toList();
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法序列化计划配置", exception);
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private String strip(String value) {
        if (value == null) {
            return "";
        }
        String stripped = value.strip();
        return stripped.length() > 120 ? stripped.substring(0, 120) : stripped;
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MESSAGE);
        }
    }

    private record ExistingPlan(Long id, boolean deleted) {
    }
}

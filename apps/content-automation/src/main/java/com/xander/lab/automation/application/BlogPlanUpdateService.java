package com.xander.lab.automation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.automation.api.BlogContentPlanUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Transactional partial updates for scheduled content plans. */
@Service
@RequiredArgsConstructor
public class BlogPlanUpdateService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final String PLAN_SQL = """
            SELECT id, topic, audience, tone, schedule_type, scheduled_date, ai_option,
                   knowledge_material_id, auto_publish, timezone, trigger_time,
                   trigger_times_json, topics_json, next_topic_index, sync_csdn, sync_juejin,
                   run_once, next_run_at, status
            FROM blog_content_plan
            WHERE id = ? AND user_id = ? AND deleted_at IS NULL
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public Long update(Long userId, Long planId, BlogContentPlanUpdateRequest request) {
        requireUser(userId);
        PlanState current = jdbcTemplate.query(PLAN_SQL, this::mapPlan, planId, userId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "定时发文计划不存在"));
        if ("RUNNING".equals(current.status()) || "CANCELLED".equals(current.status())
                || "FINISHED".equals(current.status()) || "FAILED".equals(current.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "计划执行中、已取消、已完结或已失败，暂不能修改");
        }

        MutablePlan plan = new MutablePlan(current);
        boolean scheduleChanged = applyRequest(plan, request);
        if (scheduleChanged) {
            ZoneId zone = BlogPlanScheduleCalculator.resolveZone(plan.timezone);
            List<LocalTime> times = triggerTimes(plan.triggerTimesJson, plan.triggerTime);
            plan.nextRunAt = BlogPlanScheduleCalculator.firstRun(
                    plan.scheduleType, zone, times, plan.scheduledDate, Instant.now());
        }

        int updated = jdbcTemplate.update("""
                UPDATE blog_content_plan
                SET topic = ?, audience = ?, tone = ?, schedule_type = ?, scheduled_date = ?,
                    ai_option = ?, knowledge_material_id = ?, auto_publish = ?, timezone = ?,
                    trigger_time = ?, trigger_times_json = ?, topics_json = ?, next_topic_index = ?,
                    sync_csdn = ?, sync_juejin = ?, run_once = ?, next_run_at = ?
                WHERE id = ? AND user_id = ? AND deleted_at IS NULL AND status = ?
                """,
                plan.topic,
                plan.audience,
                plan.tone,
                plan.scheduleType,
                plan.scheduledDate,
                plan.aiOption,
                plan.knowledgeMaterialId,
                plan.autoPublish,
                plan.timezone,
                plan.triggerTime,
                plan.triggerTimesJson,
                plan.topicsJson,
                plan.nextTopicIndex,
                plan.syncCsdn,
                plan.syncJuejin,
                plan.runOnce,
                plan.nextRunAt == null ? null : Timestamp.from(plan.nextRunAt),
                planId,
                userId,
                current.status);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "计划状态已变化，请刷新后重试");
        }
        return planId;
    }

    private boolean applyRequest(MutablePlan plan, BlogContentPlanUpdateRequest request) {
        boolean scheduleChanged = false;
        if (request.getTopic() != null && !request.getTopic().isBlank()) {
            plan.topic = request.getTopic().strip();
        }
        if (request.getSyncCsdn() != null) {
            plan.syncCsdn = request.getSyncCsdn();
        }
        if (request.getSyncJuejin() != null) {
            plan.syncJuejin = request.getSyncJuejin();
        }
        if (request.getAudience() != null) {
            plan.audience = strip(request.getAudience());
        }
        if (request.getTone() != null) {
            plan.tone = strip(request.getTone());
        }
        if (request.getAiOption() != null) {
            plan.aiOption = request.getAiOption();
        }
        if (request.getKnowledgeMaterialId() != null) {
            plan.knowledgeMaterialId = request.getKnowledgeMaterialId();
        }
        if (request.getAutoPublish() != null) {
            plan.autoPublish = request.getAutoPublish();
        }
        if (request.getScheduleType() != null) {
            plan.scheduleType = request.getScheduleType();
            plan.runOnce = "ONCE".equals(request.getScheduleType());
            scheduleChanged = true;
        }
        if (request.getScheduledDate() != null) {
            plan.scheduledDate = request.getScheduledDate();
            scheduleChanged = true;
        }
        if (request.getTopics() != null) {
            plan.topicsJson = toJson(normalizeTopics(request.getTopics()));
            plan.nextTopicIndex = 0;
        }
        if (request.getTriggerTimes() != null) {
            List<LocalTime> times = BlogPlanScheduleCalculator.resolveTriggerTimes(
                    request.getTriggerTimes(), null);
            String timesJson = toJson(times.stream().map(TIME_FORMAT::format).toList());
            if (!timesJson.equals(plan.triggerTimesJson)) {
                plan.triggerTimesJson = timesJson;
                plan.triggerTime = TIME_FORMAT.format(times.get(0));
                scheduleChanged = true;
            }
        } else if (request.getTriggerTime() != null && !request.getTriggerTime().isBlank()
                && !request.getTriggerTime().equals(plan.triggerTime)) {
            List<LocalTime> times = BlogPlanScheduleCalculator.resolveTriggerTimes(
                    null, request.getTriggerTime());
            plan.triggerTime = TIME_FORMAT.format(times.get(0));
            plan.triggerTimesJson = null;
            scheduleChanged = true;
        }
        if (request.getTimezone() != null && !request.getTimezone().isBlank()) {
            ZoneId zone = BlogPlanScheduleCalculator.resolveZone(request.getTimezone());
            if (!zone.getId().equals(plan.timezone)) {
                plan.timezone = zone.getId();
                scheduleChanged = true;
            }
        }
        return scheduleChanged;
    }

    private PlanState mapPlan(ResultSet rs, int rowNum) throws SQLException {
        return new PlanState(
                rs.getString("topic"),
                rs.getString("audience"),
                rs.getString("tone"),
                rs.getString("schedule_type"),
                rs.getObject("scheduled_date", LocalDate.class),
                rs.getString("ai_option"),
                toLong(rs, "knowledge_material_id"),
                toBoolean(rs, "auto_publish"),
                rs.getString("timezone"),
                rs.getString("trigger_time"),
                rs.getString("trigger_times_json"),
                rs.getString("topics_json"),
                rs.getObject("next_topic_index", Integer.class),
                toBoolean(rs, "sync_csdn"),
                toBoolean(rs, "sync_juejin"),
                toBoolean(rs, "run_once"),
                toInstant(rs, "next_run_at"),
                rs.getString("status"));
    }

    private List<LocalTime> triggerTimes(String json, String fallback) {
        if (json == null || json.isBlank()) {
            return BlogPlanScheduleCalculator.resolveTriggerTimes(null, fallback);
        }
        try {
            List<String> values = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return BlogPlanScheduleCalculator.resolveTriggerTimes(values, null);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("计划配置数据损坏", exception);
        }
    }

    private List<String> normalizeTopics(List<String> topics) {
        return topics.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .limit(30)
                .toList();
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法序列化计划配置", exception);
        }
    }

    private String strip(String value) {
        String stripped = value.strip();
        return stripped.length() > 120 ? stripped.substring(0, 120) : stripped;
    }

    private Long toLong(ResultSet rs, String column) throws SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? null : value.longValue();
    }

    private Boolean toBoolean(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        return value instanceof Boolean booleanValue
                ? booleanValue : ((Number) value).intValue() != 0;
    }

    private Instant toInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
    }

    private record PlanState(String topic, String audience, String tone, String scheduleType,
                             LocalDate scheduledDate, String aiOption, Long knowledgeMaterialId,
                             Boolean autoPublish, String timezone, String triggerTime,
                             String triggerTimesJson, String topicsJson, Integer nextTopicIndex,
                             Boolean syncCsdn, Boolean syncJuejin, Boolean runOnce,
                             Instant nextRunAt, String status) {
    }

    private static final class MutablePlan {
        private String topic;
        private String audience;
        private String tone;
        private String scheduleType;
        private LocalDate scheduledDate;
        private String aiOption;
        private Long knowledgeMaterialId;
        private Boolean autoPublish;
        private String timezone;
        private String triggerTime;
        private String triggerTimesJson;
        private String topicsJson;
        private Integer nextTopicIndex;
        private Boolean syncCsdn;
        private Boolean syncJuejin;
        private Boolean runOnce;
        private Instant nextRunAt;

        private MutablePlan(PlanState state) {
            topic = state.topic();
            audience = state.audience();
            tone = state.tone();
            scheduleType = state.scheduleType();
            scheduledDate = state.scheduledDate();
            aiOption = state.aiOption();
            knowledgeMaterialId = state.knowledgeMaterialId();
            autoPublish = state.autoPublish();
            timezone = state.timezone();
            triggerTime = state.triggerTime();
            triggerTimesJson = state.triggerTimesJson();
            topicsJson = state.topicsJson();
            nextTopicIndex = state.nextTopicIndex();
            syncCsdn = state.syncCsdn();
            syncJuejin = state.syncJuejin();
            runOnce = state.runOnce();
            nextRunAt = state.nextRunAt();
        }
    }
}

package com.xander.lab.automation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.automation.api.BlogContentPlanVO;
import com.xander.lab.automation.api.BlogContentPlansPage;
import com.xander.lab.automation.infrastructure.PlatformCredentialStatusClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/** JDBC-backed content-plan list and detail reads. */
@Service
@RequiredArgsConstructor
public class BlogPlanReadService {

    private static final String PLAN_COLUMNS = """
            SELECT id, topic, trigger_time, trigger_times_json, topics_json, next_topic_index,
                   audience, tone, schedule_type, scheduled_date, ai_option,
                   knowledge_material_id, auto_publish, timezone, sync_csdn, sync_juejin,
                   run_once, next_run_at, last_run_at, status, error_message,
                   created_at, updated_at
            FROM blog_content_plan
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PlatformCredentialStatusClient credentialStatusClient;

    public BlogContentPlansPage list(Long userId, long page, long size, String authorization) {
        requireUser(userId);
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), 50);
        long offset = (safePage - 1) * safeSize;
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM blog_content_plan WHERE user_id = ? AND deleted_at IS NULL",
                Long.class,
                userId);
        List<BlogContentPlanVO> records = jdbcTemplate.query(
                        PLAN_COLUMNS + "WHERE user_id = ? AND deleted_at IS NULL "
                                + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                        this::mapPlan,
                        userId,
                        safeSize,
                        offset)
                .stream()
                .map(plan -> enrich(plan, userId, authorization))
                .toList();
        return BlogContentPlansPage.builder()
                .records(records)
                .total(total == null ? 0 : total)
                .page(safePage)
                .size(safeSize)
                .build();
    }

    public BlogContentPlanVO detail(Long userId, Long planId, String authorization) {
        requireUser(userId);
        List<BlogContentPlanVO> records = jdbcTemplate.query(
                PLAN_COLUMNS + "WHERE id = ? AND user_id = ? AND deleted_at IS NULL",
                this::mapPlan,
                planId,
                userId);
        if (records.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "定时发文计划不存在");
        }
        return enrich(records.get(0), userId, authorization);
    }

    private BlogContentPlanVO enrich(BlogContentPlanVO plan, Long userId, String authorization) {
        return BlogContentPlanVO.builder()
                .id(plan.getId())
                .topic(plan.getTopic())
                .triggerTimes(plan.getTriggerTimes())
                .topics(plan.getTopics())
                .nextTopicIndex(plan.getNextTopicIndex())
                .audience(plan.getAudience())
                .tone(plan.getTone())
                .scheduleType(plan.getScheduleType())
                .scheduledDate(plan.getScheduledDate())
                .aiOption(plan.getAiOption())
                .knowledgeMaterialId(plan.getKnowledgeMaterialId())
                .autoPublish(plan.getAutoPublish())
                .timezone(plan.getTimezone())
                .triggerTime(plan.getTriggerTime())
                .syncCsdn(plan.getSyncCsdn())
                .syncJuejin(plan.getSyncJuejin())
                .runOnce(plan.getRunOnce())
                .nextRunAt(plan.getNextRunAt())
                .lastRunAt(plan.getLastRunAt())
                .status(plan.getStatus())
                .errorMessage(plan.getErrorMessage())
                .createdAt(plan.getCreatedAt())
                .updatedAt(plan.getUpdatedAt())
                .csdnAuthorized(credentialStatusClient.isAuthorized("csdn", userId, authorization))
                .juejinAuthorized(credentialStatusClient.isAuthorized("juejin", userId, authorization))
                .build();
    }

    private BlogContentPlanVO mapPlan(ResultSet rs, int rowNum) throws SQLException {
        return BlogContentPlanVO.builder()
                .id(toLong(rs, "id"))
                .topic(rs.getString("topic"))
                .triggerTimes(parseList(rs.getString("trigger_times_json"), rs.getString("trigger_time")))
                .topics(parseList(rs.getString("topics_json"), null))
                .nextTopicIndex(rs.getObject("next_topic_index", Integer.class))
                .audience(rs.getString("audience"))
                .tone(rs.getString("tone"))
                .scheduleType(defaultValue(rs.getString("schedule_type"), "DAILY"))
                .scheduledDate(toLocalDate(rs.getDate("scheduled_date")))
                .aiOption(defaultValue(rs.getString("ai_option"), "DEEP"))
                .knowledgeMaterialId(toLong(rs, "knowledge_material_id"))
                .autoPublish(toBoolean(rs, "auto_publish"))
                .timezone(rs.getString("timezone"))
                .triggerTime(rs.getString("trigger_time"))
                .syncCsdn(toBoolean(rs, "sync_csdn"))
                .syncJuejin(toBoolean(rs, "sync_juejin"))
                .runOnce(toBoolean(rs, "run_once"))
                .nextRunAt(toInstant(rs, "next_run_at"))
                .lastRunAt(toInstant(rs, "last_run_at"))
                .status(rs.getString("status"))
                .errorMessage(rs.getString("error_message"))
                .createdAt(toInstant(rs, "created_at"))
                .updatedAt(toInstant(rs, "updated_at"))
                .build();
    }

    private List<String> parseList(String json, String fallback) {
        if (json != null && !json.isBlank()) {
            try {
                return objectMapper.readValue(json, new TypeReference<List<String>>() { });
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException("计划配置数据损坏", exception);
            }
        }
        return fallback == null || fallback.isBlank() ? List.of() : List.of(fallback);
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
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return ((Number) value).intValue() != 0;
    }

    private Instant toInstant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private java.time.LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
    }
}

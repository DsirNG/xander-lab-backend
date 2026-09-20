package com.xander.lab.automation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Transactional status transitions for content plans. */
@Service
@RequiredArgsConstructor
public class BlogPlanStatusService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final String PLAN_SQL = """
            SELECT status, run_once, next_run_at, timezone, trigger_time, trigger_times_json
            FROM blog_content_plan
            WHERE id = ? AND user_id = ? AND deleted_at IS NULL
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public void update(Long userId, Long planId, String action) {
        requireUser(userId);
        PlanState plan = jdbcTemplate.query(PLAN_SQL, this::mapPlan, planId, userId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "定时发文计划不存在"));
        String normalized = action.strip().toUpperCase(Locale.ROOT);
        int updated = switch (normalized) {
            case "PAUSED" -> pause(planId, userId, plan);
            case "RESUME" -> resume(planId, userId, plan);
            case "CANCELLED" -> cancel(planId, userId);
            default -> throw new IllegalArgumentException(
                    "计划操作只支持 PAUSED / RESUME / CANCELLED");
        };
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "计划状态已变化，请刷新后重试");
        }
    }

    private int pause(Long planId, Long userId, PlanState plan) {
        if (!"ACTIVE".equals(plan.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态无法暂停");
        }
        return jdbcTemplate.update(
                "UPDATE blog_content_plan SET status = 'PAUSED' "
                        + "WHERE id = ? AND user_id = ? AND deleted_at IS NULL AND status = 'ACTIVE'",
                planId,
                userId);
    }

    private int resume(Long planId, Long userId, PlanState plan) {
        if (!"PAUSED".equals(plan.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态无法恢复");
        }
        Instant now = Instant.now();
        if (plan.runOnce() || (plan.nextRunAt() != null && plan.nextRunAt().isAfter(now))) {
            return jdbcTemplate.update(
                    "UPDATE blog_content_plan SET status = 'ACTIVE', error_message = NULL, "
                            + "processing_token = NULL, claimed_at = NULL "
                            + "WHERE id = ? AND user_id = ? AND deleted_at IS NULL AND status = 'PAUSED'",
                    planId,
                    userId);
        }
        Instant next = nextDaily(plan, now);
        return jdbcTemplate.update(
                "UPDATE blog_content_plan SET status = 'ACTIVE', next_run_at = ?, "
                        + "error_message = NULL, processing_token = NULL, claimed_at = NULL "
                        + "WHERE id = ? AND user_id = ? AND deleted_at IS NULL AND status = 'PAUSED'",
                next,
                planId,
                userId);
    }

    private int cancel(Long planId, Long userId) {
        return jdbcTemplate.update(
                "UPDATE blog_content_plan SET status = 'CANCELLED', error_message = NULL, "
                        + "processing_token = NULL, claimed_at = NULL "
                        + "WHERE id = ? AND user_id = ? AND deleted_at IS NULL "
                        + "AND status IN ('ACTIVE', 'PAUSED', 'FAILED')",
                planId,
                userId);
    }

    private PlanState mapPlan(ResultSet rs, int rowNum) throws SQLException {
        Object runOnce = rs.getObject("run_once");
        boolean oneShot = runOnce instanceof Boolean value ? value : ((Number) runOnce).intValue() != 0;
        var timestamp = rs.getTimestamp("next_run_at");
        return new PlanState(
                rs.getString("status"),
                oneShot,
                timestamp == null ? null : timestamp.toInstant(),
                rs.getString("timezone"),
                rs.getString("trigger_time"),
                rs.getString("trigger_times_json"));
    }

    private Instant nextDaily(PlanState plan, Instant reference) {
        ZoneId zone = resolveZone(plan.timezone());
        List<LocalTime> times = parseTriggerTimes(plan);
        return times.stream()
                .map(time -> nextDaily(zone, time, reference))
                .min(Instant::compareTo)
                .orElseThrow(() -> new IllegalArgumentException("请设置至少一个发送时间"));
    }

    private Instant nextDaily(ZoneId zone, LocalTime time, Instant reference) {
        ZonedDateTime local = reference.atZone(zone);
        ZonedDateTime candidate = ZonedDateTime.of(local.toLocalDate(), time, zone);
        if (!candidate.toInstant().isAfter(reference)) {
            candidate = candidate.plusDays(1);
        }
        return candidate.toInstant();
    }

    private List<LocalTime> parseTriggerTimes(PlanState plan) {
        List<String> values = new ArrayList<>();
        if (plan.triggerTimesJson() != null && !plan.triggerTimesJson().isBlank()) {
            try {
                values.addAll(objectMapper.readValue(
                        plan.triggerTimesJson(), new TypeReference<List<String>>() { }));
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException("计划配置数据损坏", exception);
            }
        }
        if (values.isEmpty() && plan.triggerTime() != null && !plan.triggerTime().isBlank()) {
            values.add(plan.triggerTime());
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("请设置至少一个发送时间");
        }
        return values.stream().map(this::parseTime).distinct().sorted().toList();
    }

    private LocalTime parseTime(String value) {
        try {
            return LocalTime.parse(value.strip(), TIME_FORMAT);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("发送时间格式应为 HH:mm");
        }
    }

    private ZoneId resolveZone(String timezone) {
        String value = timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone.strip();
        try {
            return ZoneId.of(value);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("时区无效：" + value);
        }
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
    }

    private record PlanState(String status, boolean runOnce, Instant nextRunAt, String timezone,
                             String triggerTime, String triggerTimesJson) {
    }
}

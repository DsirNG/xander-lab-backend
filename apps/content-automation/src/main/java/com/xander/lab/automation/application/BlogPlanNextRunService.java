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

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Read-only next-fire preview for content plans. */
@Service
@RequiredArgsConstructor
public class BlogPlanNextRunService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final String PLAN_SQL = """
            SELECT schedule_type, scheduled_date, timezone, trigger_time, trigger_times_json
            FROM blog_content_plan
            WHERE id = ? AND user_id = ? AND deleted_at IS NULL
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Instant nextRun(Long userId, Long planId, long count) {
        requireUser(userId);
        PlanSchedule plan = jdbcTemplate.query(PLAN_SQL, this::mapPlan, planId, userId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "定时发文计划不存在"));

        Instant cursor = Instant.now();
        for (long i = 0; i < Math.max(1, count); i++) {
            cursor = nextOccurrence(plan, cursor);
        }
        return cursor;
    }

    private PlanSchedule mapPlan(ResultSet rs, int rowNum) throws SQLException {
        Date scheduledDate = rs.getDate("scheduled_date");
        return new PlanSchedule(
                rs.getString("schedule_type"),
                scheduledDate == null ? null : scheduledDate.toLocalDate(),
                rs.getString("timezone"),
                rs.getString("trigger_time"),
                rs.getString("trigger_times_json"));
    }

    private Instant nextOccurrence(PlanSchedule plan, Instant reference) {
        ZoneId zone = resolveZone(plan.timezone());
        List<LocalTime> times = parseTriggerTimes(plan);
        if ("WEEKLY".equals(plan.scheduleType())) {
            DayOfWeek day = plan.scheduledDate() == null
                    ? reference.atZone(zone).getDayOfWeek()
                    : plan.scheduledDate().getDayOfWeek();
            return nextWeekly(zone, times, day, reference);
        }
        return nextDaily(zone, times, reference);
    }

    private List<LocalTime> parseTriggerTimes(PlanSchedule plan) {
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
        return values.stream()
                .map(this::parseTriggerTime)
                .distinct()
                .sorted()
                .toList();
    }

    private LocalTime parseTriggerTime(String value) {
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

    private Instant nextDaily(ZoneId zone, List<LocalTime> times, Instant reference) {
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

    private Instant nextWeekly(ZoneId zone, List<LocalTime> times,
                               DayOfWeek day, Instant reference) {
        ZonedDateTime cursor = reference.atZone(zone);
        for (int offset = 0; offset <= 7; offset++) {
            LocalDate date = cursor.toLocalDate().plusDays(offset);
            if (date.getDayOfWeek() != day) {
                continue;
            }
            for (LocalTime time : times.stream().sorted(Comparator.naturalOrder()).toList()) {
                Instant candidate = ZonedDateTime.of(date, time, zone).toInstant();
                if (candidate.isAfter(reference)) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException("无法计算每周计划的下次执行时间");
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
    }

    private record PlanSchedule(String scheduleType, LocalDate scheduledDate, String timezone,
                                String triggerTime, String triggerTimesJson) {
    }
}

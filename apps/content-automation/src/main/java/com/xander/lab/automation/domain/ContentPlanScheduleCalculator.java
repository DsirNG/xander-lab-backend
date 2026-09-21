package com.xander.lab.automation.domain;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Pure schedule calculation used by the content-owned plan poller and stale recovery. */
public final class ContentPlanScheduleCalculator {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private ContentPlanScheduleCalculator() {
    }

    /** Computes the next daily or weekly trigger strictly after {@code reference}. */
    public static Instant nextRun(String scheduleType, String timezone, List<String> triggerTimes,
                                  String legacyTriggerTime, LocalDate scheduledDate,
                                  Instant reference) {
        ZoneId zone = resolveZone(timezone);
        List<LocalTime> times = parseTimes(triggerTimes, legacyTriggerTime);
        if ("WEEKLY".equals(normalize(scheduleType))) {
            DayOfWeek day = scheduledDate == null
                    ? reference.atZone(zone).getDayOfWeek() : scheduledDate.getDayOfWeek();
            return nextWeekly(zone, times, day, reference);
        }
        return times.stream()
                .map(time -> nextDaily(zone, time, reference))
                .min(Instant::compareTo)
                .orElseThrow(() -> new IllegalArgumentException("请设置至少一个触发时间"));
    }

    public static ZoneId resolveZone(String timezone) {
        String value = timezone == null || timezone.isBlank()
                ? "Asia/Shanghai" : timezone.strip();
        try {
            return ZoneId.of(value);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("时区无效：" + value, exception);
        }
    }

    private static List<LocalTime> parseTimes(List<String> triggerTimes, String legacyTriggerTime) {
        List<String> source = triggerTimes == null || triggerTimes.isEmpty()
                ? (legacyTriggerTime == null || legacyTriggerTime.isBlank()
                ? List.of() : List.of(legacyTriggerTime)) : triggerTimes;
        if (source.isEmpty()) {
            throw new IllegalArgumentException("请设置至少一个触发时间");
        }
        return source.stream().map(ContentPlanScheduleCalculator::parseTime)
                .distinct().sorted().collect(Collectors.toList());
    }

    private static LocalTime parseTime(String value) {
        try {
            return LocalTime.parse(value.strip(), TIME_FORMAT);
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new IllegalArgumentException("触发时间格式应为 HH:mm", exception);
        }
    }

    private static Instant nextDaily(ZoneId zone, LocalTime time, Instant reference) {
        ZonedDateTime local = reference.atZone(zone);
        ZonedDateTime candidate = ZonedDateTime.of(local.toLocalDate(), time, zone);
        return (candidate.toInstant().isAfter(reference) ? candidate : candidate.plusDays(1)).toInstant();
    }

    private static Instant nextWeekly(ZoneId zone, List<LocalTime> times, DayOfWeek day,
                                      Instant reference) {
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

    private static String normalize(String scheduleType) {
        return scheduleType == null || scheduleType.isBlank()
                ? "DAILY" : scheduleType.strip().toUpperCase(Locale.ROOT);
    }
}

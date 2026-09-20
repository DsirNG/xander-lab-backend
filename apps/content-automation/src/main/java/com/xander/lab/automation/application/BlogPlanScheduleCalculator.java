package com.xander.lab.automation.application;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Computes normalized trigger times and the first absolute fire instant. */
public final class BlogPlanScheduleCalculator {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private BlogPlanScheduleCalculator() {
    }

    public static ZoneId resolveZone(String timezone) {
        String value = timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone.strip();
        try {
            return ZoneId.of(value);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("时区无效：" + value);
        }
    }

    public static List<LocalTime> resolveTriggerTimes(List<String> triggerTimes, String triggerTime) {
        if (triggerTimes != null && !triggerTimes.isEmpty()) {
            if (triggerTimes.size() > 5) {
                throw new IllegalArgumentException("每天最多设置 5 个发送时间");
            }
            return triggerTimes.stream()
                    .map(BlogPlanScheduleCalculator::parseTime)
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
        }
        if (triggerTime == null || triggerTime.isBlank()) {
            throw new IllegalArgumentException("请设置每日触发时间");
        }
        return List.of(parseTime(triggerTime));
    }

    public static Instant firstRun(String scheduleType, ZoneId zone, List<LocalTime> times,
                                   LocalDate scheduledDate, Instant reference) {
        String normalized = scheduleType == null || scheduleType.isBlank()
                ? "DAILY" : scheduleType.toUpperCase(Locale.ROOT);
        if ("DAILY".equals(normalized)) {
            return nextDaily(zone, times, reference);
        }
        if (scheduledDate == null) {
            throw new IllegalArgumentException("一次性和每周计划必须设置执行日期");
        }
        LocalTime earliest = times.stream().min(LocalTime::compareTo)
                .orElseThrow(() -> new IllegalArgumentException("请设置触发时间"));
        Instant candidate = ZonedDateTime.of(scheduledDate, earliest, zone).toInstant();
        if (candidate.isAfter(reference)) {
            return candidate;
        }
        if ("ONCE".equals(normalized)) {
            throw new IllegalArgumentException("一次性计划的执行时间必须晚于当前时间");
        }
        DayOfWeek weekday = scheduledDate.getDayOfWeek();
        ZonedDateTime local = reference.atZone(zone);
        for (int offset = 0; offset <= 7; offset++) {
            LocalDate date = local.toLocalDate().plusDays(offset);
            if (date.getDayOfWeek() != weekday) {
                continue;
            }
            for (LocalTime time : times) {
                Instant next = ZonedDateTime.of(date, time, zone).toInstant();
                if (next.isAfter(reference)) {
                    return next;
                }
            }
        }
        throw new IllegalArgumentException("无法计算每周计划的下次执行时间");
    }

    private static LocalTime parseTime(String value) {
        try {
            return LocalTime.parse(value.strip(), TIME_FORMAT);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("触发时间格式应为 HH:mm");
        }
    }

    private static Instant nextDaily(ZoneId zone, List<LocalTime> times, Instant reference) {
        if (times == null || times.isEmpty()) {
            throw new IllegalArgumentException("请设置至少一个发送时间");
        }
        return times.stream()
                .map(time -> {
                    ZonedDateTime local = reference.atZone(zone);
                    ZonedDateTime candidate = ZonedDateTime.of(local.toLocalDate(), time, zone);
                    return (candidate.toInstant().isAfter(reference)
                            ? candidate : candidate.plusDays(1)).toInstant();
                })
                .min(Instant::compareTo)
                .orElseThrow(() -> new IllegalArgumentException("请设置至少一个发送时间"));
    }
}

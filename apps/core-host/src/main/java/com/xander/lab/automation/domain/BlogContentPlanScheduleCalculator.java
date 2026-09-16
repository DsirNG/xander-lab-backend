package com.xander.lab.automation.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;

/** Calculates the next absolute trigger for daily, weekly, and one-shot plans. */
public final class BlogContentPlanScheduleCalculator {

    private BlogContentPlanScheduleCalculator() {
    }

    public static Instant firstRun(String scheduleType, ZoneId zone, List<LocalTime> times,
                                   LocalDate scheduledDate, Instant reference) {
        String type = normalize(scheduleType);
        if (BlogContentPlan.SCHEDULE_DAILY.equals(type)) {
            return EmailReminderScheduleCalculator.resolveNextDailyAfter(zone, times, reference);
        }
        if (scheduledDate == null) {
            throw new IllegalArgumentException("一次性和每周计划必须设置执行日期");
        }
        Instant result = ZonedDateTime.of(scheduledDate, earliest(times), zone).toInstant();
        if (!result.isAfter(reference)) {
            if (BlogContentPlan.SCHEDULE_ONCE.equals(type)) {
                throw new IllegalArgumentException("一次性计划的执行时间必须晚于当前时间");
            }
            return nextWeekly(zone, times, scheduledDate.getDayOfWeek(), reference);
        }
        return result;
    }

    public static Instant nextRun(BlogContentPlan plan, Instant reference) {
        ZoneId zone = EmailReminderScheduleCalculator.resolveZone(plan.getTimezone());
        List<LocalTime> times = EmailReminderScheduleCalculator.parseSendTimes(plan.allTriggerTimes());
        String type = normalize(plan.getScheduleType());
        if (BlogContentPlan.SCHEDULE_WEEKLY.equals(type)) {
            DayOfWeek day = plan.getScheduledDate() == null
                    ? reference.atZone(zone).getDayOfWeek()
                    : plan.getScheduledDate().getDayOfWeek();
            return nextWeekly(zone, times, day, reference);
        }
        return EmailReminderScheduleCalculator.resolveNextDailyAfter(zone, times, reference);
    }

    private static Instant nextWeekly(ZoneId zone, List<LocalTime> times, DayOfWeek day, Instant reference) {
        ZonedDateTime cursor = reference.atZone(zone);
        for (int offset = 0; offset <= 7; offset++) {
            LocalDate date = cursor.toLocalDate().plusDays(offset);
            if (date.getDayOfWeek() != day) continue;
            for (LocalTime time : times.stream().sorted().toList()) {
                Instant candidate = ZonedDateTime.of(date, time, zone).toInstant();
                if (candidate.isAfter(reference)) return candidate;
            }
        }
        throw new IllegalStateException("无法计算每周计划的下次执行时间");
    }

    private static LocalTime earliest(List<LocalTime> times) {
        return times.stream().min(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalArgumentException("请设置触发时间"));
    }

    private static String normalize(String scheduleType) {
        return scheduleType == null || scheduleType.isBlank()
                ? BlogContentPlan.SCHEDULE_DAILY : scheduleType;
    }
}

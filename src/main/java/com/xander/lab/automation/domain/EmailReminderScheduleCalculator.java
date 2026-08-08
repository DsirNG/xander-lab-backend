package com.xander.lab.automation.domain;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;

/**
 * Computes the next fire Instant for email reminder recurrence rules.
 */
public final class EmailReminderScheduleCalculator {

    public static final String FREQ_ONCE = "ONCE";
    public static final String FREQ_DAILY = "DAILY";
    public static final String FREQ_WEEKLY = "WEEKLY";
    public static final String FREQ_MONTHLY = "MONTHLY";
    public static final String FREQ_CUSTOM = "CUSTOM";

    private static final Set<String> FREQUENCIES = Set.of(
            FREQ_ONCE, FREQ_DAILY, FREQ_WEEKLY, FREQ_MONTHLY, FREQ_CUSTOM
    );

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private EmailReminderScheduleCalculator() {
    }

    public static String normalizeFrequency(String frequency) {
        if (frequency == null || frequency.isBlank()) {
            return FREQ_ONCE;
        }
        String normalized = frequency.strip().toUpperCase(Locale.ROOT);
        if (!FREQUENCIES.contains(normalized)) {
            throw new IllegalArgumentException("发送频率只支持 ONCE / DAILY / WEEKLY / MONTHLY / CUSTOM");
        }
        return normalized;
    }

    public static ZoneId resolveZone(String timezone) {
        String value = (timezone == null || timezone.isBlank()) ? "Asia/Shanghai" : timezone.strip();
        try {
            return ZoneId.of(value);
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException("时区无效：" + value);
        }
    }

    public static LocalTime parseSendTime(String sendTime) {
        if (sendTime == null || sendTime.isBlank()) {
            throw new IllegalArgumentException("请设置发送时间（HH:mm）");
        }
        try {
            return LocalTime.parse(sendTime.strip(), TIME_FORMAT);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("发送时间格式应为 HH:mm");
        }
    }

    public static Instant resolveFirstFire(
            String frequency,
            ZoneId zone,
            Instant onceScheduledAt,
            String sendTime,
            Integer recurrenceDay,
            Integer intervalDays,
            Instant now
    ) {
        String freq = normalizeFrequency(frequency);
        if (FREQ_ONCE.equals(freq)) {
            if (onceScheduledAt == null) {
                throw new IllegalArgumentException("请选择发送时间");
            }
            if (!onceScheduledAt.isAfter(now)) {
                throw new IllegalArgumentException("发送时间必须晚于当前时间");
            }
            return onceScheduledAt;
        }

        LocalTime time = parseSendTime(sendTime);
        return switch (freq) {
            case FREQ_DAILY -> nextDaily(zone, time, now);
            case FREQ_WEEKLY -> nextWeekly(zone, time, requireWeekday(recurrenceDay), now);
            case FREQ_MONTHLY -> nextMonthly(zone, time, requireMonthDay(recurrenceDay), now);
            case FREQ_CUSTOM -> {
                requireIntervalDays(intervalDays);
                yield nextDaily(zone, time, now);
            }
            default -> throw new IllegalArgumentException("不支持的发送频率");
        };
    }

    public static Instant resolveNextAfterSend(
            String frequency,
            ZoneId zone,
            String sendTime,
            Integer recurrenceDay,
            Integer intervalDays,
            Instant previousFire,
            Instant now
    ) {
        String freq = normalizeFrequency(frequency);
        if (FREQ_ONCE.equals(freq)) {
            return null;
        }
        LocalTime time = parseSendTime(sendTime);
        Instant anchor = previousFire == null ? now : previousFire;
        Instant candidate = switch (freq) {
            case FREQ_DAILY -> nextDaily(zone, time, anchor.plusSeconds(1));
            case FREQ_WEEKLY -> nextWeekly(zone, time, requireWeekday(recurrenceDay), anchor.plusSeconds(1));
            case FREQ_MONTHLY -> nextMonthly(zone, time, requireMonthDay(recurrenceDay), anchor.plusSeconds(1));
            case FREQ_CUSTOM -> {
                int days = requireIntervalDays(intervalDays);
                ZonedDateTime previousLocal = previousFire.atZone(zone);
                LocalDate nextDate = previousLocal.toLocalDate().plusDays(days);
                ZonedDateTime next = ZonedDateTime.of(nextDate, time, zone);
                while (!next.toInstant().isAfter(now)) {
                    next = next.plusDays(days);
                }
                yield next.toInstant();
            }
            default -> throw new IllegalArgumentException("不支持的发送频率");
        };
        return candidate;
    }

    private static int requireWeekday(Integer recurrenceDay) {
        if (recurrenceDay == null || recurrenceDay < 1 || recurrenceDay > 7) {
            throw new IllegalArgumentException("请选择每周几发送（1-7，周一到周日）");
        }
        return recurrenceDay;
    }

    private static int requireMonthDay(Integer recurrenceDay) {
        if (recurrenceDay == null || recurrenceDay < 1 || recurrenceDay > 31) {
            throw new IllegalArgumentException("请选择每月几号发送（1-31）");
        }
        return recurrenceDay;
    }

    private static int requireIntervalDays(Integer intervalDays) {
        if (intervalDays == null || intervalDays < 1 || intervalDays > 365) {
            throw new IllegalArgumentException("自定义间隔天数需在 1-365 之间");
        }
        return intervalDays;
    }

    private static Instant nextDaily(ZoneId zone, LocalTime time, Instant after) {
        ZonedDateTime local = after.atZone(zone);
        ZonedDateTime candidate = ZonedDateTime.of(local.toLocalDate(), time, zone);
        if (!candidate.toInstant().isAfter(after)) {
            candidate = candidate.plusDays(1);
        }
        return candidate.toInstant();
    }

    private static Instant nextWeekly(ZoneId zone, LocalTime time, int isoWeekday, Instant after) {
        DayOfWeek target = DayOfWeek.of(isoWeekday);
        ZonedDateTime local = after.atZone(zone);
        LocalDate date = local.toLocalDate();
        int delta = target.getValue() - date.getDayOfWeek().getValue();
        if (delta < 0) {
            delta += 7;
        }
        ZonedDateTime candidate = ZonedDateTime.of(date.plusDays(delta), time, zone);
        if (!candidate.toInstant().isAfter(after)) {
            candidate = candidate.plusWeeks(1);
        }
        return candidate.toInstant();
    }

    private static Instant nextMonthly(ZoneId zone, LocalTime time, int dayOfMonth, Instant after) {
        ZonedDateTime local = after.atZone(zone);
        LocalDate month = local.toLocalDate().withDayOfMonth(1);
        ZonedDateTime candidate = atMonthDay(month, dayOfMonth, time, zone);
        if (!candidate.toInstant().isAfter(after)) {
            candidate = atMonthDay(month.plusMonths(1), dayOfMonth, time, zone);
        }
        return candidate.toInstant();
    }

    private static ZonedDateTime atMonthDay(LocalDate monthStart, int dayOfMonth, LocalTime time, ZoneId zone) {
        int safeDay = Math.min(dayOfMonth, monthStart.lengthOfMonth());
        return ZonedDateTime.of(LocalDateTime.of(monthStart.withDayOfMonth(safeDay), time), zone);
    }
}

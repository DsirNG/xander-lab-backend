package com.xander.lab.automation.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailReminderScheduleCalculatorTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void nextDailySkipsPastTimeToday() {
        Instant now = Instant.parse("2026-07-25T08:00:00Z"); // 16:00 CST
        Instant next = EmailReminderScheduleCalculator.resolveFirstFire(
                "DAILY", SHANGHAI, null, "09:00", null, null, now
        );
        assertThat(next).isEqualTo(Instant.parse("2026-07-26T01:00:00Z"));
    }

    @Test
    void nextWeeklyUsesIsoWeekday() {
        // 2026-07-25 is Saturday; weekday 1 = Monday
        Instant now = Instant.parse("2026-07-25T02:00:00Z");
        Instant next = EmailReminderScheduleCalculator.resolveFirstFire(
                "WEEKLY", SHANGHAI, null, "10:00", 1, null, now
        );
        assertThat(next).isEqualTo(Instant.parse("2026-07-27T02:00:00Z"));
    }

    @Test
    void customEveryThreeDays() {
        Instant now = Instant.parse("2026-07-25T02:00:00Z"); // 10:00 CST
        Instant first = EmailReminderScheduleCalculator.resolveFirstFire(
                "CUSTOM", SHANGHAI, null, "09:00", null, 3, now
        );
        assertThat(first).isEqualTo(Instant.parse("2026-07-26T01:00:00Z"));
        Instant second = EmailReminderScheduleCalculator.resolveNextAfterSend(
                "CUSTOM", SHANGHAI, "09:00", null, 3, first, first
        );
        assertThat(second).isEqualTo(Instant.parse("2026-07-29T01:00:00Z"));
    }

    @Test
    void multiTimeResolvesEarliestOccurrenceAfterReference() {
        // 2026-07-25 08:00 UTC = 16:00 CST; times 09:00 / 13:30 / 17:45 CST:
        // 17:45 CST is still ahead today, so the earliest fire is today 09:45 UTC.
        Instant now = Instant.parse("2026-07-25T08:00:00Z");
        List<LocalTime> times = EmailReminderScheduleCalculator.parseSendTimes(
                List.of("13:30", "09:00", "17:45"));
        Instant next = EmailReminderScheduleCalculator.resolveNextDailyAfter(SHANGHAI, times, now);
        assertThat(next).isEqualTo(Instant.parse("2026-07-25T09:45:00Z"));
    }

    @Test
    void multiTimeRollsToNextDayWhenAllTimesPassed() {
        // 2026-07-25 10:00 UTC = 18:00 CST; all of 09:00/13:30/17:45 CST are past,
        // so the earliest next fire is tomorrow 09:00 CST = 01:00 UTC.
        Instant now = Instant.parse("2026-07-25T10:00:00Z");
        List<LocalTime> times = EmailReminderScheduleCalculator.parseSendTimes(
                List.of("13:30", "09:00", "17:45"));
        Instant next = EmailReminderScheduleCalculator.resolveNextDailyAfter(SHANGHAI, times, now);
        assertThat(next).isEqualTo(Instant.parse("2026-07-26T01:00:00Z"));
    }

    @Test
    void multiTimeChoosesLaterTimeSameDayWhenStillAhead() {
        // 08:00 UTC = 16:00 CST; 17:45 CST is still ahead today.
        Instant now = Instant.parse("2026-07-25T08:00:00Z");
        List<LocalTime> times = EmailReminderScheduleCalculator.parseSendTimes(List.of("09:00", "17:45"));
        Instant next = EmailReminderScheduleCalculator.resolveNextDailyAfter(SHANGHAI, times, now);
        assertThat(next).isEqualTo(Instant.parse("2026-07-25T09:45:00Z"));
    }

    @Test
    void multiTimeRejectsDuplicateTimes() {
        assertThat(EmailReminderScheduleCalculator.parseSendTimes(List.of("09:00", "09:00")))
                .hasSize(1);
        assertThatThrownBy(() -> EmailReminderScheduleCalculator.parseSendTimes(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmailReminderScheduleCalculator.parseSendTimes(
                List.of("09:00", "10:00", "11:00", "12:00", "13:00", "14:00")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

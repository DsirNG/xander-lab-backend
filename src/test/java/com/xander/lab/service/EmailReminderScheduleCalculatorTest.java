package com.xander.lab.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

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
}

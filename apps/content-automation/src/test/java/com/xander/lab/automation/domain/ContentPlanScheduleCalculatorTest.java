package com.xander.lab.automation.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Deterministic recurrence tests for content-owned scheduler recovery. */
class ContentPlanScheduleCalculatorTest {

    @Test
    void dailyUsesTheNextConfiguredLocalTime() {
        Instant next = ContentPlanScheduleCalculator.nextRun(
                "DAILY", "Asia/Shanghai", List.of("09:00", "18:00"), null, null,
                Instant.parse("2026-09-21T02:00:00Z"));

        assertThat(next).isEqualTo(Instant.parse("2026-09-21T10:00:00Z"));
    }

    @Test
    void weeklyUsesThePlanWeekdayAfterAStaleClaim() {
        Instant next = ContentPlanScheduleCalculator.nextRun(
                "WEEKLY", "Asia/Shanghai", List.of(), "09:00",
                LocalDate.of(2026, 9, 21), Instant.parse("2026-09-21T02:00:00Z"));

        assertThat(next).isEqualTo(Instant.parse("2026-09-28T01:00:00Z"));
    }

    @Test
    void rejectsCorruptTriggerTimeInsteadOfBusyLooping() {
        assertThatThrownBy(() -> ContentPlanScheduleCalculator.nextRun(
                "DAILY", "Asia/Shanghai", List.of("not-a-time"), null, null,
                Instant.parse("2026-09-21T02:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("触发时间格式");
    }
}

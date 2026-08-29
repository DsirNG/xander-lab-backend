package com.xander.lab.automation.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlogContentPlanScheduleCalculatorTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void oneShotUsesSelectedLocalDate() {
        Instant next = BlogContentPlanScheduleCalculator.firstRun(
                BlogContentPlan.SCHEDULE_ONCE, SHANGHAI, List.of(LocalTime.of(12, 0)),
                LocalDate.of(2026, 8, 30), Instant.parse("2026-08-29T00:00:00Z"));
        assertThat(next).isEqualTo(Instant.parse("2026-08-30T04:00:00Z"));
    }

    @Test
    void weeklyRepeatsOnTheSelectedWeekday() {
        BlogContentPlan plan = new BlogContentPlan();
        plan.setScheduleType(BlogContentPlan.SCHEDULE_WEEKLY);
        plan.setScheduledDate(LocalDate.of(2026, 8, 31)); // Monday
        plan.setTimezone("Asia/Shanghai");
        plan.setTriggerTime("09:00");
        Instant next = BlogContentPlanScheduleCalculator.nextRun(
                plan, Instant.parse("2026-08-31T02:00:00Z"));
        assertThat(next).isEqualTo(Instant.parse("2026-09-07T01:00:00Z"));
    }

    @Test
    void oneShotRejectsPastSelection() {
        assertThatThrownBy(() -> BlogContentPlanScheduleCalculator.firstRun(
                BlogContentPlan.SCHEDULE_ONCE, SHANGHAI, List.of(LocalTime.of(9, 0)),
                LocalDate.of(2026, 8, 28), Instant.parse("2026-08-29T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("晚于当前时间");
    }
}

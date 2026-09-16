package com.xander.lab.recitation.application;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class MasteryPolicyTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 8, 24, 10, 0);

    @Test
    void schedulesStableKnowledgeAfterThirtyDays() {
        MasteryPolicy.Result result = MasteryPolicy.evaluate(new BigDecimal("96.5"), now);
        assertThat(result.level()).isEqualTo("MASTERED");
        assertThat(result.nextReviewAt()).isEqualTo(now.plusDays(30));
    }

    @Test
    void schedulesWeakKnowledgeWithinTwelveHours() {
        MasteryPolicy.Result result = MasteryPolicy.evaluate(new BigDecimal("20"), now);
        assertThat(result.level()).isEqualTo("NEW");
        assertThat(result.nextReviewAt()).isEqualTo(now.plusHours(12));
    }
}

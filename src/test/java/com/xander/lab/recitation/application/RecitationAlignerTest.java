package com.xander.lab.recitation.application;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RecitationAlignerTest {
    private final RecitationAligner aligner = new RecitationAligner();

    @Test
    void ignoresPunctuationAndWhitespace() {
        RecitationAligner.AlignmentResult result = aligner.align("白日依山尽，黄河入海流。", "白日依山尽 黄河入海流");

        assertThat(result.score()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(result.differences()).isEmpty();
        assertThat(result.correctCount()).isEqualTo(10);
    }

    @Test
    void reportsMissingWrongAndExtraCharacters() {
        RecitationAligner.AlignmentResult result = aligner.align("天地玄黄", "天玄红多");

        assertThat(result.missingCount()).isEqualTo(1);
        assertThat(result.wrongCount()).isEqualTo(1);
        assertThat(result.extraCount()).isEqualTo(1);
        assertThat(result.differences()).extracting(RecitationAligner.Difference::type)
                .containsExactly("MISSING", "WRONG", "EXTRA");
        assertThat(result.score()).isEqualByComparingTo(new BigDecimal("37.50"));
    }
}

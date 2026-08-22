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

    @Test
    void groupsAdjacentWrongCharactersIntoOneDifference() {
        RecitationAligner.AlignmentResult result = aligner.align("天地玄黄宇宙洪荒", "天地红绿宇宙洪荒");

        assertThat(result.wrongCount()).isEqualTo(2);
        assertThat(result.differences()).hasSize(1);
        assertThat(result.differences().get(0).type()).isEqualTo("WRONG");
        assertThat(result.differences().get(0).expected()).isEqualTo("玄黄");
        assertThat(result.differences().get(0).actual()).isEqualTo("红绿");
        assertThat(result.differences().get(0).expectedIndex()).isEqualTo(2);
        assertThat(result.differences().get(0).contextBefore()).isEqualTo("天地");
        assertThat(result.differences().get(0).contextAfter()).isEqualTo("宇宙洪荒");
    }

    @Test
    void reportsTheWholeUnspokenTailAsOneOmission() {
        RecitationAligner.AlignmentResult result = aligner.align("白日依山尽黄河入海流欲穷千里目更上一层楼", "白日依山尽黄河入海流");

        assertThat(result.missingCount()).isEqualTo(10);
        assertThat(result.differences()).hasSize(1);
        RecitationAligner.Difference omission = result.differences().get(0);
        assertThat(omission.expected()).isEqualTo("欲穷千里目更上一层楼");
        assertThat(omission.expectedIndex()).isEqualTo(10);
        assertThat(omission.tailOmission()).isTrue();
        assertThat(omission.contextBefore()).isEqualTo("依山尽黄河入海流");
        assertThat(omission.contextAfter()).isEmpty();
    }
}

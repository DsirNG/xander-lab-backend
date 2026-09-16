package com.xander.lab.agent.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文压力估算的边界。
 *
 * <p>它只需要支撑"是不是快满了"这一个判断，所以这里钉住的是比例关系和不塌陷，
 * 不是与某个分词器逐 token 对齐。</p>
 */
class AgentTokenEstimatorTest {

    @Test
    void emptyTextCostsNothingButAnEmptyTurnStillCostsItsWrapper() {
        assertThat(AgentTokenEstimator.estimateText(null)).isZero();
        assertThat(AgentTokenEstimator.estimateText("")).isZero();
        // 一轮空消息在 chat 模板里仍然要写 role 和分隔符，按 0 算会让估算系统性偏低。
        assertThat(AgentTokenEstimator.estimateTurn("")).isEqualTo(4);
    }

    @Test
    void cjkCountsOneTokenPerCharacterAndLatinRoughlyOnePerFourCharacters() {
        assertThat(AgentTokenEstimator.estimateText("上下文压缩")).isEqualTo(5);
        assertThat(AgentTokenEstimator.estimateText("abcdefgh")).isEqualTo(2);
        assertThat(AgentTokenEstimator.estimateText("上下文 compaction"))
                .isEqualTo(3 + (11 + 3) / 4);
    }

    /** 窄字符向上取整：一个三字母短词也要算一个 token，不能算 0，否则长文本会被系统性低估。 */
    @Test
    void shortNarrowRunsRoundUpInsteadOfVanishing() {
        assertThat(AgentTokenEstimator.estimateText("a")).isEqualTo(1);
        assertThat(AgentTokenEstimator.estimateText("abc")).isEqualTo(1);
        assertThat(AgentTokenEstimator.estimateText("abcde")).isEqualTo(2);
    }

    @Test
    void fullwidthPunctuationAndKanaCountAsWideCharacters() {
        assertThat(AgentTokenEstimator.estimateText("、。！")).isEqualTo(3);
        assertThat(AgentTokenEstimator.estimateText("ひらがな")).isEqualTo(4);
        assertThat(AgentTokenEstimator.estimateText("ＡＢ")).isEqualTo(2);
    }

    @Test
    void turnsAreSummedWithTheirOwnWrapperOverheadAndNullIsTreatedAsEmpty() {
        assertThat(AgentTokenEstimator.estimateTurns(null)).isZero();
        assertThat(AgentTokenEstimator.estimateTurns(List.of(
                AgentInputTurn.user("上下文压缩"),
                AgentInputTurn.assistant("abcdefgh"))))
                .isEqualTo((4 + 5) + (4 + 2));
    }
}

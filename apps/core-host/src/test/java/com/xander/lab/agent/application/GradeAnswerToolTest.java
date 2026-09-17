package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.recitation.spi.GradedItemDraft;
import com.xander.lab.recitation.spi.QuizOutcomeView;
import com.xander.lab.recitation.spi.RecitationQuizPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * mock 的是<b>契约端口</b>，不是 {@code KnowledgeQuizService} ——
 * 这本身就是"工具只依赖端口"的证明。
 */
class GradeAnswerToolTest {

    private final RecitationQuizPort quizPort = mock(RecitationQuizPort.class);
    private final GradeAnswerTool tool = new GradeAnswerTool(quizPort);
    private final AgentToolContext context = new AgentToolContext(42L, 7L, 3L);

    @Test
    void recordsThePerQuestionEvidenceAndReportsWhereMasteryLanded() {
        when(quizPort.recordQuiz(eq(42L), eq(55L), eq(7L), eq("推导那题没答上"), any()))
                .thenReturn(outcome(new BigDecimal("50.00"), "FAMILIAR", "NEW"));

        JsonNode result = tool.execute(context, args(), new NoopProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("attemptId").asLong()).isEqualTo(77L);
        assertThat(result.path("score").asDouble()).isEqualTo(50.0);
        assertThat(result.path("questionCount").asInt()).isEqualTo(2);
        assertThat(result.path("correctCount").asInt()).isEqualTo(1);
        assertThat(result.path("masteryLevel").asText()).isEqualTo("FAMILIAR");
        assertThat(result.path("previousMasteryLevel").asText()).isEqualTo("NEW");
        assertThat(result.path("masteryChanged").asBoolean()).isTrue();
        assertThat(result.path("reviewCount").asInt()).isEqualTo(3);
        assertThat(result.path("knowledgeUrl").asText()).isEqualTo("/workspace/knowledge/9");
        assertThat(result.path("hint")).asString().contains("不要在回复里改写它").contains("别只报个分数");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GradedItemDraft>> items = ArgumentCaptor.forClass(List.class);
        verify(quizPort).recordQuiz(eq(42L), eq(55L), eq(7L), eq("推导那题没答上"), items.capture());
        assertThat(items.getValue()).hasSize(2);
        assertThat(items.getValue().get(0).question()).isEqualTo("和角公式怎么写");
        assertThat(items.getValue().get(0).userAnswer()).isEqualTo("sin a cos b + cos a sin b");
        assertThat(items.getValue().get(0).credit()).isEqualByComparingTo("1");
        assertThat(items.getValue().get(1).credit()).isEqualByComparingTo("0.5");
        assertThat(items.getValue().get(1).comment()).isEqualTo("方向对了但写错了符号");
    }

    @Test
    void anUnchangedLevelIsReportedAsUnchangedSoTheAgentDoesNotCongratulateProgressThatDidNotHappen() {
        when(quizPort.recordQuiz(any(), any(), any(), any(), any()))
                .thenReturn(outcome(new BigDecimal("30.00"), "FAMILIAR", "FAMILIAR"));

        JsonNode result = tool.execute(context, args(), new NoopProgress());

        assertThat(result.path("masteryChanged").asBoolean()).isFalse();
    }

    @Test
    void acceptsAPlainCorrectFlagBecauseModelsOftenGradeInRightOrWrongTerms() {
        when(quizPort.recordQuiz(any(), any(), any(), any(), any()))
                .thenReturn(outcome(new BigDecimal("50.00"), "FAMILIAR", "NEW"));
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("quizSessionId", 55L);
        ArrayNode items = args.putArray("items");
        items.addObject().put("question", "答对的").put("correct", true);
        items.addObject().put("question", "答错的").put("correct", false);
        items.addObject().put("question", "用字符串给分的").put("credit", "0.5");

        tool.execute(context, args, new NoopProgress());

        assertThat(captureItems()).extracting(GradedItemDraft::credit)
                .containsExactly(BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("0.5"));
    }

    @Test
    void leavesAnUngradedQuestionNullSoTheServiceRefusesInsteadOfTheToolGuessingAZero() {
        when(quizPort.recordQuiz(any(), any(), any(), any(), any()))
                .thenReturn(outcome(new BigDecimal("0.00"), "NEW", "NEW"));
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("quizSessionId", 55L);
        args.putArray("items").addObject().put("question", "没判分的题");

        tool.execute(context, args, new NoopProgress());

        assertThat(captureItems()).singleElement()
                .satisfies(item -> assertThat(item.credit()).isNull());
    }

    @Test
    void gradesWithoutAConversationTooSoTheToolStaysUsableOutsideTheChatLoop() {
        when(quizPort.recordQuiz(eq(42L), eq(55L), isNull(), any(), any()))
                .thenReturn(outcome(new BigDecimal("50.00"), "FAMILIAR", "NEW"));

        JsonNode result = tool.execute(42L, args(), new NoopProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        verify(quizPort).recordQuiz(eq(42L), eq(55L), isNull(), any(), any());
    }

    @Test
    void stopsBeforeWritingAnythingWhenTheUserAlreadyCancelled() {
        assertThatThrownBy(() -> tool.execute(context, args(), new CancelledProgress()))
                .isInstanceOf(CancellationException.class);
        verifyNoInteractions(quizPort);
    }

    @Test
    void theSchemaDemandsPerQuestionJudgementsAndTheDescriptionForbidsReportingATotal() {
        JsonNode schema = tool.parametersSchema();

        assertThat(schema.path("required").toString()).isEqualTo("[\"quizSessionId\",\"items\"]");
        assertThat(schema.path("properties").path("items").path("items").path("required").toString())
                .isEqualTo("[\"question\",\"credit\"]");
        assertThat(schema.path("properties").path("items").path("items")
                .path("properties").path("credit").path("type").asText()).isEqualTo("number");
        assertThat(tool.name()).isEqualTo(GradeAnswerTool.NAME);
        assertThat(tool.description())
                .contains("不要自己报总分").contains("判分要严格").contains("用户的原话");
    }

    /**
     * 上限常量现在住在契约端口上。schema 里的数字必须跟着端口走 ——
     * 域侧把 20 改成 10 而契约没改时，模型收到的指令就与真正会被接受的东西不一致。
     */
    @Test
    void theSchemaLimitsComeFromThePortSoTheyCannotDriftAwayFromWhatTheProviderAccepts() {
        JsonNode schema = tool.parametersSchema();

        assertThat(schema.path("properties").path("items").path("description").asText())
                .contains(String.valueOf(RecitationQuizPort.MAX_ITEMS));
        assertThat(schema.path("properties").path("verdict").path("description").asText())
                .contains(String.valueOf(RecitationQuizPort.MAX_VERDICT_CHARS));
    }

    private List<GradedItemDraft> captureItems() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GradedItemDraft>> items = ArgumentCaptor.forClass(List.class);
        verify(quizPort).recordQuiz(any(), any(), any(), any(), items.capture());
        return items.getValue();
    }

    private ObjectNode args() {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("quizSessionId", 55L);
        args.put("verdict", "推导那题没答上");
        ArrayNode items = args.putArray("items");
        items.addObject().put("question", "和角公式怎么写")
                .put("userAnswer", "sin a cos b + cos a sin b").put("credit", 1);
        items.addObject().put("question", "怎么推导").put("userAnswer", "画单位圆…")
                .put("credit", 0.5).put("comment", "方向对了但写错了符号");
        return args;
    }

    private QuizOutcomeView outcome(BigDecimal score, String level, String previousLevel) {
        return new QuizOutcomeView(77L, 9L, "三角函数和角公式", score, 2, 1,
                level, previousLevel, 3, LocalDateTime.now().plusDays(1));
    }

    private static final class NoopProgress implements AgentTool.ProgressListener {
        @Override
        public void onProgress(String message) {
        }
    }

    private static final class CancelledProgress implements AgentTool.ProgressListener {
        @Override
        public void onProgress(String message) {
        }

        @Override
        public boolean isCancellationRequested() {
            return true;
        }
    }
}

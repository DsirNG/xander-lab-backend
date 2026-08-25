package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.recitation.api.KnowledgeQuizAttemptView;
import com.xander.lab.recitation.application.KnowledgeQuizService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QuizKnowledgeToolTest {

    private final KnowledgeQuizService quizService = mock(KnowledgeQuizService.class);
    private final QuizKnowledgeTool tool = new QuizKnowledgeTool(quizService, new ObjectMapper());

    @Test
    void handsTheAgentTheOriginalTextTheMasteryPictureAndLastTimesQuestions() {
        when(quizService.openQuiz(42L, 9L)).thenReturn(session("sin(a+b) = ...", false,
                List.of(quiz("上次问的题", "忘了"))));

        JsonNode result = tool.execute(42L, args(9L), new NoopProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("materialId").asLong()).isEqualTo(9L);
        assertThat(result.path("title").asText()).isEqualTo("三角函数和角公式");
        assertThat(result.path("content").asText()).isEqualTo("sin(a+b) = ...");
        assertThat(result.path("contentTruncated").asBoolean()).isFalse();
        assertThat(result.path("masteryLevel").asText()).isEqualTo("FAMILIAR");
        assertThat(result.path("dueForReview").asBoolean()).isTrue();
        assertThat(result.path("knowledgeUrl").asText()).isEqualTo("/workspace/knowledge/9");
        assertThat(result.path("recentQuizzes").path(0).path("score").asDouble()).isEqualTo(60.0);
        // 历史逐题证据必须是真的 JSON，而不是某个对象的 toString。
        assertThat(result.path("recentQuizzes").path(0).path("items").path(0).path("question").asText())
                .isEqualTo("上次问的题");
        assertThat(result.path("hint")).asString()
                .contains("上次答错").contains("先别给答案").contains("grade_answer");
    }

    @Test
    void saysSoWhenTheTextWasTruncatedSoTheAgentDoesNotAskAboutWhatItCannotSee() {
        when(quizService.openQuiz(42L, 9L)).thenReturn(session("很长的正文…", true, List.of()));

        JsonNode result = tool.execute(42L, args(9L), new NoopProgress());

        assertThat(result.path("contentTruncated").asBoolean()).isTrue();
        assertThat(result.path("hint")).asString()
                .contains("只考你看到的部分")
                .contains("还没有测验记录");
        assertThat(result.path("recentQuizzes")).isEmpty();
    }

    @Test
    void aMissingMaterialIdStaysNullInsteadOfSilentlyBecomingZero() {
        when(quizService.openQuiz(eq(42L), isNull())).thenReturn(session("正文", false, List.of()));

        tool.execute(42L, JsonNodeFactory.instance.objectNode().put("materialId", "九"), new NoopProgress());

        verify(quizService).openQuiz(42L, null);
    }

    @Test
    void stopsBeforeReadingAnythingWhenTheUserAlreadyCancelled() {
        assertThatThrownBy(() -> tool.execute(42L, args(9L), new CancelledProgress()))
                .isInstanceOf(CancellationException.class);
        verifyNoInteractions(quizService);
    }

    @Test
    void openingAQuizNeverWritesAnythingItselfSoAbandonedTestsCostNoMastery() {
        when(quizService.openQuiz(any(), any())).thenReturn(session("正文", false, List.of()));

        tool.execute(42L, args(9L), new NoopProgress());

        verify(quizService, never()).recordQuiz(any(), any(), any(), any(), any());
    }

    @Test
    void theSchemaAsksOnlyForTheIdAndTheDescriptionTellsTheAgentToGradeAfterwards() {
        JsonNode schema = tool.parametersSchema();

        assertThat(schema.path("required").toString()).isEqualTo("[\"materialId\"]");
        assertThat(schema.path("properties").path("materialId").path("type").asText()).isEqualTo("integer");
        assertThat(tool.name()).isEqualTo(QuizKnowledgeTool.NAME);
        assertThat(tool.description())
                .contains("考考我").contains("query_knowledge").contains("grade_answer");
    }

    private ObjectNode args(long materialId) {
        return JsonNodeFactory.instance.objectNode().put("materialId", materialId);
    }

    private KnowledgeQuizService.QuizSession session(String content, boolean truncated,
                                                     List<KnowledgeQuizAttemptView> history) {
        return new KnowledgeQuizService.QuizSession(9L, "三角函数和角公式", "CONCEPT", "AI_QA",
                content, truncated, content.length(), new BigDecimal("40.00"), "FAMILIAR", 2,
                LocalDateTime.now().minusDays(5), LocalDateTime.now().minusHours(1), true, history);
    }

    private KnowledgeQuizAttemptView quiz(String question, String answer) {
        return new KnowledgeQuizAttemptView(5L, 9L, "AI_QA", 2, 1, new BigDecimal("60.00"), "上次没答上",
                List.of(Map.of("question", question, "userAnswer", answer, "credit", 0)),
                LocalDateTime.now().minusDays(5));
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

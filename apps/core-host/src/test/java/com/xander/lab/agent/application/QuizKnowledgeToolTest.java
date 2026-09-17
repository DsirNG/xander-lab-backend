package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.recitation.spi.QuizAttemptView;
import com.xander.lab.recitation.spi.QuizSessionView;
import com.xander.lab.recitation.spi.RecitationQuizPort;
import org.junit.jupiter.api.Test;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 这个测试 mock 的是<b>契约端口</b>而不是 {@code KnowledgeQuizService} ——
 * 这本身就是"工具只需要端口"的证明：mock 端口的测试通过，说明工具没有用到底层服务上
 * 端口之外的东西。
 */
class QuizKnowledgeToolTest {

    private final RecitationQuizPort quizPort = mock(RecitationQuizPort.class);
    private final QuizKnowledgeTool tool = new QuizKnowledgeTool(quizPort, new ObjectMapper());

    @Test
    void handsTheAgentTheOriginalTextTheMasteryPictureAndLastTimesQuestions() {
        when(quizPort.openQuiz(42L, 9L, null)).thenReturn(session("sin(a+b) = ...", false,
                List.of(quiz("上次问的题", "忘了"))));

        JsonNode result = tool.execute(42L, args(9L), new NoopProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("quizSessionId").asLong()).isEqualTo(55L);
        assertThat(result.path("materialId").asLong()).isEqualTo(9L);
        assertThat(result.path("title").asText()).isEqualTo("三角函数和角公式");
        assertThat(result.path("content").asText()).isEqualTo("sin(a+b) = ...");
        assertThat(result.path("contentTruncated").asBoolean()).isFalse();
        assertThat(result.path("masteryLevel").asText()).isEqualTo("FAMILIAR");
        assertThat(result.path("dueForReview").asBoolean()).isTrue();
        assertThat(result.path("knowledgeUrl").asText()).isEqualTo("/workspace/knowledge/9");
        assertThat(result.path("recentQuizzes").path(0).path("score").asDouble()).isEqualTo(60.0);
        // 历史逐题证据必须是真的 JSON 结构，而不是某个对象的 toString。
        // ⚠️ 契约侧把这一列收窄成了 itemsJson 文本（域侧字段是 Object，没有形状），
        // 于是"文本 → 结构"这一步现在发生在工具里 —— 断言必须落在解析后的结构上，
        // 否则解析写坏了（例如把整串当标量塞进去）不会有任何测试变红。
        assertThat(result.path("recentQuizzes").path(0).path("items").path(0).path("question").asText())
                .isEqualTo("上次问的题");
        assertThat(result.path("hint")).asString()
                .contains("上次答错").contains("先别给答案").contains("grade_answer")
                // 出题这一轮必须收口等用户作答：否则模型会返回没有工具的决策把整轮卡死。
                .contains("done=true");
    }

    @Test
    void aHistoryRowWithUnparseableItemsStillYieldsAnEmptyArrayInsteadOfFailingTheWholeTool() {
        when(quizPort.openQuiz(42L, 9L, null)).thenReturn(session("正文", false,
                List.of(new QuizAttemptView(2, 1, new BigDecimal("60.00"), "上次没答上",
                        "{不是合法 JSON", LocalDateTime.now()))));

        JsonNode result = tool.execute(42L, args(9L), new NoopProgress());

        // 单条历史坏掉不能把整场测验带下水：出题这一轮的价值远大于历史证据。
        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("recentQuizzes").path(0).path("items")).isEmpty();
    }

    @Test
    void aHistoryRowWithNullItemsJsonYieldsAnEmptyArrayRatherThanANullNode() {
        when(quizPort.openQuiz(42L, 9L, null)).thenReturn(session("正文", false,
                List.of(new QuizAttemptView(2, 1, new BigDecimal("60.00"), "没留证据",
                        null, LocalDateTime.now()))));

        JsonNode result = tool.execute(42L, args(9L), new NoopProgress());

        // 必须是数组而不是 null：下游的 .path("items").path(0) 在 null 上会静默取不到东西。
        assertThat(result.path("recentQuizzes").path(0).path("items").isArray()).isTrue();
        assertThat(result.path("recentQuizzes").path(0).path("items")).isEmpty();
    }

    @Test
    void saysSoWhenTheTextWasTruncatedSoTheAgentDoesNotAskAboutWhatItCannotSee() {
        when(quizPort.openQuiz(42L, 9L, null)).thenReturn(session("很长的正文…", true, List.of()));

        JsonNode result = tool.execute(42L, args(9L), new NoopProgress());

        assertThat(result.path("contentTruncated").asBoolean()).isTrue();
        assertThat(result.path("hint")).asString()
                .contains("只考你看到的部分")
                .contains("还没有测验记录");
        assertThat(result.path("recentQuizzes")).isEmpty();
    }

    @Test
    void aMissingMaterialIdStaysNullInsteadOfSilentlyBecomingZero() {
        when(quizPort.openQuiz(eq(42L), isNull(), isNull())).thenReturn(session("正文", false, List.of()));

        tool.execute(42L, JsonNodeFactory.instance.objectNode().put("materialId", "九"), new NoopProgress());

        verify(quizPort).openQuiz(42L, null, null);
    }

    @Test
    void stopsBeforeReadingAnythingWhenTheUserAlreadyCancelled() {
        assertThatThrownBy(() -> tool.execute(42L, args(9L), new CancelledProgress()))
                .isInstanceOf(CancellationException.class);
        verifyNoInteractions(quizPort);
    }

    @Test
    void openingAQuizNeverWritesAnythingItselfSoAbandonedTestsCostNoMastery() {
        when(quizPort.openQuiz(any(), any(), any())).thenReturn(session("正文", false, List.of()));

        tool.execute(42L, args(9L), new NoopProgress());

        verify(quizPort, never()).recordQuiz(any(), any(), any(), any(), any());
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

    private QuizSessionView session(String content, boolean truncated, List<QuizAttemptView> history) {
        return new QuizSessionView(55L, 9L, "三角函数和角公式", "CONCEPT", "AI_QA",
                content, truncated, content.length(), new BigDecimal("40.00"), "FAMILIAR", 2,
                LocalDateTime.now().minusDays(5), LocalDateTime.now().minusHours(1), true, history);
    }

    /** 历史行的逐题证据在契约里是文本，所以这里给的就是域侧真正落库的那种 JSON 字符串。 */
    private QuizAttemptView quiz(String question, String answer) {
        String itemsJson = "[{\"question\":\"" + question + "\",\"userAnswer\":\"" + answer + "\","
                + "\"comment\":\"\",\"credit\":0}]";
        return new QuizAttemptView(2, 1, new BigDecimal("60.00"), "上次没答上",
                itemsJson, LocalDateTime.now().minusDays(5));
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

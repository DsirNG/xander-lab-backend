package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmitQuizToolTest {

    private final EmitQuizTool tool = new EmitQuizTool();

    @Test
    void bindsTheCardToTheQuizSessionSoGradingCanFindItLater() {
        JsonNode result = tool.execute(new AgentToolContext(42L, 7L, 3L),
                args(99L, "红楼梦第一回", question("作者是谁？", "single", "曹雪芹", "高鹗")),
                new NoopProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("quizSessionId").asLong()).isEqualTo(99L);
        assertThat(result.path("questionCount").asInt()).isEqualTo(1);
        JsonNode quiz = result.path(EmitQuizTool.QUIZ_FIELD);
        assertThat(quiz.path("type").asText()).isEqualTo(AgentQuizService.PAYLOAD_TYPE);
        // 卡片 id 就是测验会话 id：用户提交时原样带回，判分不必再做一次映射。
        assertThat(quiz.path("id").asText()).isEqualTo("99");
        assertThat(quiz.path("title").asText()).isEqualTo("红楼梦第一回");
        JsonNode first = quiz.path("questions").get(0);
        assertThat(first.path("id").asText()).isEqualTo("q1");
        assertThat(first.path("type").asText()).isEqualTo("single");
        assertThat(first.path("options").toString()).isEqualTo("[\"曹雪芹\",\"高鹗\"]");
        assertThat(result.path("hint").asText()).contains("quizSessionId=99");
    }

    @Test
    void aChoiceQuestionWithoutEnoughOptionsBecomesAnOpenQuestion() {
        ObjectNode args = args(99L, "", question("默写第一句", "single", "只有一个选项"));

        JsonNode quiz = tool.execute(new AgentToolContext(42L, 7L, 3L), args, new NoopProgress())
                .path(EmitQuizTool.QUIZ_FIELD);

        JsonNode only = quiz.path("questions").get(0);
        assertThat(only.path("type").asText()).isEqualTo("text");
        assertThat(only.path("options")).isEmpty();
    }

    @Test
    void emitsAnAdHocCardWithoutAKnowledgeQuizSession() {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.putArray("questions").add(question("sin 90° 等于多少？", "single", "0", "1"));

        JsonNode result = tool.execute(new AgentToolContext(42L, 7L, 3L), args, new NoopProgress());

        assertThat(result.has("quizSessionId")).isFalse();
        assertThat(result.path(EmitQuizTool.QUIZ_FIELD).path("id").asText()).isEqualTo("adhoc-7-3");
    }

    @Test
    void keepsAtMostTenQuestionsSoTheCardStaysAnswerable() {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("quizSessionId", 99L);
        ArrayNode questions = args.putArray("questions");
        for (int index = 0; index < EmitQuizTool.MAX_QUESTIONS + 5; index++) {
            questions.add(question("第 " + index + " 题", "text"));
        }

        JsonNode result = tool.execute(new AgentToolContext(42L, 7L, 3L), args, new NoopProgress());

        assertThat(result.path("questionCount").asInt()).isEqualTo(EmitQuizTool.MAX_QUESTIONS);
    }

    @Test
    void refusesACardThatCouldNotBeRendered() {
        assertThatThrownBy(() -> tool.execute(new AgentToolContext(42L, 7L, 3L),
                JsonNodeFactory.instance.objectNode().put("quizSessionId", 99L), new NoopProgress()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("questions");
        assertThatThrownBy(() -> tool.execute(new AgentToolContext(42L, 7L, 3L),
                args(99L, "", question("   ", "text")), new NoopProgress()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prompt");
    }

    @Test
    void refusesToRunWithoutAConversationBecauseACardNeedsSomewhereToLand() {
        ObjectNode args = args(99L, "", question("作者是谁？", "text"));
        assertThatThrownBy(() -> tool.execute(42L, args, new NoopProgress()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须在会话上下文中执行");
        assertThatThrownBy(() -> tool.execute(AgentToolContext.ofUser(42L), args, new NoopProgress()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须在会话上下文中执行");
    }

    @Test
    void schemaMakesTheSessionIdOptionalAndAdvertisesTheThreeQuestionTypes() {
        JsonNode schema = tool.parametersSchema();

        assertThat(schema.path("required").toString()).isEqualTo("[\"questions\"]");
        JsonNode question = schema.path("properties").path("questions").path("items");
        assertThat(question.path("required").toString()).isEqualTo("[\"prompt\",\"type\"]");
        assertThat(question.path("properties").path("type").path("enum").toString())
                .isEqualTo("[\"single\",\"multiple\",\"text\"]");
        assertThat(tool.name()).isEqualTo(EmitQuizTool.NAME);
        assertThat(tool.description()).contains("答题卡");
    }

    private ObjectNode args(long quizSessionId, String title, ObjectNode... questions) {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("quizSessionId", quizSessionId);
        args.put("title", title);
        ArrayNode target = args.putArray("questions");
        for (ObjectNode question : questions) {
            target.add(question);
        }
        return args;
    }

    private ObjectNode question(String prompt, String type, String... options) {
        ObjectNode question = JsonNodeFactory.instance.objectNode();
        question.put("prompt", prompt);
        question.put("type", type);
        ArrayNode target = question.putArray("options");
        for (String option : options) {
            target.add(option);
        }
        return question;
    }

    private static final class NoopProgress implements AgentTool.ProgressListener {
        @Override
        public void onProgress(String message) {
        }
    }
}

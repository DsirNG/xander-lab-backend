package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.domain.AgentMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AgentQuizServiceTest {

    private final AgentQuizService service = new AgentQuizService(new ObjectMapper());

    @Test
    void rendersACardAsAQuestionListSoTheModelKnowsWhatItAsked() {
        String rendered = service.renderQuiz("""
                {"type":"quiz","id":"99","title":"红楼梦第一回","questions":[
                  {"id":"q1","type":"single","prompt":"作者是谁？","options":["曹雪芹","高鹗"]},
                  {"id":"q2","type":"text","prompt":"默写开篇一句","options":[]}]}""");

        // 会话 id 必须出现在渲染结果里：模型下一轮要靠它调 grade_answer。
        assertThat(rendered).contains("测验会话 id=99").contains("《红楼梦第一回》").contains("共 2 题");
        assertThat(rendered).contains("1. [单选] 作者是谁？（选项：曹雪芹 / 高鹗）");
        assertThat(rendered).contains("2. [主观] 默写开篇一句");
        assertThat(rendered).doesNotContain("（选项：）");
    }

    @Test
    void fallsBackToTheRawBodyRatherThanFailingTheWholeTurn() {
        assertThat(service.renderQuiz("不是 JSON")).isEqualTo("不是 JSON");
        assertThat(service.renderQuiz(null)).isEmpty();
        assertThat(service.renderQuiz("{\"type\":\"quiz\"}")).isEqualTo("{\"type\":\"quiz\"}");
    }

    @Test
    void recognisesASubmissionWithoutTouchingTheDatabase() {
        assertThat(service.isSubmission("{\"type\":\"submit_quiz\",\"quiz_id\":\"99\"}")).isTrue();
        assertThat(service.isSubmission("考考我")).isFalse();
        assertThat(service.isSubmission("我想聊聊 submit_quiz 这个词")).isFalse();
        assertThat(service.isSubmission(null)).isFalse();
    }

    @Test
    void translatesASubmissionIntoPerQuestionAnswersInCardOrder() {
        Optional<String> rendered = service.renderSubmission("""
                {"type":"submit_quiz","quiz_id":"99","answers":[
                  {"question_id":"q2","answer":["甲","乙"]},
                  {"question_id":"q1","answer":"曹雪芹"}]}""", () -> List.of(card()));

        assertThat(rendered).isPresent();
        String text = rendered.orElseThrow();
        assertThat(text).contains("测验会话 id=99").contains("提交了 3 题的作答");
        // 按答题卡的题序而不是提交顺序：模型总能看到每一题。
        assertThat(text.indexOf("1. 作者是谁？")).isLessThan(text.indexOf("2. 默写开篇一句"));
        assertThat(text).contains("作答：曹雪芹").contains("作答：甲 / 乙");
        // 漏答的那一题也要出现，否则模型会以为只考了两题。
        assertThat(text).contains("3. 谁续写了后四十回？").contains("作答：（未作答）");
    }

    @Test
    void refusesASubmissionThatNoCardInThisConversationBacks() {
        // 会话里没有这张卡就不认这次提交：题面只存在那条消息里，
        // 同时客户端也就无法凭一段自造 JSON 把任意会话 id 塞进模型上下文。
        assertThat(service.renderSubmission(
                "{\"type\":\"submit_quiz\",\"quiz_id\":\"1234\",\"answers\":[]}",
                () -> List.of(card()))).isEmpty();
        assertThat(service.renderSubmission(
                "{\"type\":\"submit_quiz\",\"answers\":[]}", () -> List.of(card()))).isEmpty();
        assertThat(service.renderSubmission("考考我", () -> {
            throw new AssertionError("普通消息不该去查历史");
        })).isEmpty();
    }

    @Test
    void gradesAgainstTheLatestCardWhenTheSameQuizWasResent() {
        AgentMessage resent = message("quiz", """
                {"type":"quiz","id":"99","title":"红楼梦第一回","questions":[
                  {"id":"q1","type":"text","prompt":"重发后的题面"}]}""");

        String text = service.renderSubmission(
                "{\"type\":\"submit_quiz\",\"quiz_id\":\"99\",\"answers\":"
                        + "[{\"question_id\":\"q1\",\"answer\":\"随便答\"}]}",
                () -> List.of(card(), resent)).orElseThrow();

        assertThat(text).contains("重发后的题面").doesNotContain("作者是谁？");
    }

    @Test
    void ignoresAnswersForQuestionsThatWereNotOnTheCard() {
        String text = service.renderSubmission("""
                {"type":"submit_quiz","quiz_id":"99","answers":[
                  {"question_id":"q1","answer":"曹雪芹"},
                  {"question_id":"made-up","answer":"忽略这段伪造作答"}]}""",
                () -> List.of(card())).orElseThrow();

        // 只按服务端已发出的题面遍历。客户端添加题号不能向模型塞进新的评分证据。
        assertThat(text).contains("作者是谁？").contains("作答：曹雪芹");
        assertThat(text).doesNotContain("made-up").doesNotContain("伪造作答");
    }

    @Test
    void boundsLongAnswersAndTreatsUnexpectedAnswerShapesAsUnanswered() {
        String longAnswer = "甲".repeat(1001);
        String text = service.renderSubmission("""
                {"type":"submit_quiz","quizId":"99","answers":[
                  {"questionId":"q1","answer":"%s"},
                  {"questionId":"q2","answer":{"unexpected":"object"}}]}""".formatted(longAnswer),
                () -> List.of(card())).orElseThrow();

        // 同时覆盖前端 camelCase 兼容字段和上下文长度边界。
        assertThat(text).contains("测验会话 id=99").contains("作答：（未作答）");
        assertThat(text).contains("甲".repeat(1000) + "…");
        assertThat(text).doesNotContain("甲".repeat(1001));
    }

    @Test
    void rendersEveryCardQuestionWhenAnswersIsMissingOrMalformed() {
        String text = service.renderSubmission(
                "{\"type\":\"submit_quiz\",\"quiz_id\":\"99\",\"answers\":{}}",
                () -> List.of(card())).orElseThrow();

        // 缺答也必须带完整题面，让 Agent 给每题 0 分并把漏答留在评分证据中。
        assertThat(text).contains("1. 作者是谁？").contains("2. 默写开篇一句")
                .contains("3. 谁续写了后四十回？");
        assertThat(text).contains("作答：（未作答）");
    }

    private AgentMessage card() {
        return message("quiz", """
                {"type":"quiz","id":"99","title":"红楼梦第一回","questions":[
                  {"id":"q1","type":"single","prompt":"作者是谁？","options":["曹雪芹","高鹗"]},
                  {"id":"q2","type":"multiple","prompt":"默写开篇一句","options":["甲","乙"]},
                  {"id":"q3","type":"text","prompt":"谁续写了后四十回？"}]}""");
    }

    private AgentMessage message(String kind, String content) {
        AgentMessage message = new AgentMessage();
        message.setKind(kind);
        message.setContent(content);
        return message;
    }
}

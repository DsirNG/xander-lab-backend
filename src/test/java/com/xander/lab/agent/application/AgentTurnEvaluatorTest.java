package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.agent.domain.AgentPlanItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTurnEvaluatorTest {

    private final AgentTurnEvaluator evaluator = new AgentTurnEvaluator();

    @Test
    void allowsClosingWhenNothingIsPendingAndNoToolFailed() {
        assertThat(evaluator.critique("已经写好了", List.of(
                item("查资料", "DONE"), item("写正文", "DONE")), null)).isEmpty();
    }

    @Test
    void allowsClosingOnAnEmptyPlan() {
        assertThat(evaluator.critique("你好", List.of(), null)).isEmpty();
        assertThat(evaluator.critique("你好", null, null)).isEmpty();
    }

    @Test
    void rejectsClosingWhileThePlanStillHasOpenSteps() {
        var verdict = evaluator.critique("都办好了", List.of(
                item("查资料", "DONE"), item("写正文", "IN_PROGRESS"), item("发布", "PENDING")), null);

        assertThat(verdict).isPresent();
        assertThat(verdict.orElseThrow())
                .contains("还有 2 个步骤没有收口")
                .contains("「写正文」(IN_PROGRESS)")
                .contains("「发布」(PENDING)")
                .contains("DROPPED");
    }

    @Test
    void treatsDroppedStepsAsSettledSoDeliberateGiveUpsDoNotBlockTheTurn() {
        assertThat(evaluator.critique("其中一步不需要做了", List.of(
                item("查资料", "DONE"), item("发布", "DROPPED")), null)).isEmpty();
    }

    @Test
    void rejectsAReplyThatHidesTheLastToolFailure() {
        var verdict = evaluator.critique("已经帮你生成好了", List.of(), error("上游超时"));

        assertThat(verdict).isPresent();
        assertThat(verdict.orElseThrow())
                .contains("上游超时")
                .contains("不要把失败说成完成");
    }

    @Test
    void acceptsAReplyThatOwnsUpToTheFailure() {
        assertThat(evaluator.critique("图片生成失败了，稍后重试", List.of(), error("上游超时"))).isEmpty();
        assertThat(evaluator.critique("我无法完成这一步", List.of(), error("上游超时"))).isEmpty();
    }

    @Test
    void fallsBackToTheWholeErrorPayloadWhenThereIsNoErrorMessage() {
        JsonNode raw = JsonNodeFactory.instance.objectNode().put("status", 502);

        assertThat(evaluator.critique("已完成", List.of(), raw).orElseThrow()).contains("502");
    }

    @Test
    void aBlankReplyNeverCountsAsAcknowledgingTheFailure() {
        assertThat(evaluator.critique("", List.of(), error("上游超时"))).isPresent();
        assertThat(evaluator.critique(null, List.of(), error("上游超时"))).isPresent();
    }

    @Test
    void unfinishedPlanIsReportedBeforeAnUnacknowledgedFailure() {
        // 先报计划：步骤没收口时让智能体继续干活，比先要它认错更可能真正完成诉求。
        assertThat(evaluator.critique("已完成", List.of(item("写正文", "PENDING")), error("上游超时"))
                .orElseThrow()).contains("没有收口");
    }

    private AgentPlanItem item(String title, String status) {
        return new AgentPlanItem(title, status, "");
    }

    private JsonNode error(String message) {
        return JsonNodeFactory.instance.objectNode().put("ok", false).put("error", message);
    }
}

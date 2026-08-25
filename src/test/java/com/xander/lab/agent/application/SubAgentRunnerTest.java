package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.agent.infrastructure.AgentConversationProperties;
import com.xander.lab.agent.infrastructure.AgentModelClient;
import com.xander.lab.common.ModelCallResult;
import com.xander.lab.common.ModelUsage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubAgentRunnerTest {

    private final AgentToolContext context = new AgentToolContext(42L, 7L, 3L);

    @Test
    void runsItsOwnLoopAndHandsBackOnlyTheConclusion() {
        RecordingTool knowledge = new RecordingTool("query_knowledge", "{\"items\":[\"三角函数\"]}");
        ScriptedModel model = new ScriptedModel(
                decideTool("query_knowledge"),
                done("知识库里有 1 条三角函数笔记"));
        SubAgentRunner runner = runner(model, 6, knowledge);
        CapturingProgress progress = new CapturingProgress();

        SubAgentRunner.SubAgentResult result = runner.run(context, "盘点三角函数笔记", "用户在准备复习",
                Set.of(), progress);

        assertThat(result.completed()).isTrue();
        assertThat(result.findings()).isEqualTo("知识库里有 1 条三角函数笔记");
        assertThat(result.steps()).isEqualTo(2);
        assertThat(result.toolCalls()).containsExactly("query_knowledge");
        assertThat(knowledge.calls).isEqualTo(1);
        // 子智能体的每一步都要让用户看到进度，但它的推理草稿不能外泄。
        assertThat(progress.messages).first().asString().contains("盘点三角函数笔记");
        assertThat(progress.messages).anyMatch(message -> message.contains("query_knowledge"));
        assertThat(progress.deltas).isEmpty();
    }

    @Test
    void feedsEveryObservationBackIntoTheNextSubContextWithTheRemainingBudget() {
        RecordingTool tool = new RecordingTool("query_knowledge", "{\"items\":[\"极限\"]}");
        ScriptedModel model = new ScriptedModel(decideTool("query_knowledge"), done("查到了极限"));
        SubAgentRunner runner = runner(model, 6, tool);

        runner.run(context, "盘点笔记", "自包含背景", Set.of(), new CapturingProgress());

        assertThat(model.contexts.get(0))
                .contains("【子任务目标】盘点笔记")
                .contains("【主智能体给的背景】自包含背景")
                .contains("【已有观察】还没有")
                .contains("剩余可用步数：5");
        assertThat(model.contexts.get(1))
                .contains("1) 调用 query_knowledge 得到：{\"items\":[\"极限\"]}")
                .contains("剩余可用步数：4");
    }

    @Test
    void neverExposesDelegationOrThePlanSoItCannotRecurseOrRewriteSessionState() {
        ScriptedModel model = new ScriptedModel(done("直接就有结论"));
        SubAgentRunner runner = runner(model, 6,
                new RecordingTool(DelegateSubtaskTool.NAME, "{}"),
                new RecordingTool(PlanTasksTool.NAME, "{}"),
                new RecordingTool(GradeAnswerTool.NAME, "{}"),
                new RecordingTool("query_knowledge", "{}"));

        SubAgentRunner.SubAgentResult result = runner.run(context, "随便查查", "", Set.of(), new CapturingProgress());

        assertThat(result.allowedTools()).containsExactly("query_knowledge");
        assertThat(model.toolDescriptions.get(0))
                .contains("query_knowledge")
                .doesNotContain(DelegateSubtaskTool.NAME)
                .doesNotContain(PlanTasksTool.NAME)
                // 子智能体看不到用户的作答，让它判分等于凭空改写掌握度。
                .doesNotContain(GradeAnswerTool.NAME);
    }

    @Test
    void aWhitelistOnlyNarrowsAndAnAllUnknownWhitelistFallsBackToTheFullAllowedSet() {
        ScriptedModel model = new ScriptedModel(done("好了"), done("好了"));
        SubAgentRunner runner = runner(model, 6,
                new RecordingTool("query_knowledge", "{}"),
                new RecordingTool("query_posts", "{}"));

        assertThat(runner.run(context, "查笔记", "",
                Set.of("query_knowledge", PlanTasksTool.NAME, "不存在的工具"), new CapturingProgress())
                .allowedTools())
                .containsExactly("query_knowledge");
        assertThat(runner.run(context, "查笔记", "", Set.of("完全不存在"), new CapturingProgress()).allowedTools())
                .containsExactly("query_knowledge", "query_posts");
    }

    @Test
    void refusingAnOutOfScopeToolBecomesAnObservationInsteadOfEndingTheSubtask() {
        ScriptedModel model = new ScriptedModel(decideTool("publish_post"), done("我改用别的办法查到了"));
        SubAgentRunner runner = runner(model, 6, new RecordingTool("query_knowledge", "{}"));

        SubAgentRunner.SubAgentResult result = runner.run(context, "查笔记", "", Set.of("query_knowledge"),
                new CapturingProgress());

        assertThat(result.completed()).isTrue();
        assertThat(result.toolCalls()).isEmpty();
        assertThat(model.contexts.get(1)).contains("尝试调用 publish_post 被拒绝").contains("query_knowledge");
    }

    @Test
    void stopsAfterRepeatedToolFailuresAndHandsBackTheObservationsAsUnfinished() {
        FailingTool broken = new FailingTool("query_knowledge", new IllegalStateException("数据库连不上"));
        ScriptedModel model = new ScriptedModel(decideTool("query_knowledge"), decideTool("query_knowledge"));
        SubAgentRunner runner = runner(model, 6, broken);

        SubAgentRunner.SubAgentResult result = runner.run(context, "查笔记", "", Set.of(), new CapturingProgress());

        assertThat(result.completed()).isFalse();
        assertThat(result.steps()).isEqualTo(2);
        assertThat(result.findings()).contains("调用 query_knowledge 失败：数据库连不上");
    }

    @Test
    void anExhaustedBudgetStillReturnsWhatItSawRatherThanNothing() {
        RecordingTool tool = new RecordingTool("query_knowledge", "{\"items\":[]}");
        ScriptedModel model = new ScriptedModel(decideTool("query_knowledge"), decideTool("query_knowledge"));
        SubAgentRunner runner = runner(model, 2, tool);

        SubAgentRunner.SubAgentResult result = runner.run(context, "查笔记", "", Set.of(), new CapturingProgress());

        assertThat(result.completed()).isFalse();
        assertThat(result.steps()).isEqualTo(2);
        assertThat(result.findings()).contains("调用 query_knowledge 得到");
        assertThat(tool.calls).isEqualTo(2);
    }

    @Test
    void reportsItsOwnTokenCostSoDelegationCannotSlipPastBilling() {
        ScriptedModel model = new ScriptedModel(
                new ModelCallResult(json("{\"done\":false,\"action\":{\"tool\":\"query_knowledge\"}}"),
                        new ModelUsage(100, 10, 20), "gpt-x"),
                new ModelCallResult(json("{\"done\":true,\"reply\":\"查到了\"}"),
                        new ModelUsage(200, 5, 30), "gpt-x"));
        SubAgentRunner runner = runner(model, 6, new RecordingTool("query_knowledge", "{}"));
        CapturingProgress progress = new CapturingProgress();

        runner.run(context, "查笔记", "", Set.of(), progress);

        assertThat(progress.usage).isEqualTo(new ModelUsage(300, 15, 50));
        assertThat(progress.usageModel).isEqualTo("gpt-x");
    }

    @Test
    void withholdsTheModelNameWhenTheSubtaskSpannedDifferentModels() {
        ScriptedModel model = new ScriptedModel(
                new ModelCallResult(json("{\"done\":false,\"action\":{\"tool\":\"query_knowledge\"}}"),
                        new ModelUsage(10, 0, 1), "fast-model"),
                new ModelCallResult(json("{\"done\":true,\"reply\":\"查到了\"}"),
                        new ModelUsage(20, 0, 2), "smart-model"));
        SubAgentRunner runner = runner(model, 6, new RecordingTool("query_knowledge", "{}"));
        CapturingProgress progress = new CapturingProgress();

        runner.run(context, "查笔记", "", Set.of(), progress);

        assertThat(progress.usage).isEqualTo(new ModelUsage(30, 0, 3));
        assertThat(progress.usageModel).isNull();
    }

    @Test
    void cancellationPropagatesInsteadOfBeingSwallowedAsAToolFailure() {
        FailingTool cancelling = new FailingTool("query_knowledge", new CancellationException("用户已停止"));
        ScriptedModel model = new ScriptedModel(decideTool("query_knowledge"));
        SubAgentRunner runner = runner(model, 6, cancelling);

        assertThatThrownBy(() -> runner.run(context, "查笔记", "", Set.of(), new CapturingProgress()))
                .isInstanceOf(CancellationException.class);
    }

    @Test
    void anEmptyObjectiveIsRejectedBeforeAnyModelCall() {
        ScriptedModel model = new ScriptedModel();
        SubAgentRunner runner = runner(model, 6, new RecordingTool("query_knowledge", "{}"));

        assertThatThrownBy(() -> runner.run(context, "  ", "背景", Set.of(), new CapturingProgress()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("objective");
        assertThat(model.contexts).isEmpty();
    }

    @Test
    void truncatesAnOverlongObjectiveAndAnOverlongConclusion() {
        String longReply = "结".repeat(SubAgentRunner.MAX_FINDINGS_LENGTH + 100);
        ScriptedModel model = new ScriptedModel(done(longReply));
        SubAgentRunner runner = runner(model, 6, new RecordingTool("query_knowledge", "{}"));

        SubAgentRunner.SubAgentResult result = runner.run(context,
                "标".repeat(SubAgentRunner.MAX_OBJECTIVE_LENGTH + 50), "", Set.of(), new CapturingProgress());

        assertThat(result.objective()).hasSize(SubAgentRunner.MAX_OBJECTIVE_LENGTH + 1).endsWith("…");
        assertThat(result.findings()).hasSize(SubAgentRunner.MAX_FINDINGS_LENGTH + 1).endsWith("…");
    }

    @Test
    void truncatesAnOverlongBriefInsteadOfShippingTheWholeThingToTheSubContext() {
        ScriptedModel model = new ScriptedModel(done("好了"));
        SubAgentRunner runner = runner(model, 6, new RecordingTool("query_knowledge", "{}"));

        runner.run(context, "查笔记", "背".repeat(SubAgentRunner.MAX_BRIEF_LENGTH + 200), Set.of(),
                new CapturingProgress());

        assertThat(model.contexts.get(0)).contains("背".repeat(SubAgentRunner.MAX_BRIEF_LENGTH) + "…");
    }

    @Test
    void aDoneDecisionWithNoTextCountsAsUnfinished() {
        ScriptedModel model = new ScriptedModel(done("   "));
        SubAgentRunner runner = runner(model, 6, new RecordingTool("query_knowledge", "{}"));

        SubAgentRunner.SubAgentResult result = runner.run(context, "查笔记", "", Set.of(), new CapturingProgress());

        assertThat(result.completed()).isFalse();
        assertThat(result.findings()).isEqualTo("子任务没有产出任何观察。");
    }

    private SubAgentRunner runner(ScriptedModel model, int maxSubAgentSteps, AgentTool... tools) {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tools));
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentToolRegistry> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(registry);
        AgentConversationProperties properties = new AgentConversationProperties();
        properties.setMaxSubAgentSteps(maxSubAgentSteps);
        AgentModelClient client = mock(AgentModelClient.class);
        when(client.decideNextStepStream(anyString(), anyString(), any(), any())).thenAnswer(model);
        return new SubAgentRunner(provider, client, properties);
    }

    /** 按脚本依次交出决策，同时记录子智能体每一步看到的上下文与工具表。 */
    private static final class ScriptedModel
            implements org.mockito.stubbing.Answer<ModelCallResult> {
        private final Deque<ModelCallResult> script = new ArrayDeque<>();
        private final List<String> contexts = new ArrayList<>();
        private final List<String> toolDescriptions = new ArrayList<>();

        private ScriptedModel(ModelCallResult... results) {
            script.addAll(List.of(results));
        }

        @Override
        public ModelCallResult answer(org.mockito.invocation.InvocationOnMock invocation) {
            contexts.add(invocation.getArgument(0));
            toolDescriptions.add(invocation.getArgument(1));
            ModelCallResult next = script.poll();
            if (next == null) throw new AssertionError("子智能体超出脚本继续调用了模型");
            return next;
        }
    }

    private static ModelCallResult decideTool(String toolName) {
        return ModelCallResult.of(
                json("{\"done\":false,\"action\":{\"tool\":\"" + toolName + "\",\"args\":{}}}"),
                ModelUsage.NONE);
    }

    private static ModelCallResult done(String reply) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("done", true);
        payload.put("reply", reply);
        return ModelCallResult.of(payload, ModelUsage.NONE);
    }

    private static JsonNode json(String raw) {
        try {
            return new ObjectMapper().readTree(raw);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static class RecordingTool implements AgentTool {
        private final String name;
        private final String result;
        private int calls;

        private RecordingTool(String name, String result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name + " 的说明";
        }

        @Override
        public JsonNode parametersSchema() {
            return JsonNodeFactory.instance.objectNode().put("type", "object");
        }

        @Override
        public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
            calls++;
            return json(result);
        }
    }

    private static final class FailingTool extends RecordingTool {
        private final RuntimeException failure;

        private FailingTool(String name, RuntimeException failure) {
            super(name, "{}");
            this.failure = failure;
        }

        @Override
        public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
            throw failure;
        }
    }

    private static final class CapturingProgress implements AgentTool.ProgressListener {
        private final List<String> messages = new ArrayList<>();
        private final List<String> deltas = new ArrayList<>();
        private ModelUsage usage;
        private String usageModel;

        @Override
        public void onProgress(String message) {
            messages.add(message);
        }

        @Override
        public void onToolDelta(String delta) {
            deltas.add(delta);
        }

        @Override
        public void onModelUsage(ModelUsage reported, String model) {
            usage = reported;
            usageModel = model;
        }
    }
}

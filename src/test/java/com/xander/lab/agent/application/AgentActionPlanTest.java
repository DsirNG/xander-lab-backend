package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 决策到"这一步要调哪几个工具"的翻译。
 *
 * <p>这里最要紧的一条是**整批要么全执行、要么全拒绝**：半批跑完会让模型分不清哪一半生效，
 * 于是它会把已经成功的那部分重复调一遍。所以下面每个拒绝用例都同时断言 {@code calls} 为空。</p>
 *
 * <p>测试里给 {@code args} 直接放对象，是因为进入这一层之前 {@code AgentModelClient} 已经把
 * 模型交上来的 JSON 字符串解码成对象了（见 {@code decodeArgs}）。</p>
 */
class AgentActionPlanTest {

    private static final Set<String> ALLOWED =
            Set.of("query_knowledge", "query_posts", "query_plans", "recall_memory", "publish_post");

    @Test
    void singleActionBehavesExactlyAsBefore() {
        AgentActionPlan plan = AgentActionPlan.from(
                decisionWithAction(call("query_posts", args("limit", 5))), ALLOWED, 4);

        assertThat(plan.rejected()).isFalse();
        assertThat(plan.calls()).hasSize(1);
        assertThat(plan.calls().get(0).toolName()).isEqualTo("query_posts");
        assertThat(plan.calls().get(0).args().path("limit").asInt()).isEqualTo(5);
        assertThat(plan.skipped()).isEmpty();
    }

    @Test
    void batchPreservesTheOrderTheModelAskedFor() {
        AgentActionPlan plan = AgentActionPlan.from(decisionWithActions(batch(
                call("query_knowledge", args()),
                call("query_posts", args()),
                call("query_plans", args()))), ALLOWED, 4);

        assertThat(plan.rejected()).isFalse();
        assertThat(plan.calls()).extracting(AgentToolCall::toolName)
                .containsExactly("query_knowledge", "query_posts", "query_plans");
    }

    @Test
    void actionsWinsOverActionWhenBothArePresent() {
        ObjectNode decision = decisionWithAction(call("publish_post", args()));
        decision.set("actions", batch(call("query_posts", args())));

        AgentActionPlan plan = AgentActionPlan.from(decision, ALLOWED, 4);

        assertThat(plan.calls()).extracting(AgentToolCall::toolName).containsExactly("query_posts");
    }

    @Test
    void unknownToolRejectsTheWholeBatchAndNamesEveryOffender() {
        AgentActionPlan plan = AgentActionPlan.from(decisionWithActions(batch(
                call("query_posts", args()),
                call("delete_everything", args()),
                call("also_missing", args()))), ALLOWED, 4);

        assertThat(plan.rejected()).isTrue();
        assertThat(plan.calls()).isEmpty();
        assertThat(plan.rejection()).contains("未知工具").contains("delete_everything").contains("also_missing");
        assertThat(plan.rejectedTools()).containsExactly("delete_everything", "also_missing");
    }

    @Test
    void missingToolNameReadsAsTheBlankPlaceholderRatherThanCrashing() {
        AgentActionPlan plan = AgentActionPlan.from(decision(), ALLOWED, 4);

        assertThat(plan.rejected()).isTrue();
        assertThat(plan.calls()).isEmpty();
        assertThat(plan.rejection()).contains("未知工具").contains("（空工具名）");
        assertThat(plan.rejectedTools()).containsExactly("");
    }

    /**
     * 同名但**参数不同**是合法用法：一次查两个主题的知识点，前后端都按 invocationId 配对，
     * 不会串卡。这条守着"别把同名一起拒掉"——早先的版本正是这样，代价是模型只能一步步来。
     */
    @Test
    void theSameToolWithDifferentArgumentsIsAllowedInOneBatch() {
        AgentActionPlan plan = AgentActionPlan.from(decisionWithActions(batch(
                call("query_knowledge", args("topic", "a")),
                call("query_knowledge", args("topic", "b")))), ALLOWED, 4);

        assertThat(plan.rejected()).isFalse();
        assertThat(plan.calls()).hasSize(2);
        assertThat(plan.calls()).extracting(call -> call.args().path("topic").asText())
                .containsExactly("a", "b");
    }

    /**
     * 同名**且**同参数才是错：同一步里把同一件事说了两遍，要么是笔误，
     * 要么会让写操作真的执行两次（重复发帖、重复扣款）。
     */
    @Test
    void repeatingTheExactSameCallInOneBatchIsRejected() {
        AgentActionPlan plan = AgentActionPlan.from(decisionWithActions(batch(
                call("query_knowledge", args("topic", "a")),
                call("query_knowledge", args("topic", "a")))), ALLOWED, 4);

        assertThat(plan.rejected()).isTrue();
        assertThat(plan.calls()).isEmpty();
        assertThat(plan.rejection()).contains("完全相同的调用").contains("query_knowledge");
    }

    /** 参数顺序不同不是"不同的事"：JSON 对象的相等性与键序无关，否则这条会漏过去。 */
    @Test
    void duplicateDetectionIgnoresArgumentKeyOrder() {
        AgentActionPlan plan = AgentActionPlan.from(decisionWithActions(batch(
                call("query_knowledge", args("topic", "a", "limit", "5")),
                call("query_knowledge", args("limit", "5", "topic", "a")))), ALLOWED, 4);

        assertThat(plan.rejected()).isTrue();
        assertThat(plan.rejection()).contains("完全相同的调用");
    }

    @Test
    void overflowIsTruncatedAndTheRestReportedAsSkipped() {
        AgentActionPlan plan = AgentActionPlan.from(decisionWithActions(batch(
                call("query_knowledge", args()),
                call("query_posts", args()),
                call("query_plans", args()))), ALLOWED, 2);

        assertThat(plan.rejected()).isFalse();
        assertThat(plan.calls()).extracting(AgentToolCall::toolName)
                .containsExactly("query_knowledge", "query_posts");
        // 超出的调用不能静默消失：模型以为自己问过、只是没收到答案，就会拿着残缺信息收口。
        assertThat(plan.skipped()).extracting(AgentToolCall::toolName).containsExactly("query_plans");
    }

    @Test
    void nonObjectEntriesInActionsFallBackToTheSingleAction() {
        ObjectNode decision = decisionWithAction(call("query_posts", args()));
        decision.set("actions", JsonNodeFactory.instance.arrayNode().add("query_posts"));

        AgentActionPlan plan = AgentActionPlan.from(decision, ALLOWED, 4);

        assertThat(plan.rejected()).isFalse();
        assertThat(plan.calls()).extracting(AgentToolCall::toolName).containsExactly("query_posts");
    }

    @Test
    void toolNamesAreTrimmedBeforeValidation() {
        AgentActionPlan plan = AgentActionPlan.from(
                decisionWithAction(call("  query_posts  ", args())), ALLOWED, 4);

        assertThat(plan.calls()).extracting(AgentToolCall::toolName).containsExactly("query_posts");
    }

    /** done=false，两个动作字段都不给——就是"想调工具但没说是哪个"。 */
    private ObjectNode decision() {
        return JsonNodeFactory.instance.objectNode().put("done", false);
    }

    private ObjectNode decisionWithAction(JsonNode action) {
        ObjectNode decision = decision();
        decision.set("action", action);
        decision.putNull("actions");
        return decision;
    }

    private ObjectNode decisionWithActions(ArrayNode actions) {
        ObjectNode decision = decision();
        decision.putNull("action");
        decision.set("actions", actions);
        return decision;
    }

    private ObjectNode call(String tool, JsonNode args) {
        ObjectNode call = JsonNodeFactory.instance.objectNode().put("tool", tool);
        call.set("args", args);
        return call;
    }

    private ArrayNode batch(JsonNode... calls) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (JsonNode call : calls) array.add(call);
        return array;
    }

    private JsonNode args() {
        return JsonNodeFactory.instance.objectNode();
    }

    private JsonNode args(String name, String value) {
        return JsonNodeFactory.instance.objectNode().put(name, value);
    }

    private JsonNode args(String name, int value) {
        return JsonNodeFactory.instance.objectNode().put(name, value);
    }

    /** 多字段参数：用来验证"参数键序不同不算不同的事"。 */
    private JsonNode args(String first, String firstValue, String second, String secondValue) {
        return JsonNodeFactory.instance.objectNode()
                .put(first, firstValue)
                .put(second, secondValue);
    }
}

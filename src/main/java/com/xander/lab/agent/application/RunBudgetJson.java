package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.agent.domain.RunBudget;

import java.time.Duration;

/**
 * {@link RunBudget} 与 {@code agent_run.budget_json} 之间的编解码。
 *
 * <p><b>为什么不直接序列化 record：</b>那个 JSON 是要被**长期读**的（一个 Run 的生命周期内每次
 * 预算判定都要读它，排障时还要拿它复现"当时用的是哪套标准"）。直接序列化会把字段名、{@code Duration}
 * 的表示形式（ISO-8601 还是秒数）都交给 Jackson 的配置决定——换一次序列化配置，或者哪天给 record
 * 加个字段，存量数据就悄悄换了形态，而读它的地方只会看到 null 然后退回默认值，
 * 于是"预算莫名变了"没有任何报错。这里把形态写死成一组显式整数，读写都是它。</p>
 *
 * <p>时长存**秒**而不是 {@code Duration} 对象：解析不依赖任何模块注册，
 * 且"秒"是这个字段唯一有意义的精度（判定粒度为秒级，见 {@code AgentConversationProperties.maxActiveMinutes}）。</p>
 */
final class RunBudgetJson {

    /**
     * 只用来读这份固定形态的 JSON，不注册任何模块。
     *
     * <p>刻意**不复用**容器里的那个 {@code ObjectMapper}：那个实例的序列化配置（命名策略、
     * 时间模块、未知字段处理）是给对外 API 用的，会随部署配置变化；而这里的读写形态必须是固定的。
     * 用一个独立实例，改 API 序列化配置不会顺手改掉存量预算快照的解读方式。</p>
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RunBudgetJson() {
    }

    /** 序列化为 budget_json 的正文。{@code null} 预算写成 {@code null}，由调用方决定不写这一列。 */
    static String write(RunBudget budget) {
        if (budget == null) return null;
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("maxSteps", budget.maxSteps());
        node.put("maxToolCalls", budget.maxToolCalls());
        node.put("maxInputTokens", budget.maxInputTokens());
        node.put("maxOutputTokens", budget.maxOutputTokens());
        node.put("maxTotalTokens", budget.maxTotalTokens());
        node.put("maxMilliPoints", budget.maxMilliPoints());
        node.put("maxActiveSeconds", budget.maxActiveDuration() == null
                ? RunBudget.UNLIMITED : budget.maxActiveDuration().toSeconds());
        return node.toString();
    }

    /**
     * 解析 budget_json。
     *
     * <p>解析失败或内容为空时返回 {@code null}，由调用方退回当前配置——**不要**在这里
     * 静默返回 {@code unlimited()}：那会让"快照损坏"变成"这个 Run 没有预算"，
     * 而后者是一个看起来完全正常的状态。返回 null 至少让调用方能记一条日志。</p>
     */
    static RunBudget read(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isObject()) return null;
            long activeSeconds = node.path("maxActiveSeconds").asLong(RunBudget.UNLIMITED);
            return new RunBudget(
                    node.path("maxSteps").asInt(RunBudget.UNLIMITED),
                    node.path("maxToolCalls").asInt(RunBudget.UNLIMITED),
                    node.path("maxInputTokens").asLong(RunBudget.UNLIMITED),
                    node.path("maxOutputTokens").asLong(RunBudget.UNLIMITED),
                    node.path("maxTotalTokens").asLong(RunBudget.UNLIMITED),
                    node.path("maxMilliPoints").asLong(RunBudget.UNLIMITED),
                    activeSeconds > 0 ? Duration.ofSeconds(activeSeconds) : null);
        } catch (Exception malformed) {
            return null;
        }
    }
}

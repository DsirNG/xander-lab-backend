package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Multi-Agent：把一个能独立收口的子任务派给上下文隔离的子智能体。
 *
 * <p>主智能体调用本工具时相当于说"这段脏活你去干，只把结论告诉我"。
 * 子任务的十几条原始工具输出留在子上下文里，主上下文只多一段结论，
 * 主循环的步数预算也只消耗一步。</p>
 */
@Component
public class DelegateSubtaskTool implements AgentTool {

    /** 子智能体自己不能再调用本工具，见 {@link SubAgentRunner#FORBIDDEN_TOOLS}。 */
    public static final String NAME = "delegate_subtask";

    private final SubAgentRunner runner;

    public DelegateSubtaskTool(SubAgentRunner runner) {
        this.runner = runner;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "把一个能独立收口的子任务交给一个上下文隔离的子智能体去做，只拿回它的结论。"
                + "使用时机：这个子任务预计要连续调好几次工具、中间产物又多又长（例如翻遍知识库再横向比较、"
                + "对一批条目逐个取证），就地做会把你的上下文塞满而影响后续判断。"
                + "不要用于一两步就能做完的事，也不要用它替你写最终回复。"
                + "子智能体看不到本次对话，brief 必须自包含：写清背景、已知结论、判断口径和你想要的产出格式。"
                + "它不能再往下派发，也不能改写你的任务计划。";
    }

    @Override
    public Optional<AgentPromptSection> promptSection() {
        return Optional.of(AgentPromptSection.sideEffect(
                "需要翻查大量资料、而那些原始结果对最终答案没有价值时，用 " + NAME + " 把取证交给子智能体，"
                        + "主线只接收结论。子智能体看不到本轮对话，brief 必须自包含；"
                        + "它只有只读工具，不能替你写入或发布，也不能改你的任务计划。"
                        + "一步就能查到的事不要派发——那只是白花一次模型调用。"));
    }

    @Override
    public JsonNode parametersSchema() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("objective")
                .put("type", "string")
                .put("description", "子任务目标，一句话说清要拿到什么，最多 "
                        + SubAgentRunner.MAX_OBJECTIVE_LENGTH + " 个字符");
        properties.putObject("brief")
                .put("type", "string")
                .put("description", "给子智能体的自包含背景：它看不到本次对话，最多 "
                        + SubAgentRunner.MAX_BRIEF_LENGTH + " 个字符");
        ObjectNode tools = properties.putObject("tools");
        tools.put("type", "array");
        tools.put("description", "可选：限定子智能体只能使用的工具名。不填表示由它在允许集合里自己挑");
        tools.putObject("items").put("type", "string");
        schema.putArray("required").add("objective");
        return schema;
    }

    @Override
    public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
        throw new IllegalStateException(NAME + " 必须在会话上下文中执行");
    }

    @Override
    public JsonNode execute(AgentToolContext context, JsonNode args, ProgressListener onProgress) {
        onProgress.checkCancellation();
        if (!context.hasConversation()) {
            throw new IllegalStateException(NAME + " 必须在会话上下文中执行");
        }
        SubAgentRunner.SubAgentResult result = runner.run(context,
                args.path("objective").asText(""),
                args.path("brief").asText(""),
                readTools(args.path("tools")),
                onProgress);

        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("ok", true);
        outcome.put("objective", result.objective());
        outcome.put("completed", result.completed());
        outcome.put("steps", result.steps());
        ArrayNode calls = outcome.putArray("toolCalls");
        result.toolCalls().forEach(calls::add);
        outcome.put("findings", result.findings());
        outcome.put("hint", result.completed()
                ? "这是子智能体的结论，不是你的答复：自己核对它是否真的回答了子任务，再决定下一步或如何写进最终回复。"
                : "子智能体没能收口，findings 只是它的中途观察。别当成结论用：要么自己接着查，要么在回复里如实说明这部分没查到。");
        return outcome;
    }

    /** 白名单只取字符串元素；写坏了就当没限定，由 {@link SubAgentRunner} 兜住允许集合。 */
    private Set<String> readTools(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            node.forEach(entry -> {
                String name = entry.asText("");
                if (StringUtils.hasText(name)) names.add(name.trim());
            });
        }
        return names;
    }
}

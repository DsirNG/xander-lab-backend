package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.agent.domain.AgentPlanItem;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Planner：让智能体把多步任务写成自己的待办清单，并在推进过程中改写它。
 *
 * <p>这是"自主安排"的落点。计划不是给用户看的装饰，而是会话状态：
 * 每一步推理都会重新读到它，所以模型改一次计划，后面每一步看到的都是新的。</p>
 */
@Component
public class PlanTasksTool implements AgentTool {

    /** 编排循环据此识别计划变更并单独广播 plan 事件。 */
    public static final String NAME = "plan_tasks";

    private final AgentPlanService planService;

    public PlanTasksTool(AgentPlanService planService) {
        this.planService = planService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "写下或更新你自己的任务计划，用于需要多个步骤才能完成的诉求。"
                + "每次调用都要给出完整的步骤列表（整体替换，不是增量补丁），"
                + "所以删除步骤、调整顺序、修改状态都通过重述整个列表完成。"
                + "使用时机：诉求需要两步以上时先写计划再动手；每完成一步立刻把它标成 DONE 并把下一步标成 IN_PROGRESS；"
                + "发现原计划不对就直接改写。单步就能完成的简单请求不要调用本工具。"
                + "status 取值：PENDING 未开始、IN_PROGRESS 进行中（同一时刻只能有一个）、DONE 已完成、DROPPED 主动放弃（note 必须写原因）。";
    }

    @Override
    public JsonNode parametersSchema() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode items = properties.putObject("items");
        items.put("type", "array");
        items.put("description", "完整的任务步骤列表，最多 " + AgentPlanService.MAX_ITEMS + " 步");
        ObjectNode item = items.putObject("items");
        item.put("type", "object");
        ObjectNode itemProperties = item.putObject("properties");
        itemProperties.putObject("title")
                .put("type", "string")
                .put("description", "这一步要做什么，面向用户可读，最多120个字符");
        ObjectNode status = itemProperties.putObject("status");
        status.put("type", "string");
        status.set("enum", factory.arrayNode()
                .add("PENDING").add("IN_PROGRESS").add("DONE").add("DROPPED"));
        status.put("description", "步骤状态");
        itemProperties.putObject("note")
                .put("type", "string")
                .put("description", "可选备注：卡在哪里、产出在哪里、放弃原因，最多300个字符");
        item.putArray("required").add("title").add("status");
        schema.putArray("required").add("items");
        return schema;
    }

    @Override
    public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
        throw new IllegalStateException("plan_tasks 必须在会话上下文中执行");
    }

    @Override
    public JsonNode execute(AgentToolContext context, JsonNode args, ProgressListener onProgress) {
        onProgress.checkCancellation();
        if (!context.hasConversation()) {
            throw new IllegalStateException("plan_tasks 必须在会话上下文中执行");
        }
        List<AgentPlanItem> saved = planService.replace(context.conversationId(), context.userId(),
                context.runVersion(), args.path("items"));
        List<AgentPlanItem> unfinished = planService.unfinished(saved);

        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("ok", true);
        outcome.put("total", saved.size());
        outcome.put("unfinished", unfinished.size());
        ArrayNode records = outcome.putArray("items");
        saved.forEach(item -> {
            ObjectNode record = records.addObject();
            record.put("title", item.title());
            record.put("status", item.status());
            if (!item.note().isEmpty()) record.put("note", item.note());
        });
        outcome.put("hint", unfinished.isEmpty()
                ? "计划中所有步骤都已收口，可以给出最终回复。"
                : "计划已保存。现在直接执行下一个未完成步骤，不要把计划本身当成答复交给用户。");
        return outcome;
    }
}

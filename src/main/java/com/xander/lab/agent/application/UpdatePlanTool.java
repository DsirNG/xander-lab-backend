package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.automation.api.BlogContentPlanVO;
import com.xander.lab.automation.application.BlogContentPlanService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Manages a recurring content plan (定时发文计划): pause, resume, cancel or
 * delete an existing plan owned by the current user. State transitions are
 * enforced by the plan service; the tool only translates the user's request
 * into the matching action.
 */
@Component
public class UpdatePlanTool implements AgentTool {

    private final BlogContentPlanService planService;

    public UpdatePlanTool(BlogContentPlanService planService) {
        this.planService = planService;
    }

    @Override
    public String name() {
        return "update_plan";
    }

    @Override
    public String description() {
        return "对当前用户已有的定时发文计划执行暂停、恢复、取消或删除操作。"
                + "参数 planId 为计划 id（可用 query_plans 查询），action 取值 PAUSE / RESUME / CANCEL / DELETE。"
                + "当用户说“把 X 计划暂停/停掉/恢复/取消/删除”、“先别发这个了”时使用本工具。"
                + "注意：取消/删除不可恢复，执行前请先向用户确认。";
    }

    @Override
    public JsonNode parametersSchema() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode planId = properties.putObject("planId");
        planId.put("type", "integer");
        planId.put("description", "定时发文计划 id（必填）");
        ObjectNode action = properties.putObject("action");
        action.put("type", "string");
        action.set("enum", JsonNodeFactory.instance.arrayNode()
                .add("PAUSE").add("RESUME").add("CANCEL").add("DELETE"));
        action.put("description", "操作类型：PAUSE 暂停 / RESUME 恢复 / CANCEL 取消 / DELETE 删除（必填）");
        schema.putArray("required").add("planId").add("action");
        return schema;
    }

    @Override
    public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
        onProgress.checkCancellation();
        long planId = args.path("planId").asLong(-1L);
        String action = args.path("action").asText("").trim().toUpperCase();
        if (planId <= 0) {
            throw new IllegalArgumentException("定时发文计划工具缺少参数 planId");
        }
        if (!StringUtils.hasText(action)) {
            throw new IllegalArgumentException("定时发文计划工具缺少参数 action");
        }

        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("ok", true);
        outcome.put("planId", planId);
        outcome.put("action", action);
        switch (action) {
            case "PAUSE", "RESUME", "CANCEL" -> {
                onProgress.onProgress("正在" + actionText(action) + "定时发文计划…");
                BlogContentPlanVO plan = planService.updateStatus(userId, planId, action);
                outcome.put("topic", defaultText(plan.getTopic(), ""));
                outcome.put("status", defaultText(plan.getStatus(), ""));
                outcome.put("nextRunAt", plan.getNextRunAt() == null ? "" : plan.getNextRunAt().toString());
                outcome.put("hint", "定时发文计划已" + actionText(action) + "。");
            }
            case "DELETE" -> {
                onProgress.onProgress("正在删除定时发文计划…");
                planService.delete(userId, planId);
                outcome.put("hint", "定时发文计划已删除。");
            }
            default -> throw new IllegalArgumentException("操作类型只支持 PAUSE / RESUME / CANCEL / DELETE");
        }
        return outcome;
    }

    private String actionText(String action) {
        return switch (action) {
            case "PAUSE" -> "暂停";
            case "RESUME" -> "恢复";
            case "CANCEL" -> "取消";
            default -> "删除";
        };
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
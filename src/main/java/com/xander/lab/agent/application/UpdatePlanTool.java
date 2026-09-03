package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.automation.api.BlogContentPlanUpdateRequest;
import com.xander.lab.automation.api.BlogContentPlanVO;
import com.xander.lab.automation.application.BlogContentPlanService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Manages a recurring content plan (定时发文计划): pause, resume, cancel, delete
 * or edit an existing plan owned by the current user. State transitions are
 * enforced by the plan service; the tool only translates the user's request
 * into the matching action. Action words are normalized leniently because the
 * model may mix the plan status vocabulary (PAUSED / CANCELLED) with the action
 * vocabulary (PAUSE / CANCEL); both spellings must work to avoid retry loops.
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
        return "对当前用户已有的定时发文计划执行暂停、恢复、取消、删除或编辑操作。"
                + "参数 planId 为计划 id（可用 query_plans 查询），action 取值 PAUSE/PAUSED 暂停、"
                + "RESUME 恢复、CANCEL/CANCELLED 取消、DELETE 删除、EDIT 编辑。"
                + "当用户想修改计划的主题、触发时间、是否同步 CSDN/掘金、目标读者或文风时，使用 action=EDIT"
                + "并传入对应字段（topic/triggerTime/syncCsdn/audience/tone 等），直接修改即可，不要去取消旧计划再新建。"
                + "当用户说“把 X 计划暂停/停掉/恢复/取消/删除”、“先别发这个了”、“把 X 计划的主题改成……、时间改到……”时使用本工具。"
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
                .add("PAUSE").add("PAUSED").add("RESUME")
                .add("CANCEL").add("CANCELLED").add("DELETE").add("EDIT"));
        action.put("description", "操作类型（必填）：PAUSE/PAUSED 暂停、RESUME 恢复、CANCEL/CANCELLED 取消、DELETE 删除、EDIT 编辑");
        ObjectNode topic = properties.putObject("topic");
        topic.put("type", "string");
        topic.put("description", "新文章主题（仅 EDIT 时可选；应提炼为简短标题，不要把用户素材原文直接作为主题）");
        ObjectNode triggerTime = properties.putObject("triggerTime");
        triggerTime.put("type", "string");
        triggerTime.put("description", "新的单个每日触发时间 HH:mm（仅 EDIT 时可选）");
        ObjectNode triggerTimes = properties.putObject("triggerTimes");
        triggerTimes.put("type", "array");
        triggerTimes.set("items", factory.objectNode().put("type", "string").put("description", "HH:mm"));
        triggerTimes.put("description", "新的每日触发时间列表（仅 EDIT 时可选，如 [\"09:00\", \"20:00\"]）");
        ObjectNode syncCsdn = properties.putObject("syncCsdn");
        syncCsdn.put("type", "boolean");
        syncCsdn.put("description", "是否同步发布到 CSDN（仅 EDIT 时可选）");
        ObjectNode syncJuejin = properties.putObject("syncJuejin");
        syncJuejin.put("type", "boolean");
        syncJuejin.put("description", "是否同步发布到掘金（仅 EDIT 时可选）");
        ObjectNode audience = properties.putObject("audience");
        audience.put("type", "string");
        audience.put("description", "新的目标读者（仅 EDIT 时可选）");
        ObjectNode tone = properties.putObject("tone");
        tone.put("type", "string");
        tone.put("description", "新的文风语气（仅 EDIT 时可选）");
        ObjectNode timezone = properties.putObject("timezone");
        timezone.put("type", "string");
        timezone.put("description", "新的 IANA 时区（仅 EDIT 时可选）");
        schema.putArray("required").add("planId").add("action");
        return schema;
    }

    @Override
    public Optional<String> approvalReason(JsonNode args) {
        String action = args.path("action").asText("").trim().toUpperCase(Locale.ROOT);
        return switch (action) {
            case "CANCEL", "CANCELLED" -> Optional.of("取消自动发文计划，该计划不会再被调度执行");
            case "DELETE" -> Optional.of("删除自动发文计划，该操作不可恢复");
            default -> Optional.empty();
        };
    }

    @Override
    public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
        onProgress.checkCancellation();
        long planId = args.path("planId").asLong(-1L);
        if (planId <= 0) {
            throw new IllegalArgumentException("定时发文计划工具缺少参数 planId");
        }
        String action = normalizeAction(args.path("action").asText(""));

        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("ok", true);
        outcome.put("planId", planId);
        outcome.put("action", action);
        switch (action) {
            case "PAUSED", "RESUME", "CANCELLED" -> {
                onProgress.onProgress("正在" + actionText(action) + "定时发文计划…");
                BlogContentPlanVO plan = planService.updateStatus(userId, planId, action);
                fillPlan(outcome, plan);
                outcome.put("hint", "定时发文计划已" + actionText(action) + "。");
            }
            case "EDIT" -> {
                onProgress.onProgress("正在更新定时发文计划…");
                BlogContentPlanVO plan = planService.update(userId, planId, toUpdateRequest(args));
                fillPlan(outcome, plan);
                outcome.put("hint", "定时发文计划已更新。");
            }
            case "DELETE" -> {
                onProgress.onProgress("正在删除定时发文计划…");
                planService.delete(userId, planId);
                outcome.put("hint", "定时发文计划已删除。");
            }
            default -> throw new IllegalArgumentException(
                    "操作类型只支持 PAUSE / PAUSED / RESUME / CANCEL / CANCELLED / DELETE / EDIT");
        }
        return outcome;
    }

    /** Lenient action normalization: accepts both the tool action vocabulary
     * (PAUSE/CANCEL) and the plan status vocabulary (PAUSED/CANCELLED). */
    private String normalizeAction(String raw) {
        String action = StringUtils.hasText(raw) ? raw.trim().toUpperCase(Locale.ROOT) : "";
        return switch (action) {
            case "PAUSE", "PAUSED" -> "PAUSED";
            case "RESUME" -> "RESUME";
            case "CANCEL", "CANCELLED" -> "CANCELLED";
            case "DELETE" -> "DELETE";
            case "EDIT" -> "EDIT";
            default -> throw new IllegalArgumentException(
                    "操作类型只支持 PAUSE / PAUSED / RESUME / CANCEL / CANCELLED / DELETE / EDIT");
        };
    }

    private BlogContentPlanUpdateRequest toUpdateRequest(JsonNode args) {
        BlogContentPlanUpdateRequest request = new BlogContentPlanUpdateRequest();
        String topic = args.path("topic").asText("");
        if (StringUtils.hasText(topic)) {
            if (topic.trim().length() > 120) {
                throw new IllegalArgumentException(
                        "文章主题过长：" + topic.trim().length()
                                + " 字。请把素材提炼为不超过 120 字的简短标题，不要把素材原文直接作为主题。");
            }
            request.setTopic(topic.trim());
        }
        request.setTriggerTime(defaultText(args.path("triggerTime").asText(""), null));
        request.setTriggerTimes(stringArray(args.path("triggerTimes")));
        if (args.path("syncCsdn").isBoolean()) {
            request.setSyncCsdn(args.path("syncCsdn").asBoolean());
        }
        if (args.path("syncJuejin").isBoolean()) {
            request.setSyncJuejin(args.path("syncJuejin").asBoolean());
        }
        request.setAudience(defaultText(args.path("audience").asText(""), null));
        request.setTone(defaultText(args.path("tone").asText(""), null));
        request.setTimezone(defaultText(args.path("timezone").asText(""), null));
        return request;
    }

    private void fillPlan(ObjectNode outcome, BlogContentPlanVO plan) {
        outcome.put("topic", defaultText(plan.getTopic(), ""));
        outcome.put("status", defaultText(plan.getStatus(), ""));
        outcome.put("nextRunAt", plan.getNextRunAt() == null ? "" : plan.getNextRunAt().toString());
        ArrayNode details = outcome.putArray("triggerTimes");
        if (plan.getTriggerTimes() != null) {
            plan.getTriggerTimes().forEach(details::add);
        }
    }

    private String actionText(String action) {
        return switch (action) {
            case "PAUSED" -> "暂停";
            case "RESUME" -> "恢复";
            case "CANCELLED" -> "取消";
            default -> "更新";
        };
    }

    private List<String> stringArray(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node == null || !node.isArray()) return result;
        node.forEach(item -> {
            String value = item.asText("");
            if (StringUtils.hasText(value)) result.add(value.trim());
        });
        return result;
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}

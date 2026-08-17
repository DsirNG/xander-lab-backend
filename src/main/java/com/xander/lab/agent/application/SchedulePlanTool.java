package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.automation.api.BlogContentPlanCreateRequest;
import com.xander.lab.automation.api.BlogContentPlanVO;
import com.xander.lab.automation.application.BlogContentPlanService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Creates a recurring content plan (定时发文计划). The plan owns a topic
 * queue, trigger times, and an optional CSDN sync; the automation poller runs
 * the headless pipeline on schedule. The agent only creates the durable plan,
 * it never runs the schedule itself.
 */
@Component
public class SchedulePlanTool implements AgentTool {

    private final BlogContentPlanService planService;

    public SchedulePlanTool(BlogContentPlanService planService) {
        this.planService = planService;
    }

    @Override
    public String name() {
        return "schedule_plan";
    }

    @Override
    public String description() {
        return "创建一个定时发文计划：设定主题、每日触发时间和可选的目标读者与文风，"
                + "系统会按计划自动生成并发布文章。可设置多个主题排队、多组触发时间，"
                + "并可选择同步到 CSDN 或掘金。用户要求定时发文、每日更新、自动更新博客时使用本工具。"
                + "注意：用户提供的技术说明、素材或参考文本只是创作参考，不能直接作为 topic；"
                + "topic 必须是由你自行提炼的简短文章标题（建议 4~40 字），例如“前端文件下载体验与跨域兼容性实践”。";
    }

    @Override
    public JsonNode parametersSchema() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode topic = properties.putObject("topic");
        topic.put("type", "string");
        topic.put("description", "文章主题（必填）：应为简短自成一体的标题（建议 4~40 字，最多 120 字）。"
                + "用户提供的素材只能作参考，必须由你提炼成简短选题，不能把素材原文直接作为主题。");
        ObjectNode triggerTimes = properties.putObject("triggerTimes");
        triggerTimes.put("type", "array");
        triggerTimes.set("items", factory.objectNode().put("type", "string").put("description", "HH:mm"));
        triggerTimes.put("description", "每日触发时间列表，如 [\"09:00\", \"20:00\"]；留空时可用 triggerTime 指定单个时间");
        ObjectNode triggerTime = properties.putObject("triggerTime");
        triggerTime.put("type", "string");
        triggerTime.put("description", "单个每日触发时间 HH:mm（triggerTimes 为空时使用）");
        ObjectNode topics = properties.putObject("topics");
        topics.put("type", "array");
        topics.set("items", factory.objectNode().put("type", "string"));
        topics.put("description", "多日主题队列，每天按队列顺序取一个主题（可选）");
        ObjectNode audience = properties.putObject("audience");
        audience.put("type", "string");
        audience.put("description", "目标读者（可选）");
        ObjectNode tone = properties.putObject("tone");
        tone.put("type", "string");
        tone.put("description", "文风语气（可选）");
        ObjectNode timezone = properties.putObject("timezone");
        timezone.put("type", "string");
        timezone.put("description", "IANA 时区，默认 Asia/Shanghai");
        ObjectNode syncCsdn = properties.putObject("syncCsdn");
        syncCsdn.put("type", "boolean");
        syncCsdn.put("description", "是否同步发布到 CSDN，默认 false");
        ObjectNode syncJuejin = properties.putObject("syncJuejin");
        syncJuejin.put("type", "boolean");
        syncJuejin.put("description", "是否同步发布到掘金，默认 false");
        schema.putArray("required").add("topic");
        return schema;
    }

    @Override
    public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
        onProgress.checkCancellation();
        String topic = args.path("topic").asText("");
        if (!StringUtils.hasText(topic)) {
            throw new IllegalArgumentException("定时发文计划工具缺少参数 topic");
        }
        if (topic.trim().length() > 120) {
            throw new IllegalArgumentException(
                    "文章主题过长：" + topic.trim().length()
                            + " 字。用户素材只能作参考，请提炼为不超过 120 字的简短文章标题，不要把素材原文直接作为主题。");
        }
        BlogContentPlanCreateRequest request = new BlogContentPlanCreateRequest();
        request.setTopic(topic.trim());
        request.setTimezone(defaultText(args.path("timezone").asText(""), "Asia/Shanghai"));
        request.setTriggerTime(defaultText(args.path("triggerTime").asText(""), null));
        request.setTriggerTimes(stringArray(args.path("triggerTimes")));
        request.setTopics(stringArray(args.path("topics")));
        request.setAudience(defaultText(args.path("audience").asText(""), null));
        request.setTone(defaultText(args.path("tone").asText(""), null));
        if (args.path("syncCsdn").isBoolean()) {
            request.setSyncCsdn(args.path("syncCsdn").asBoolean());
        }
        if (args.path("syncJuejin").isBoolean()) {
            request.setSyncJuejin(args.path("syncJuejin").asBoolean());
        }
        onProgress.onProgress("正在创建定时发文计划…");
        BlogContentPlanVO plan = planService.create(userId, request);

        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("ok", true);
        outcome.put("planId", plan.getId());
        outcome.put("topic", defaultText(plan.getTopic(), ""));
        outcome.put("triggerTimes", plan.getTriggerTimes() == null ? new ArrayNode(JsonNodeFactory.instance) : toArray(plan.getTriggerTimes()));
        outcome.put("nextRunAt", plan.getNextRunAt() == null ? "" : plan.getNextRunAt().toString());
        outcome.put("status", defaultText(plan.getStatus(), ""));
        outcome.put("hint", "定时发文计划已创建，系统将按计划自动生成并发布文章。");
        return outcome;
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

    private ArrayNode toArray(List<String> values) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        values.forEach(array::add);
        return array;
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
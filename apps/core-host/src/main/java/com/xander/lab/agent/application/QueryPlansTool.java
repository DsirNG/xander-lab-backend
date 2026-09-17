package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.automation.spi.ContentPlanPort;
import com.xander.lab.automation.spi.ContentPlanView;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Lists the current user's recurring content plans (定时发文计划). A single
 * unpaged query returns all matching plans, optionally filtered by status, so
 * the agent never has to page through results.
 *
 * <p>依赖 {@link ContentPlanPort} 而不是 {@code BlogContentPlanService}：这个工具属于
 * agent 域，而那个实现类属于 automation 域，两者在拆服务后是两个进程。</p>
 */
@Component
public class QueryPlansTool implements AgentTool {

    private final ContentPlanPort planPort;

    public QueryPlansTool(ContentPlanPort planPort) {
        this.planPort = planPort;
    }

    @Override
    public String name() {
        return "query_plans";
    }

    /** 纯查询：不写任何状态，可与同批其它只读工具并发执行。 */
    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public String description() {
        return "查询当前用户的所有定时发文计划，一次调用返回全部（不分页），可按状态筛选。"
                + "当用户问“我有哪些定时发文计划”、“接下来/明天什么时候发文”、“有哪些计划在运行/暂停/失败/已完成”时使用本工具。"
                + "参数 status 可选 ACTIVE / PAUSED / RUNNING / FINISHED / FAILED / CANCELLED，省略或 ALL 返回全部；"
                + "每项返回 id、topic、status、triggerTimes（每日触发时间）、nextRunAt（下次触发时间）、syncCsdn、syncJuejin、runOnce 等信息。";
    }

    @Override
    public JsonNode parametersSchema() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode status = properties.putObject("status");
        status.put("type", "string");
        status.set("enum", JsonNodeFactory.instance.arrayNode()
                .add("ACTIVE").add("PAUSED").add("RUNNING").add("FINISHED").add("FAILED").add("CANCELLED").add("ALL"));
        status.put("description", "计划状态过滤，省略或 ALL 返回全部");
        return schema;
    }

    @Override
    public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
        onProgress.checkCancellation();
        String status = args.path("status").asText("");
        List<ContentPlanView> plans = planPort.listAll(userId, status);

        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("ok", true);
        outcome.put("total", plans.size());
        ArrayNode records = outcome.putArray("plans");
        for (ContentPlanView plan : plans) {
            ObjectNode item = records.addObject();
            item.put("id", plan.id());
            item.put("topic", defaultText(plan.topic(), ""));
            item.put("status", defaultText(plan.status(), ""));
            item.put("timezone", defaultText(plan.timezone(), ""));
            item.put("syncCsdn", Boolean.TRUE.equals(plan.syncCsdn()));
            item.put("syncJuejin", Boolean.TRUE.equals(plan.syncJuejin()));
            item.put("runOnce", Boolean.TRUE.equals(plan.runOnce()));
            item.put("nextRunAt", plan.nextRunAt() == null ? "" : plan.nextRunAt().toString());
            item.put("lastRunAt", plan.lastRunAt() == null ? "" : plan.lastRunAt().toString());
            ArrayNode triggerTimes = item.putArray("triggerTimes");
            if (plan.triggerTimes() != null) {
                plan.triggerTimes().forEach(triggerTimes::add);
            }
            if (StringUtils.hasText(plan.errorMessage())) {
                item.put("errorMessage", plan.errorMessage().trim());
            }
        }
        outcome.put("hint", "共返回 " + plans.size() + " 个定时发文计划。");
        return outcome;
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
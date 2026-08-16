package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.automation.api.EmailReminderTaskVO;
import com.xander.lab.automation.application.EmailReminderService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Lists the current user's scheduled email reminders (定时邮件提醒). A single
 * unpaged query returns all matching tasks, optionally filtered by status or
 * keyword, so the agent never has to page through results.
 */
@Component
public class QueryEmailRemindersTool implements AgentTool {

    private final EmailReminderService emailReminderService;

    public QueryEmailRemindersTool(EmailReminderService emailReminderService) {
        this.emailReminderService = emailReminderService;
    }

    @Override
    public String name() {
        return "query_email_reminders";
    }

    @Override
    public String description() {
        return "查询当前用户的所有定时邮件提醒任务，一次调用返回全部（不分页），可按状态或关键词筛选。"
                + "当用户问“我有哪些定时邮件”、“有哪些邮件待发送/已发送/发送失败”、“查一下发给某邮箱的提醒”时使用本工具。"
                + "参数 status 可选 PENDING / PAUSED / SENDING / SENT / FAILED，省略或 ALL 返回全部；"
                + "search 为邮件主题或收件邮箱关键词（可选）；"
                + "每项返回 id、recipientEmail、subject、frequency、status、scheduledAt（下次发送时间）、timezone。";
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
                .add("PENDING").add("PAUSED").add("SENDING").add("SENT").add("FAILED").add("ALL"));
        status.put("description", "任务状态过滤，省略或 ALL 返回全部");
        ObjectNode search = properties.putObject("search");
        search.put("type", "string");
        search.put("description", "邮件主题或收件邮箱关键词（可选）");
        return schema;
    }

    @Override
    public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
        onProgress.checkCancellation();
        String status = args.path("status").asText("");
        String search = args.path("search").asText("");
        List<EmailReminderTaskVO> tasks = emailReminderService.listAll(userId, status, search);

        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("ok", true);
        outcome.put("total", tasks.size());
        ArrayNode records = outcome.putArray("reminders");
        for (EmailReminderTaskVO task : tasks) {
            ObjectNode item = records.addObject();
            item.put("id", task.getId());
            item.put("recipientEmail", defaultText(task.getRecipientEmail(), ""));
            item.put("subject", defaultText(task.getSubject(), ""));
            item.put("frequency", defaultText(task.getFrequency(), ""));
            item.put("status", defaultText(task.getStatus(), ""));
            item.put("timezone", defaultText(task.getTimezone(), ""));
            item.put("sendTime", defaultText(task.getSendTime(), ""));
            item.put("scheduledAt", task.getScheduledAt() == null ? "" : task.getScheduledAt().toString());
            item.put("sentAt", task.getSentAt() == null ? "" : task.getSentAt().toString());
            if (StringUtils.hasText(task.getErrorMessage())) {
                item.put("errorMessage", task.getErrorMessage().trim());
            }
        }
        outcome.put("hint", "共返回 " + tasks.size() + " 个定时邮件提醒任务。");
        return outcome;
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
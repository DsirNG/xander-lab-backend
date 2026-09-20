package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.automation.spi.EmailReminderPort;
import com.xander.lab.automation.spi.EmailReminderView;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Lists the current user's scheduled email reminders (定时邮件提醒). A single
 * unpaged query returns all matching tasks, optionally filtered by status or
 * keyword, so the agent never has to page through results.
 *
 * <p>依赖 {@link EmailReminderPort} 而不是 {@code EmailReminderService}：这个工具属于
 * agent 域，而那个实现类属于 automation 域，两者在拆服务后是两个进程。</p>
 */
@Component
public class QueryEmailRemindersTool implements AgentTool {

    private final EmailReminderPort reminderPort;

    public QueryEmailRemindersTool(EmailReminderPort reminderPort) {
        this.reminderPort = reminderPort;
    }

    @Override
    public String name() {
        return "query_email_reminders";
    }

    /** 纯查询：不写任何状态，可与同批其它只读工具并发执行。 */
    @Override
    public boolean readOnly() {
        return true;
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
        List<EmailReminderView> tasks = reminderPort.listAll(userId, status, search);

        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("ok", true);
        outcome.put("total", tasks.size());
        ArrayNode records = outcome.putArray("reminders");
        for (EmailReminderView task : tasks) {
            ObjectNode item = records.addObject();
            item.put("id", task.id());
            item.put("recipientEmail", defaultText(task.recipientEmail(), ""));
            item.put("subject", defaultText(task.subject(), ""));
            item.put("frequency", defaultText(task.frequency(), ""));
            item.put("status", defaultText(task.status(), ""));
            item.put("timezone", defaultText(task.timezone(), ""));
            item.put("sendTime", defaultText(task.sendTime(), ""));
            item.put("scheduledAt", task.scheduledAt() == null ? "" : task.scheduledAt().toString());
            item.put("sentAt", task.sentAt() == null ? "" : task.sentAt().toString());
            if (StringUtils.hasText(task.errorMessage())) {
                item.put("errorMessage", task.errorMessage().trim());
            }
        }
        outcome.put("hint", "共返回 " + tasks.size() + " 个定时邮件提醒任务。");
        return outcome;
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
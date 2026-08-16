package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.automation.api.EmailReminderTaskVO;
import com.xander.lab.automation.application.EmailReminderService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Manages a scheduled email reminder (定时邮件提醒): pause, resume or delete
 * an existing task owned by the current user. State transitions are enforced
 * by the reminder service; the tool only translates the user's request into
 * the matching action.
 */
@Component
public class UpdateEmailReminderTool implements AgentTool {

    private final EmailReminderService emailReminderService;

    public UpdateEmailReminderTool(EmailReminderService emailReminderService) {
        this.emailReminderService = emailReminderService;
    }

    @Override
    public String name() {
        return "update_email_reminder";
    }

    @Override
    public String description() {
        return "对当前用户已有的定时邮件提醒任务执行暂停、恢复或删除操作。"
                + "参数 taskId 为任务 id（可用 query_email_reminders 查询），action 取值 PAUSE / RESUME / DELETE。"
                + "当用户说“把发给 X 的邮件提醒暂停/停掉/恢复/删除”、“先别发这个了”时使用本工具。"
                + "注意：删除不可恢复，执行前请先向用户确认。";
    }

    @Override
    public JsonNode parametersSchema() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode taskId = properties.putObject("taskId");
        taskId.put("type", "integer");
        taskId.put("description", "定时邮件任务 id（必填）");
        ObjectNode action = properties.putObject("action");
        action.put("type", "string");
        action.set("enum", JsonNodeFactory.instance.arrayNode()
                .add("PAUSE").add("RESUME").add("DELETE"));
        action.put("description", "操作类型：PAUSE 暂停 / RESUME 恢复 / DELETE 删除（必填）");
        schema.putArray("required").add("taskId").add("action");
        return schema;
    }

    @Override
    public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
        onProgress.checkCancellation();
        long taskId = args.path("taskId").asLong(-1L);
        String action = args.path("action").asText("").trim().toUpperCase();
        if (taskId <= 0) {
            throw new IllegalArgumentException("定时邮件工具缺少参数 taskId");
        }
        if (!StringUtils.hasText(action)) {
            throw new IllegalArgumentException("定时邮件工具缺少参数 action");
        }

        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("ok", true);
        outcome.put("taskId", taskId);
        outcome.put("action", action);
        switch (action) {
            case "PAUSE", "RESUME" -> {
                onProgress.onProgress("正在" + actionText(action) + "定时邮件提醒…");
                String targetStatus = "RESUME".equals(action)
                        ? EmailReminderService.STATUS_PENDING
                        : EmailReminderService.STATUS_PAUSED;
                EmailReminderTaskVO task = emailReminderService.updateStatus(userId, taskId, targetStatus);
                outcome.put("recipientEmail", defaultText(task.getRecipientEmail(), ""));
                outcome.put("subject", defaultText(task.getSubject(), ""));
                outcome.put("status", defaultText(task.getStatus(), ""));
                outcome.put("scheduledAt", task.getScheduledAt() == null ? "" : task.getScheduledAt().toString());
                outcome.put("hint", "定时邮件提醒已" + actionText(action) + "。");
            }
            case "DELETE" -> {
                onProgress.onProgress("正在删除定时邮件提醒…");
                emailReminderService.delete(userId, taskId);
                outcome.put("hint", "定时邮件提醒已删除。");
            }
            default -> throw new IllegalArgumentException("操作类型只支持 PAUSE / RESUME / DELETE");
        }
        return outcome;
    }

    private String actionText(String action) {
        return switch (action) {
            case "PAUSE" -> "暂停";
            case "RESUME" -> "恢复";
            default -> "删除";
        };
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
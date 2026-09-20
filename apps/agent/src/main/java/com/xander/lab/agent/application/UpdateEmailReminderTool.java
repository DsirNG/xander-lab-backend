package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.automation.spi.EmailReminderPort;
import com.xander.lab.automation.spi.EmailReminderView;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;

/**
 * Manages a scheduled email reminder (定时邮件提醒): pause, resume or delete
 * an existing task owned by the current user. State transitions are enforced
 * by the reminder service; the tool only translates the user's request into
 * the matching action. The pause action accepts both PAUSE and PAUSED because
 * the model may reuse the task status vocabulary.
 *
 * <p>依赖 {@link EmailReminderPort} 而不是 {@code EmailReminderService}：这个工具属于
 * agent 域，而那个实现类属于 automation 域，两者在拆服务后是两个进程。
 * 两个状态常量也随之从实现类搬到端口上 —— 否则这里仍要 import 实现类才能拿到它们。</p>
 */
@Component
public class UpdateEmailReminderTool implements AgentTool {

    private final EmailReminderPort reminderPort;

    public UpdateEmailReminderTool(EmailReminderPort reminderPort) {
        this.reminderPort = reminderPort;
    }

    @Override
    public String name() {
        return "update_email_reminder";
    }

    @Override
    public String description() {
        return "对当前用户已有的定时邮件提醒任务执行暂停、恢复或删除操作。"
                + "参数 taskId 为任务 id（可用 query_email_reminders 查询），action 取值 PAUSE / PAUSED / RESUME / DELETE。"
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
                .add("PAUSE").add("PAUSED").add("RESUME").add("DELETE"));
        action.put("description", "操作类型：PAUSE/PAUSED 暂停 / RESUME 恢复 / DELETE 删除（必填）");
        schema.putArray("required").add("taskId").add("action");
        return schema;
    }

    @Override
    public Optional<String> approvalReason(JsonNode args) {
        String action = args.path("action").asText("").trim().toUpperCase(Locale.ROOT);
        return "DELETE".equals(action)
                ? Optional.of("删除定时邮件提醒，该操作不可恢复")
                : Optional.empty();
    }

    @Override
    public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
        onProgress.checkCancellation();
        long taskId = args.path("taskId").asLong(-1L);
        if (taskId <= 0) {
            throw new IllegalArgumentException("定时邮件工具缺少参数 taskId");
        }
        String action = normalizeAction(args.path("action").asText(""));

        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("ok", true);
        outcome.put("taskId", taskId);
        outcome.put("action", action);
        switch (action) {
            case "PAUSED", "RESUME" -> {
                onProgress.onProgress("正在" + actionText(action) + "定时邮件提醒…");
                String targetStatus = "RESUME".equals(action)
                        ? EmailReminderPort.STATUS_PENDING
                        : EmailReminderPort.STATUS_PAUSED;
                EmailReminderView task = reminderPort.updateStatus(userId, taskId, targetStatus);
                outcome.put("recipientEmail", defaultText(task.recipientEmail(), ""));
                outcome.put("subject", defaultText(task.subject(), ""));
                outcome.put("status", defaultText(task.status(), ""));
                outcome.put("scheduledAt", task.scheduledAt() == null ? "" : task.scheduledAt().toString());
                outcome.put("hint", "定时邮件提醒已" + actionText(action) + "。");
            }
            case "DELETE" -> {
                onProgress.onProgress("正在删除定时邮件提醒…");
                reminderPort.delete(userId, taskId);
                outcome.put("hint", "定时邮件提醒已删除。");
            }
            default -> throw new IllegalArgumentException("操作类型只支持 PAUSE / PAUSED / RESUME / DELETE");
        }
        return outcome;
    }

    /** Lenient action normalization: accepts both PAUSE and the PAUSED status. */
    private String normalizeAction(String raw) {
        String action = StringUtils.hasText(raw) ? raw.trim().toUpperCase(Locale.ROOT) : "";
        return switch (action) {
            case "PAUSE", "PAUSED" -> "PAUSED";
            case "RESUME" -> "RESUME";
            case "DELETE" -> "DELETE";
            default -> throw new IllegalArgumentException("操作类型只支持 PAUSE / PAUSED / RESUME / DELETE");
        };
    }

    private String actionText(String action) {
        return "PAUSED".equals(action) ? "暂停" : "恢复";
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}

package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.automation.spi.EmailReminderDraft;
import com.xander.lab.automation.spi.EmailReminderPort;
import com.xander.lab.automation.spi.EmailReminderView;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Creates a scheduled email reminder. The service owns recurrence calculation,
 * idempotency, quota limits and the durable send job; the tool only translates
 * the user's natural-language request into a validated create command.
 *
 * <p>依赖 {@link EmailReminderPort} 而不是 {@code EmailReminderService}：这个工具属于
 * agent 域，而那个实现类属于 automation 域，两者在拆服务后是两个进程。
 * 端口只暴露本工具真正用到的五个操作与两个状态常量。</p>
 */
@Component
public class EmailReminderTool implements AgentTool {

    private final EmailReminderPort reminderPort;

    public EmailReminderTool(EmailReminderPort reminderPort) {
        this.reminderPort = reminderPort;
    }

    @Override
    public String name() {
        return "email_reminder";
    }

    @Override
    public String description() {
        return "创建一条定时邮件提醒任务，到点后自动发送邮件到指定邮箱。"
                + "支持 ONCE（一次性）、DAILY（每天）、WEEKLY（每周某天）、MONTHLY（每月某天）、CUSTOM（每隔 N 天）五种频率。"
                + "用户要求定时发邮件、邮件提醒、每周给我发邮件时使用本工具。";
    }

    @Override
    public JsonNode parametersSchema() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode recipient = properties.putObject("recipientEmail");
        recipient.put("type", "string");
        recipient.put("description", "收件邮箱（必填）");
        ObjectNode subject = properties.putObject("subject");
        subject.put("type", "string");
        subject.put("description", "邮件主题（必填）");
        ObjectNode message = properties.putObject("message");
        message.put("type", "string");
        message.put("description", "邮件正文（必填）");
        ObjectNode frequency = properties.putObject("frequency");
        frequency.put("type", "string");
        frequency.set("enum", JsonNodeFactory.instance.arrayNode().add("ONCE").add("DAILY").add("WEEKLY").add("MONTHLY").add("CUSTOM"));
        frequency.put("description", "发送频率，默认 ONCE");
        ObjectNode scheduledAt = properties.putObject("scheduledAt");
        scheduledAt.put("type", "string");
        scheduledAt.put("description", "ONCE 频率的绝对发送时间（ISO-8601 带时区偏移），如 2026-08-17T09:00:00+08:00");
        ObjectNode sendTime = properties.putObject("sendTime");
        sendTime.put("type", "string");
        sendTime.put("description", "DAILY / WEEKLY / MONTHLY / CUSTOM 的每日发送时间 HH:mm");
        ObjectNode timezone = properties.putObject("timezone");
        timezone.put("type", "string");
        timezone.put("description", "IANA 时区，默认 Asia/Shanghai");
        ObjectNode recurrenceDay = properties.putObject("recurrenceDay");
        recurrenceDay.put("type", "integer");
        recurrenceDay.put("description", "WEEKLY 为 1-7（周一至周日），MONTHLY 为 1-31（每月几号）");
        ObjectNode intervalDays = properties.putObject("intervalDays");
        intervalDays.put("type", "integer");
        intervalDays.put("description", "CUSTOM 的间隔天数 1-365");
        ObjectNode contentType = properties.putObject("contentType");
        contentType.put("type", "string");
        contentType.set("enum", JsonNodeFactory.instance.arrayNode().add("PLAIN").add("HTML"));
        contentType.put("description", "内容格式，默认 PLAIN");
        ObjectNode templateId = properties.putObject("templateId");
        templateId.put("type", "string");
        templateId.set("enum", JsonNodeFactory.instance.arrayNode().add("classic").add("minimal").add("card").add("notice"));
        templateId.put("description", "邮件模板，默认 classic");
        schema.putArray("required").add("recipientEmail").add("subject").add("message");
        return schema;
    }

    @Override
    public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
        return execute(AgentToolContext.ofUser(userId), args, onProgress);
    }

    @Override
    public Optional<String> approvalReason(JsonNode args) {
        return Optional.of("创建定时邮件提醒，系统会在指定时间向收件人发送邮件");
    }

    @Override
    public JsonNode execute(AgentToolContext context, JsonNode args, ProgressListener onProgress) {
        onProgress.checkCancellation();
        String recipientEmail = args.path("recipientEmail").asText("").trim();
        String subject = args.path("subject").asText("").trim();
        String message = args.path("message").asText("");
        if (!StringUtils.hasText(recipientEmail)) {
            throw new IllegalArgumentException("定时邮件工具缺少参数 recipientEmail");
        }
        if (!StringUtils.hasText(subject)) {
            throw new IllegalArgumentException("定时邮件工具缺少参数 subject");
        }
        if (!StringUtils.hasText(message)) {
            throw new IllegalArgumentException("定时邮件工具缺少参数 message");
        }
        Integer recurrenceDay = args.path("recurrenceDay").isIntegralNumber()
                ? args.path("recurrenceDay").asInt()
                : null;
        Integer intervalDays = args.path("intervalDays").isIntegralNumber()
                ? args.path("intervalDays").asInt()
                : null;
        String scheduledAtText = args.path("scheduledAt").asText("");
        OffsetDateTime scheduledAt = StringUtils.hasText(scheduledAtText)
                ? OffsetDateTime.parse(scheduledAtText)
                : null;

        // ⚠️ 记录类型靠位置构造：分量顺序必须与 EmailReminderDraft 的声明完全一致，
        // 写反不会有任何编译错误。顺序由契约侧的 RecordShapeTest 钉住，
        // 而"哪个分量落到哪个字段"由本工具的测试用互不相同的字面量钉住。
        EmailReminderDraft draft = new EmailReminderDraft(
                context.invocationId(),
                recipientEmail,
                subject,
                message,
                defaultText(args.path("frequency").asText(""), "ONCE"),
                defaultText(args.path("timezone").asText(""), "Asia/Shanghai"),
                scheduledAt,
                defaultText(args.path("sendTime").asText(""), null),
                recurrenceDay,
                intervalDays,
                defaultText(args.path("contentType").asText(""), "PLAIN"),
                defaultText(args.path("templateId").asText(""), "classic"));
        onProgress.onProgress("正在创建定时邮件提醒…");
        EmailReminderView task = reminderPort.create(context.userId(), draft);

        return toOutcome(task);
    }

    @Override
    public Optional<JsonNode> reconcile(AgentToolContext context, JsonNode args) {
        if (!StringUtils.hasText(context.invocationId())) {
            return Optional.empty();
        }
        EmailReminderView task = reminderPort.findByClientRequestId(
                context.userId(), context.invocationId());
        return task == null ? Optional.empty() : Optional.of(toOutcome(task));
    }

    private ObjectNode toOutcome(EmailReminderView task) {
        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("ok", true);
        outcome.put("taskId", task.id());
        outcome.put("recipientEmail", defaultText(task.recipientEmail(), ""));
        outcome.put("subject", defaultText(task.subject(), ""));
        outcome.put("frequency", defaultText(task.frequency(), ""));
        outcome.put("scheduledAt", task.scheduledAt() == null ? "" : task.scheduledAt().toString());
        outcome.put("status", defaultText(task.status(), ""));
        outcome.put("hint", "定时邮件提醒已创建，到点会自动发送到 " + defaultText(task.recipientEmail(), "") + "。");
        return outcome;
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}

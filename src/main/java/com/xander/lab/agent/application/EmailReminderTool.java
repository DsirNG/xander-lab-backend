package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.automation.api.EmailReminderCreateRequest;
import com.xander.lab.automation.api.EmailReminderTaskVO;
import com.xander.lab.automation.application.EmailReminderService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Creates a scheduled email reminder. The service owns recurrence calculation,
 * idempotency, quota limits and the durable send job; the tool only translates
 * the user's natural-language request into a validated create command.
 */
@Component
public class EmailReminderTool implements AgentTool {

    private final EmailReminderService emailReminderService;

    public EmailReminderTool(EmailReminderService emailReminderService) {
        this.emailReminderService = emailReminderService;
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
    public JsonNode execute(AgentToolContext context, JsonNode args, ProgressListener onProgress) {
        onProgress.checkCancellation();
        EmailReminderCreateRequest request = new EmailReminderCreateRequest();
        request.setRecipientEmail(args.path("recipientEmail").asText("").trim());
        request.setSubject(args.path("subject").asText("").trim());
        request.setMessage(args.path("message").asText(""));
        if (!StringUtils.hasText(request.getRecipientEmail())) {
            throw new IllegalArgumentException("定时邮件工具缺少参数 recipientEmail");
        }
        if (!StringUtils.hasText(request.getSubject())) {
            throw new IllegalArgumentException("定时邮件工具缺少参数 subject");
        }
        if (!StringUtils.hasText(request.getMessage())) {
            throw new IllegalArgumentException("定时邮件工具缺少参数 message");
        }
        request.setFrequency(defaultText(args.path("frequency").asText(""), "ONCE"));
        request.setTimezone(defaultText(args.path("timezone").asText(""), "Asia/Shanghai"));
        request.setSendTime(defaultText(args.path("sendTime").asText(""), null));
        request.setContentType(defaultText(args.path("contentType").asText(""), "PLAIN"));
        request.setTemplateId(defaultText(args.path("templateId").asText(""), "classic"));
        if (args.path("recurrenceDay").isIntegralNumber()) {
            request.setRecurrenceDay(args.path("recurrenceDay").asInt());
        }
        if (args.path("intervalDays").isIntegralNumber()) {
            request.setIntervalDays(args.path("intervalDays").asInt());
        }
        String scheduledAt = args.path("scheduledAt").asText("");
        if (StringUtils.hasText(scheduledAt)) {
            request.setScheduledAt(OffsetDateTime.parse(scheduledAt));
        }
        request.setClientRequestId(context.invocationId());
        onProgress.onProgress("正在创建定时邮件提醒…");
        EmailReminderTaskVO task = emailReminderService.create(context.userId(), request);

        return toOutcome(task);
    }

    @Override
    public Optional<JsonNode> reconcile(AgentToolContext context, JsonNode args) {
        if (!StringUtils.hasText(context.invocationId())) {
            return Optional.empty();
        }
        EmailReminderTaskVO task = emailReminderService.findByClientRequestId(
                context.userId(), context.invocationId());
        return task == null ? Optional.empty() : Optional.of(toOutcome(task));
    }

    private ObjectNode toOutcome(EmailReminderTaskVO task) {
        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("ok", true);
        outcome.put("taskId", task.getId());
        outcome.put("recipientEmail", defaultText(task.getRecipientEmail(), ""));
        outcome.put("subject", defaultText(task.getSubject(), ""));
        outcome.put("frequency", defaultText(task.getFrequency(), ""));
        outcome.put("scheduledAt", task.getScheduledAt() == null ? "" : task.getScheduledAt().toString());
        outcome.put("status", defaultText(task.getStatus(), ""));
        outcome.put("hint", "定时邮件提醒已创建，到点会自动发送到 " + defaultText(task.getRecipientEmail(), "") + "。");
        return outcome;
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}

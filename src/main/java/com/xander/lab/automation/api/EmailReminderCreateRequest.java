package com.xander.lab.automation.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class EmailReminderCreateRequest {

    @Size(max = 64, message = "幂等请求标识不能超过 64 个字符")
    @Pattern(regexp = "[A-Za-z0-9._:-]+", message = "幂等请求标识格式不正确")
    private String clientRequestId;

    @NotBlank(message = "请输入收件邮箱")
    @Email(message = "收件邮箱格式不正确")
    @Size(max = 254, message = "收件邮箱不能超过 254 个字符")
    private String recipientEmail;

    /**
     * ONCE | DAILY | WEEKLY | MONTHLY | CUSTOM. Defaults to ONCE when blank.
     */
    @Size(max = 16, message = "发送频率无效")
    private String frequency;

    /**
     * IANA timezone id. Defaults to Asia/Shanghai.
     */
    @Size(max = 64, message = "时区不能超过 64 个字符")
    private String timezone;

    /**
     * Required for ONCE. Absolute send time with offset.
     */
    private OffsetDateTime scheduledAt;

    /**
     * Required for DAILY / WEEKLY / MONTHLY / CUSTOM. Format HH:mm in the selected timezone.
     */
    @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "发送时间格式应为 HH:mm")
    private String sendTime;

    /**
     * WEEKLY: ISO weekday 1-7 (Mon-Sun). MONTHLY: day of month 1-31.
     */
    @Min(value = 1, message = "周期日无效")
    @Max(value = 31, message = "周期日无效")
    private Integer recurrenceDay;

    /**
     * CUSTOM: send every N days.
     */
    @Min(value = 1, message = "间隔天数至少为 1")
    @Max(value = 365, message = "间隔天数不能超过 365")
    private Integer intervalDays;

    @NotBlank(message = "请输入邮件主题")
    @Size(max = 200, message = "邮件主题不能超过 200 个字符")
    private String subject;

    @NotBlank(message = "请输入想说的话")
    @Size(max = 10000, message = "邮件内容不能超过 10000 个字符")
    private String message;

    /**
     * PLAIN | HTML. Defaults to PLAIN when blank.
     */
    @Size(max = 16, message = "内容格式无效")
    private String contentType;

    /**
     * classic | minimal | card | notice. Defaults to classic when blank.
     */
    @Size(max = 32, message = "邮件模板无效")
    private String templateId;
}

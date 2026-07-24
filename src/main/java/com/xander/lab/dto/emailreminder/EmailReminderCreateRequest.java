package com.xander.lab.dto.emailreminder;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    @NotNull(message = "请选择发送时间")
    @Future(message = "发送时间必须晚于当前时间")
    private OffsetDateTime scheduledAt;

    @NotBlank(message = "请输入邮件主题")
    @Size(max = 200, message = "邮件主题不能超过 200 个字符")
    private String subject;

    @NotBlank(message = "请输入想说的话")
    @Size(max = 10000, message = "邮件内容不能超过 10000 个字符")
    private String message;
}

package com.xander.lab.blogagent.api;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BlogAgentTaskCreateRequest {
    @NotBlank(message = "请输入主题或日记")
    @jakarta.validation.constraints.Size(max = 20000, message = "输入内容不能超过 20000 个字符")
    private String input;

    /** Optional stable caller key used to make a task-creation request idempotent per user. */
    @jakarta.validation.constraints.Size(max = 64, message = "请求幂等标识不能超过 64 个字符")
    private String clientRequestId;
}

package com.xander.lab.blogagent.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** HTTP request owned by content-automation for creating a durable BlogAgent task. */
@Data
public class BlogAgentTaskCreateRequest {

    @NotBlank(message = "请输入主题或日记")
    @Size(max = 20000, message = "输入内容不能超过 20000 个字符")
    private String input;

    @Size(max = 64, message = "请求幂等标识不能超过 64 个字符")
    private String clientRequestId;
}

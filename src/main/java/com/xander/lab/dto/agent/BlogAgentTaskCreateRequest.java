package com.xander.lab.dto.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BlogAgentTaskCreateRequest {
    @NotBlank(message = "请输入主题或日记")
    @Size(max = 20000, message = "输入内容不能超过 20000 个字符")
    private String input;
    private String inputType = "topic";
    @Size(max = 120)
    private String audience = "对这个主题感兴趣的普通读者";
    @Size(max = 60)
    private String tone = "清晰、真诚、可操作";
}

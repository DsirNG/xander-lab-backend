package com.xander.lab.dto.agent;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BlogAgentTaskCreateRequest {
    @NotBlank(message = "请输入主题或日记")
    @jakarta.validation.constraints.Size(max = 20000, message = "输入内容不能超过 20000 个字符")
    private String input;
}

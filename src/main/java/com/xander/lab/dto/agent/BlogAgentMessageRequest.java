package com.xander.lab.dto.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BlogAgentMessageRequest {
    @NotBlank(message = "修改要求不能为空")
    @Size(max = 4000, message = "修改要求不能超过 4000 个字符")
    private String content;
}

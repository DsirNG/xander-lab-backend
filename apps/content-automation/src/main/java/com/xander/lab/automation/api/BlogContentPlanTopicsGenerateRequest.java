package com.xander.lab.automation.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** Body for generating a publishable topic series. */
@Data
public class BlogContentPlanTopicsGenerateRequest {

    @NotBlank(message = "请输入主题种子")
    @Size(max = 500, message = "主题种子不能超过 500 个字符")
    private String topic;

    @Min(value = 1, message = "至少生成 1 个主题")
    @Max(value = 30, message = "最多生成 30 个主题")
    private int count = 7;

    @Size(max = 120, message = "目标读者不能超过 120 个字符")
    private String audience;

    @Size(max = 60, message = "文风语气不能超过 60 个字符")
    private String tone;

    @Size(max = 200, message = "排除标题过多")
    private List<@Size(max = 500, message = "排除标题不能超过 500 个字符") String> excludeTitles;
}

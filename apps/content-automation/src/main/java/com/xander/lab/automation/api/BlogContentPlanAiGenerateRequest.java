package com.xander.lab.automation.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Body for generating one-shot plans from an AI topic series. */
@Data
public class BlogContentPlanAiGenerateRequest {

    @NotBlank(message = "请输入主题方向")
    @Size(max = 500, message = "主题方向不能超过 500 个字符")
    private String topic;

    @Min(value = 1, message = "天数至少为 1")
    @Max(value = 30, message = "天数最多为 30")
    private Integer days;

    @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "触发时间格式应为 HH:mm")
    private String time;

    @Size(max = 64, message = "时区不能超过 64 个字符")
    private String timezone;

    private Boolean syncCsdn;
    private Boolean syncJuejin;

    @Size(max = 120, message = "目标读者不能超过 120 个字符")
    private String audience;

    @Size(max = 60, message = "文风语气不能超过 60 个字符")
    private String tone;
}

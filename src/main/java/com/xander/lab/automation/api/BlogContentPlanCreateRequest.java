package com.xander.lab.automation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Body for creating a daily content plan. */
@Data
public class BlogContentPlanCreateRequest {

    @NotBlank(message = "请输入文章主题")
    @Size(max = 500, message = "文章主题不能超过 500 个字符")
    private String topic;

    /** IANA timezone id. Defaults to Asia/Shanghai when blank. */
    @Size(max = 64, message = "时区不能超过 64 个字符")
    private String timezone;

    /** Daily local trigger time, format HH:mm in the selected timezone. */
    @NotBlank(message = "请设置每日触发时间")
    @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "触发时间格式应为 HH:mm")
    private String triggerTime;

    /** Whether this plan also publishes the generated article to CSDN. */
    private Boolean syncCsdn;

    @Size(max = 120, message = "目标读者不能超过 120 个字符")
    private String audience;

    @Size(max = 60, message = "文风语气不能超过 60 个字符")
    private String tone;
}
package com.xander.lab.automation.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Body for AI 生成计划: asks the agent to refine a seed topic into `days`
 * one-shot plans, one per day starting tomorrow at the same local `time`. */
@Data
public class BlogContentPlanAiGenerateRequest {

    /** Seed topic/direction the agent refines into one topic per day. */
    @NotBlank(message = "请输入主题方向")
    @Size(max = 500, message = "主题方向不能超过 500 个字符")
    private String topic;

    /** Number of one-shot plans to create, 1-30, default 7. */
    @Min(value = 1, message = "天数至少为 1")
    @Max(value = 30, message = "天数最多为 30")
    private Integer days;

    /** Daily local trigger time, format HH:mm in the selected timezone.
     * Defaults to 09:00 when blank. */
    @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "触发时间格式应为 HH:mm")
    private String time;

    /** IANA timezone id. Defaults to Asia/Shanghai when blank. */
    @Size(max = 64, message = "时区不能超过 64 个字符")
    private String timezone;

    /** Whether each generated plan also publishes its article to CSDN. */
    private Boolean syncCsdn;

    /** Whether each generated plan also publishes its article to 掘金. */
    private Boolean syncJuejin;

    @Size(max = 120, message = "目标读者不能超过 120 个字符")
    private String audience;

    @Size(max = 60, message = "文风语气不能超过 60 个字符")
    private String tone;
}

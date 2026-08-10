package com.xander.lab.automation.api;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Body for editing an existing daily content plan. Only editable while the
 * plan is ACTIVE or PAUSED; a RUNNING plan rejects updates. */
@Data
public class BlogContentPlanUpdateRequest {

    @Size(max = 500, message = "文章主题不能超过 500 个字符")
    private String topic;

    @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "触发时间格式应为 HH:mm")
    private String triggerTime;

    @Size(max = 64, message = "时区不能超过 64 个字符")
    private String timezone;

    private Boolean syncCsdn;

    @Size(max = 120, message = "目标读者不能超过 120 个字符")
    private String audience;

    @Size(max = 60, message = "文风语气不能超过 60 个字符")
    private String tone;
}
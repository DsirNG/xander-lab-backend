package com.xander.lab.automation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** Body for pausing, resuming, or cancelling a content plan. */
@Data
public class BlogContentPlanStatusRequest {

    @NotBlank(message = "请选择计划操作")
    @Pattern(regexp = "PAUSED|RESUME|CANCELLED",
            message = "计划操作只支持 PAUSED / RESUME / CANCELLED")
    private String action;
}

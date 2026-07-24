package com.xander.lab.dto.emailreminder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class EmailReminderStatusUpdateRequest {

    @NotBlank(message = "请选择任务状态")
    @Pattern(regexp = "PENDING|PAUSED", message = "任务状态只支持 PENDING 或 PAUSED")
    private String status;
}

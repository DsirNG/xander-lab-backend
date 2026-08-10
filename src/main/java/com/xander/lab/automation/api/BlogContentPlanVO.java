package com.xander.lab.automation.api;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** View of a daily content plan for C-end APIs. `nextRunAt` is the absolute
 * UTC instant; the frontend renders it after converting to the plan timezone. */
@Data
@Builder
public class BlogContentPlanVO {
    private Long id;
    private String topic;
    private String audience;
    private String tone;
    private String timezone;
    private String triggerTime;
    private Boolean syncCsdn;
    private Instant nextRunAt;
    private Instant lastRunAt;
    private String status;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;
    /** Whether the CSDN platform is currently authorized for this user. */
    private Boolean csdnAuthorized;
}
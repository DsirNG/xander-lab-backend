package com.xander.lab.automation.api;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/** View of a daily content plan for C-end APIs. `nextRunAt` is the absolute
 * UTC instant; the frontend renders it after converting to the plan timezone. */
@Data
@Builder
public class BlogContentPlanVO {
    private Long id;
    private String topic;
    private List<String> triggerTimes;
    private List<String> topics;
    private Integer nextTopicIndex;
    private String audience;
    private String tone;
    private String timezone;
    private String triggerTime;
    private Boolean syncCsdn;
    private Boolean syncJuejin;
    /** True for one-shot plans: fires exactly once, then auto-finishes. */
    private Boolean runOnce;
    private Instant nextRunAt;
    private Instant lastRunAt;
    private String status;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;
    /** Whether the CSDN platform is currently authorized for this user. */
    private Boolean csdnAuthorized;
    /** Whether the 掘金 platform is currently authorized for this user. */
    private Boolean juejinAuthorized;
}
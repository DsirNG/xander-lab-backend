package com.xander.lab.automation.api;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** View of a content plan returned by the C-end APIs. */
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
    private String scheduleType;
    private LocalDate scheduledDate;
    private String aiOption;
    private Long knowledgeMaterialId;
    private Boolean autoPublish;
    private String timezone;
    private String triggerTime;
    private Boolean syncCsdn;
    private Boolean syncJuejin;
    private Boolean runOnce;
    private Instant nextRunAt;
    private Instant lastRunAt;
    private String status;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;
    private Boolean csdnAuthorized;
    private Boolean juejinAuthorized;
}

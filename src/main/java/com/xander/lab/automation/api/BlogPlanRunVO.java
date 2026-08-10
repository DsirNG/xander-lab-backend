package com.xander.lab.automation.api;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** View of one plan run (trigger). */
@Data
@Builder
public class BlogPlanRunVO {
    private Long id;
    private Long planId;
    private Instant scheduledAt;
    private String triggerType;
    private String status;
    private String stageError;
    private String errorMessage;
    private Long agentTaskId;
    private Long localPostId;
    private Boolean reviewPass;
    private String reviewReason;
    private String csdnStatus;
    private String csdnErrorCode;
    private String csdnErrorMessage;
    private String csdnExternalId;
    private String csdnUrl;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant createdAt;
}
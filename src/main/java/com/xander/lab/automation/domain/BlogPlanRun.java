package com.xander.lab.automation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** One durable run row per plan trigger. plan_id + scheduled_at is the
 * idempotency key so a crashed or replayed poller can never run twice. */
@Data
@TableName("blog_plan_run")
public class BlogPlanRun {

    public static final String TRIGGER_SCHEDULED = "SCHEDULED";
    public static final String TRIGGER_MANUAL = "MANUAL";

    public static final String STATUS_GENERATING = "GENERATING";
    public static final String STATUS_REVIEWING = "REVIEWING";
    public static final String STATUS_PUBLISHING = "PUBLISHING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_REVIEW_REJECTED = "REVIEW_REJECTED";

    public static final String STAGE_GENERATE = "generate";
    public static final String STAGE_REVIEW = "review";
    public static final String STAGE_PUBLISH = "publish";

    public static final String CSDN_UNSET = "UNSET";
    public static final String CSDN_PENDING = "PENDING";
    public static final String CSDN_SUCCEEDED = "SUCCEEDED";
    public static final String CSDN_FAILED = "FAILED";
    public static final String CSDN_SKIPPED = "SKIPPED";

    public static final String JUEJIN_UNSET = "UNSET";
    public static final String JUEJIN_PENDING = "PENDING";
    public static final String JUEJIN_SUCCEEDED = "SUCCEEDED";
    public static final String JUEJIN_FAILED = "FAILED";
    public static final String JUEJIN_SKIPPED = "SKIPPED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long planId;
    private Instant scheduledAt;
    private String triggerType;
    private String status;
    private String stageError;
    private String errorMessage;
    private Long agentTaskId;
    private Long localPostId;
    /** NULL = not reviewed, 1 = passed, 0 = rejected */
    private Boolean reviewPass;
    private String reviewReason;
    private String csdnStatus;
    private String csdnErrorCode;
    private String csdnErrorMessage;
    private String csdnExternalId;
    private String csdnUrl;
    private String juejinStatus;
    private String juejinErrorCode;
    private String juejinErrorMessage;
    private String juejinExternalId;
    private String juejinUrl;
    private String processingToken;
    private Instant claimedAt;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
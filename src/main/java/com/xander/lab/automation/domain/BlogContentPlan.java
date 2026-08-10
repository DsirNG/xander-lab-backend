package com.xander.lab.automation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** Daily content plan: a single recurring topic, a user-provided local
 * trigger time, and whether that run should also sync to CSDN.
 * Scheduling instants are stored as absolute UTC instants. */
@Data
@TableName("blog_content_plan")
public class BlogContentPlan {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String topic;
    private String audience;
    private String tone;
    /** IANA timezone, e.g. Asia/Shanghai */
    private String timezone;
    /** HH:mm local trigger time in `timezone` */
    private String triggerTime;
    private Boolean syncCsdn;
    private Instant nextRunAt;
    private Instant lastRunAt;
    private String status;
    private String errorMessage;
    private String processingToken;
    private Instant claimedAt;
    private Instant deletedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
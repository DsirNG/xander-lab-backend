package com.xander.lab.automation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** User-facing notification produced by background plan runs. Serves as the
 * durable fallback for users who are offline at trigger time; the Navbar bell
 * polls or subscribes to this table. */
@Data
@TableName("blog_notification")
public class BlogNotification {

    public static final String TYPE_REVIEW_REJECTED = "REVIEW_REJECTED";
    public static final String TYPE_CSDN_FAILED = "CSDN_FAILED";
    public static final String TYPE_RUN_FAILED = "RUN_FAILED";
    public static final String TYPE_PLAN_STALLED = "PLAN_STALLED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String type;
    private String title;
    private String message;
    private Long planId;
    private Long runId;
    private Boolean isRead;
    private Instant createdAt;
    private Instant updatedAt;
}
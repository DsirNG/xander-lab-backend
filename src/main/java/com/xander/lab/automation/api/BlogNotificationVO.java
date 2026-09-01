package com.xander.lab.automation.api;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** View of a user notification for the Navbar bell. */
@Data
@Builder
public class BlogNotificationVO {
    private Long id;
    private String type;
    private String title;
    private String message;
    private Long planId;
    private Long runId;
    /** 通用关联资源类型，如 KNOWLEDGE_FILE；前端据此决定点击通知跳去哪里。 */
    private String resourceType;
    private Long resourceId;
    private Boolean isRead;
    private Instant createdAt;
}
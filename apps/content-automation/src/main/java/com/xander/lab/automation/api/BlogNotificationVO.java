package com.xander.lab.automation.api;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** Notification view returned to the authenticated notification bell. */
@Data
@Builder
public class BlogNotificationVO {

    private Long id;
    private String type;
    private String title;
    private String message;
    private Long planId;
    private Long runId;
    private String resourceType;
    private Long resourceId;
    private Boolean isRead;
    private Instant createdAt;
}

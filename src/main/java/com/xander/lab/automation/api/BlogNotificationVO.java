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
    private Boolean isRead;
    private Instant createdAt;
}
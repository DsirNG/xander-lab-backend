package com.xander.lab.blogagent.domain;

import lombok.Data;

import java.time.LocalDateTime;

/** Replayable BlogAgent stream event owned by content-automation. */
@Data
public class BlogAgentEvent {
    private Long id;
    private Long taskId;
    private String eventType;
    private String dataContent;
    private LocalDateTime createdAt;
}

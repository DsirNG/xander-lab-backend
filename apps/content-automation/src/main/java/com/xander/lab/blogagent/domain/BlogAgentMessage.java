package com.xander.lab.blogagent.domain;

import lombok.Data;

import java.time.LocalDateTime;

/** Durable progress/message entry attached to a BlogAgent task read projection. */
@Data
public class BlogAgentMessage {
    private Long id;
    private Long taskId;
    private String role;
    private String kind;
    private String stage;
    private String content;
    private LocalDateTime createdAt;
}

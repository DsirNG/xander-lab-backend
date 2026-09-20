package com.xander.lab.blogagent.domain;

import lombok.Data;

import java.time.LocalDateTime;

/** Content-automation read projection of one durable BlogAgent task. */
@Data
public class BlogAgentTask {
    private Long id;
    private Long userId;
    private String clientRequestId;
    private String input;
    private String inputType;
    private String audience;
    private String tone;
    private String status;
    private String stage;
    private String title;
    private String summary;
    private String content;
    private String outline;
    private String contentBoundary;
    private String knowledgeGraphJson;
    private String categoryId;
    private String tagsJson;
    private String review;
    private String illustrationStatus;
    private String illustrationError;
    private Long publishedPostId;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

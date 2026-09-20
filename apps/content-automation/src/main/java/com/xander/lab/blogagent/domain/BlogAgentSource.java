package com.xander.lab.blogagent.domain;

import lombok.Data;

import java.time.LocalDateTime;

/** Evidence source attached to a BlogAgent task read projection. */
@Data
public class BlogAgentSource {
    private Long id;
    private Long taskId;
    private String title;
    private String url;
    private String publisher;
    private String excerpt;
    private String reliability;
    private LocalDateTime retrievedAt;
}

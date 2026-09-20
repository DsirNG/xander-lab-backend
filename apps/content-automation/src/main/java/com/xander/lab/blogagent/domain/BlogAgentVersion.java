package com.xander.lab.blogagent.domain;

import lombok.Data;

import java.time.LocalDateTime;

/** Version snapshot attached to a BlogAgent task read projection. */
@Data
public class BlogAgentVersion {
    private Long id;
    private Long taskId;
    private Integer versionNo;
    private String title;
    private String summary;
    private String content;
    private String changeNote;
    private LocalDateTime createdAt;
}

package com.xander.lab.blogagent.api;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class BlogAgentSessionVO {
    private Long id;
    private String title;
    private String input;
    private String status;
    private String stage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

package com.xander.lab.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("blog_agent_task")
public class BlogAgentTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
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
    private Long publishedPostId;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

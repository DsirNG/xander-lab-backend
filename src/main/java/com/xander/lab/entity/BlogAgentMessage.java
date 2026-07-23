package com.xander.lab.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("blog_agent_message")
public class BlogAgentMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private String role;
    private String kind;
    private String stage;
    private String content;
    private LocalDateTime createdAt;
}

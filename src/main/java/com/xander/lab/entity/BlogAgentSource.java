package com.xander.lab.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("blog_agent_source")
public class BlogAgentSource {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private String title;
    private String url;
    private String publisher;
    private String excerpt;
    private String reliability;
    private LocalDateTime retrievedAt;
}

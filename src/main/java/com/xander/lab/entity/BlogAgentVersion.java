package com.xander.lab.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("blog_agent_version")
public class BlogAgentVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Integer versionNo;
    private String title;
    private String summary;
    private String content;
    private String changeNote;
    private LocalDateTime createdAt;
}

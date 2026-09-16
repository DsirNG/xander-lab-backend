package com.xander.lab.publishing.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable status of one task's publication attempt on one platform. */
@Data
@TableName("blog_publish_record")
public class BlogPublishRecord {
    @TableId(type = IdType.AUTO) private Long id;
    private Long taskId; private Long userId; private String platform;
    private String externalId; private String url; private String status;
    private String errorCode; private String errorMessage; private Integer attempts;
    private LocalDateTime publishedAt; private LocalDateTime createdAt; private LocalDateTime updatedAt;
}

package com.xander.lab.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("blog_media_asset")
public class BlogMediaAsset {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String url;
    private String originalName;
    private String storedName;
    private Long size;
    private String contentType;
    private Integer width;
    private Integer height;
    private LocalDateTime createdAt;
}

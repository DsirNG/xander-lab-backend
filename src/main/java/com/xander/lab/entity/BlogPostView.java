package com.xander.lab.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 博客阅读记录实体
 */
@Data
@TableName("blog_post_view")
public class BlogPostView {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 文章ID */
    private Long postId;

    /** 用户ID（未登录为NULL） */
    private Long userId;

    /** 客户端IP地址 */
    private String ipAddress;

    /** 浏览器UA */
    private String userAgent;

    /** 阅读时间 */
    private LocalDateTime createdAt;
}

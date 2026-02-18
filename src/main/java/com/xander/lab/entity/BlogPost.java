package com.xander.lab.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 博客文章实体
 */
@Data
@TableName("blog_post")
public class BlogPost {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 文章标题 */
    private String title;

    /** 文章摘要 */
    private String summary;

    /** 文章正文（Markdown） */
    private String content;

    /** 分类ID */
    private String categoryId;

    /** 作者用户ID */
    private Long userId;

    /** 作者（冗余展示名） */
    private String author;

    /** 预计阅读时间，如 "10 min" */
    private String readTime;

    /** 浏览次数 */
    private Integer views;

    /** 状态：0=草稿 1=已发布 */
    private Integer status;

    /** 发布日期 */
    private LocalDate publishedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

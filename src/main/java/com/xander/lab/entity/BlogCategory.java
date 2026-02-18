package com.xander.lab.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 博客分类实体
 */
@Data
@TableName("blog_category")
public class BlogCategory {

    /** 分类ID（英文标识，如 frontend） */
    @TableId
    private String id;

    /** 分类名称（中文） */
    private String name;

    /** 排序权重 */
    private Integer sort;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

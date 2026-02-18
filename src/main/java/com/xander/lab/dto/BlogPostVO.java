package com.xander.lab.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 博客文章 VO（返回给前端的数据结构）
 * 对应前端 blogService 中的 blog 对象字段
 */
@Data
public class BlogPostVO {

    /** 文章ID */
    private Long id;

    /** 文章标题 */
    private String title;

    /** 文章摘要 */
    private String summary;

    /** 文章正文（Markdown），列表接口不返回，详情接口返回 */
    private String content;

    /** 分类ID（如 frontend） */
    private String category;

    /** 分类名称（如 前端开发） */
    private String categoryName;

    /** 标签列表（字符串数组），序列化给前端 */
    private List<String> tags;

    /**
     * MyBatis 映射用：GROUP_CONCAT 结果（逗号分隔的标签字符串）
     * 不序列化给前端
     */
    @JsonIgnore
    private String tagsRaw;

    /** 作者用户ID */
    private Long userId;

    /** 作者 */
    private String author;

    /** 发布日期，格式 yyyy-MM-dd */
    private LocalDate date;

    /** 预计阅读时间 */
    private String readTime;

    /** 浏览次数 */
    private Integer views;

    /**
     * 将 tagsRaw 拆分为 tags 列表
     * 在 Service 层调用
     */
    public void parseTags() {
        if (tagsRaw != null && !tagsRaw.isBlank()) {
            this.tags = Arrays.asList(tagsRaw.split(","));
        } else {
            this.tags = Collections.emptyList();
        }
    }
}

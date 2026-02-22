package com.xander.lab.dto;

import lombok.Data;
import java.util.List;

/**
 * 发布博客请求 DTO
 */
@Data
public class BlogPostDTO {
    /** 文章标题 */
    private String title;

    /** 文章摘要 */
    private String summary;

    /** 文章正文（Markdown） */
    private String content;

    /** 分类ID */
    private String categoryId;

    /** 标签列表 */
    private List<String> tags;
}

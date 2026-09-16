package com.xander.lab.publishing.api;

import lombok.Data;

/**
 * 博客文章状态变更请求：0=草稿 1=已发布 -1=回收站
 */
@Data
public class BlogPostStatusUpdateRequest {
    private Integer status;
}

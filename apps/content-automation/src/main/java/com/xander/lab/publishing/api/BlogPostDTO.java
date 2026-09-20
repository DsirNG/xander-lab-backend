package com.xander.lab.publishing.api;

import lombok.Data;

import java.util.List;

/** Private publishing mutation request owned by content-automation. */
@Data
public class BlogPostDTO {
    private String title;
    private String summary;
    private String content;
    private String categoryId;
    private String coverImage;
    private List<String> tags;
    private Boolean publish;
}

package com.xander.lab.publishing.infrastructure;

import lombok.Data;

@Data
public class BlogArticleStatusCounts {
    private long totalArticles;
    private long drafts;
    private long published;
    private long trashed;
}

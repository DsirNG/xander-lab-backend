package com.xander.lab.publishing.api;

import lombok.Data;

/** Private publishing status mutation request owned by content-automation. */
@Data
public class BlogPostStatusUpdateRequest {
    private Integer status;
}

package com.xander.lab.publishing.api;

import lombok.Data;

/** Public blog category with the count of published posts. */
@Data
public class CategoryVO {
    private String id;
    private String name;
    private Integer count;
}

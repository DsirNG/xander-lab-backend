package com.xander.lab.publishing.api;

import lombok.Data;

/** Public blog tag with the count of published posts. */
@Data
public class TagVO {
    private String name;
    private Integer count;
}

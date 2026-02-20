package com.xander.lab.dto;

import lombok.Data;

@Data
public class ComponentListVO {
    private Long id;
    private String title;
    private String desc;
    private String tag;
    private String iconKey;
    private String categoryId;
    private String author; // Add author here as requested
    private String version; // Add version here as requested
}

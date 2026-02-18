package com.xander.lab.dto;

import lombok.Data;

/**
 * 分类 VO（含文章数量）
 */
@Data
public class CategoryVO {

    /** 分类ID（英文标识） */
    private String id;

    /** 分类名称 */
    private String name;

    /** 该分类下已发布的文章数量 */
    private Integer count;
}

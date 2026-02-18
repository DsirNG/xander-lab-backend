package com.xander.lab.dto;

import lombok.Data;

/**
 * 标签 VO（含文章数量）
 */
@Data
public class TagVO {

    /** 标签名称 */
    private String name;

    /** 使用该标签的已发布文章数量 */
    private Integer count;
}

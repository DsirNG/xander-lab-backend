package com.xander.lab.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 组件分类实体
 * 对应数据库表：component_category
 */
@Data
@TableName("component_category")
public class ComponentCategory {

    /**
     * 分类ID (例如: ui-kit)
     */
    @TableId(type = IdType.INPUT)
    private String id;

    /**
     * 分类名称 (中文)
     */
    private String nameZh;

    /**
     * 分类名称 (英文)
     */
    private String nameEn;

    /**
     * 简短描述 (中文)
     */
    private String descriptionZh;

    /**
     * 简短描述 (英文)
     */
    private String descriptionEn;

    /**
     * 排序权重
     */
    private Integer sort;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}

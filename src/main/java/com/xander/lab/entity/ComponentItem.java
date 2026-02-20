package com.xander.lab.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 组件实体
 * 对应数据库表：component_item
 */
@Data
@TableName("component_item")
public class ComponentItem {

    /**
     * 组件ID (例如: toast)
     */
    @TableId(type = IdType.INPUT)
    private String id;

    /**
     * 关联 component_category.id
     */
    private String categoryId;

    /**
     * 标题 (中文)
     */
    private String titleZh;

    /**
     * 标题 (英文)
     */
    private String titleEn;

    /**
     * 描述 (中文)
     */
    private String descriptionZh;

    /**
     * 描述 (英文)
     */
    private String descriptionEn;

    /**
     * 标签 (例如: 交互)
     */
    private String tagZh;

    /**
     * 标签 (例如: Interaction)
     */
    private String tagEn;

    /**
     * 图标标识符 (例如: Activity)
     */
    private String iconKey;

    /**
     * 排序权重
     */
    private Integer sort;

    /**
     * 状态: 1=启用, 0=禁用
     */
    private Integer status;

    /**
     * 作者
     */
    private String author;

    /**
     * 版本号
     */
    private String version;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}

package com.xander.lab.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 组件详情页配置实体
 * 对应数据库表：component_detail_page
 */
@Data
@TableName("component_detail_page")
public class ComponentDetailPage {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联组件ID
     */
    private String componentId;

    /**
     * 页面类型 (guide, api 等)
     */
    private String pageType;

    /**
     * React组件键名 (例如: CustomSelectGuide)
     */
    private String componentKey;

    /**
     * 排序权重
     */
    private Integer sort;
}

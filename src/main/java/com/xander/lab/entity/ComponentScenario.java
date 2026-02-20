package com.xander.lab.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 组件演示场景实体
 * 对应数据库表：component_scenario
 */
@Data
@TableName("component_scenario")
public class ComponentScenario {

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
     * 场景标题 (中文)
     */
    private String titleZh;

    /**
     * 场景标题 (英文)
     */
    private String titleEn;

    /**
     * 场景描述 (中文)
     */
    private String descriptionZh;

    /**
     * 场景描述 (英文)
     */
    private String descriptionEn;

    /**
     * 用于展示的源代码片段
     */
    private String codeSnippet;

    /**
     * React演示组件键名 (例如: ToastBasicDemo)；与 demoCode 二选一
     */
    private String demoKey;

    /**
     * 可直接运行的 JSX demo 代码；有此字段时无需 demoKey。
     * 前端会通过 LiveDemoSandbox 动态编译并渲染。
     */
    private String demoCode;

    /**
     * 排序权重
     */
    private Integer sort;
}

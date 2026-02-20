package com.xander.lab.dto;

import lombok.Data;

@Data
public class ComponentScenarioVO {
    private String title;
    private String desc;
    /** 用于 "View Code" 按钮展示的格式化代码片段 */
    private String code;
    /** React 注册表键名（静态组件走这个） */
    private String demoKey;
    /** 可直接运行的 JSX demo 代码（动态沙箱走这个） */
    private String demoCode;
}

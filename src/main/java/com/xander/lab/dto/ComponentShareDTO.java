package com.xander.lab.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 组件分享请求对象（支持多场景）
 */
@Data
public class ComponentShareDTO {

    @NotBlank(message = "中文标题不能为空")
    private String titleZh;

    @NotBlank(message = "英文标题不能为空")
    private String titleEn;

    private String version;

    private String descriptionZh;

    private String categoryId = "ui-kit";

    /**
     * 可选：整体组件源码说明（纯文本，供阅读参考，不运行）
     */
    private String sourceCode;

    /**
     * 演示场景列表（至少一个）
     */
    @NotEmpty(message = "至少需要一个演示场景")
    private List<ScenarioDTO> scenarios;

    /**
     * 单个演示场景 DTO
     */
    @Data
    public static class ScenarioDTO {
        /** 场景标题（中文） */
        private String titleZh;
        /** 场景标题（英文，可选） */
        private String titleEn;
        /** 场景描述 */
        private String description;
        /** 可运行的 JSX demo 代码（沙箱执行） */
        private String demoCode;
        /** View Code 中展示的格式化/精简代码（可选） */
        private String codeSnippet;
    }
}

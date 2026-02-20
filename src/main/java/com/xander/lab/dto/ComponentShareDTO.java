package com.xander.lab.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 组件分享请求对象
 */
@Data
public class ComponentShareDTO {
    
    @NotBlank(message = "中文标题不能为空")
    private String titleZh;
    
    @NotBlank(message = "英文标题不能为空")
    private String titleEn;
    
    private String version;
    
    private String descriptionZh;
    
    @NotBlank(message = "演示代码不能为空")
    private String demoCode;
    
    private String categoryId = "ui-kit";
}

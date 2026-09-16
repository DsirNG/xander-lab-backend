package com.xander.lab.knowledgebase.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 新建标签。 */
public record CreateTagRequest(
        @NotBlank(message = "标签名不能为空")
        @Size(max = 60, message = "标签名最长 60 个字符")
        String name,
        @Pattern(regexp = "^$|^#[0-9a-fA-F]{6}$", message = "色值格式应为 #RRGGBB")
        String color,
        @Size(max = 200, message = "标签说明最长 200 个字符")
        String description) {
}

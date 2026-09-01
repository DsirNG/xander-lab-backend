package com.xander.lab.knowledgebase.api;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 修改标签。三个字段都可选，只改传了的：null 表示不动这个字段。
 * 因此清空颜色要显式传空串，而不是不传。
 */
public record UpdateTagRequest(
        @Size(max = 60, message = "标签名最长 60 个字符")
        String name,
        @Pattern(regexp = "^$|^#[0-9a-fA-F]{6}$", message = "色值格式应为 #RRGGBB")
        String color,
        @Size(max = 200, message = "标签说明最长 200 个字符")
        String description) {
}

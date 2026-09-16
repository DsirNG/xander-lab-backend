package com.xander.lab.knowledgebase.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 重命名文件。只改展示名，原始文件名与对象存储里的键不变。 */
public record RenameFileRequest(
        @NotBlank(message = "文件名不能为空")
        @Size(max = 255, message = "文件名最长 255 个字符")
        String displayName) {
}

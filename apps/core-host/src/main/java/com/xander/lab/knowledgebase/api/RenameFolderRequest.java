package com.xander.lab.knowledgebase.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 重命名文件夹。 */
public record RenameFolderRequest(
        @NotBlank(message = "文件夹名不能为空")
        @Size(max = 120, message = "文件夹名最长 120 个字符")
        String name) {
}

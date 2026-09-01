package com.xander.lab.knowledgebase.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建文件夹。
 *
 * @param parentId 父文件夹 id；null 或 0 表示建在根目录下
 */
public record CreateFolderRequest(
        Long parentId,
        @NotBlank(message = "文件夹名不能为空")
        @Size(max = 120, message = "文件夹名最长 120 个字符")
        String name) {
}

package com.xander.lab.knowledgebase.api;

import jakarta.validation.constraints.NotNull;

/**
 * 移动文件到另一个文件夹。
 *
 * @param folderId 目标文件夹 id，必须是一个真实存在的非根文件夹
 */
public record MoveFileRequest(
        @NotNull(message = "目标文件夹不能为空")
        Long folderId) {
}

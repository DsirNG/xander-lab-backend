package com.xander.lab.knowledgebase.api;

/**
 * 移动文件夹。
 *
 * @param parentId 新的父文件夹 id；null 或 0 表示移到根目录下
 */
public record MoveFolderRequest(Long parentId) {
}

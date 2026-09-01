package com.xander.lab.knowledgebase.api;

import java.util.List;

/**
 * 文件的知识结构预览：一次请求把「文件信息 + 章节目录 + 结构图 + 正文片段」都给前端，
 * 省掉打开预览页时的三四个串行请求。
 *
 * @param structure   整本书维度的结构图；大部头文件在这里是 {@code generated=false} 的空图，
 *                    前端应展示 {@code file.structureMessage} 并引导用户点开某一章
 * @param excerpt     正文开头片段，用于首屏直接看到内容
 * @param excerptTruncated 正文是否被截断，true 表示还有更多内容
 */
public record KnowledgeFilePreviewView(
        KnowledgeFileView file,
        List<KnowledgeChapterView> chapters,
        KnowledgeStructureView structure,
        String excerpt,
        boolean excerptTruncated) {
}

package com.xander.lab.knowledgebase.domain;

/**
 * 章节识别的一条结果，坐标系是抽取出的纯文本。
 *
 * @param level       压缩后的标题层级，1 为顶层章
 * @param title       标题文本（已去掉井号、编号前缀与多余空白）
 * @param startOffset 章节起始下标（含），指向标题行行首
 * @param endOffset   章节结束下标（不含），指向下一个「层级不深于自己」的标题行行首。
 *                    因此父章的区间包含它所有子节的区间，取整章原文时不必自己拼接。
 */
public record DetectedChapter(int level, String title, int startOffset, int endOffset) {

    public int charCount() {
        return Math.max(0, endOffset - startOffset);
    }
}

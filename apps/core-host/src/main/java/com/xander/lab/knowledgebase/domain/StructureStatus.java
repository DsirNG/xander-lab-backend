package com.xander.lab.knowledgebase.domain;

/**
 * 知识结构图的生成状态。文件维度与章节维度共用这套取值，章节只会用到 PENDING/READY/FAILED。
 */
public enum StructureStatus {
    /** 待生成。文件刚入库、或大部头的某一章还没被展开过。 */
    PENDING,
    /** 已生成，可直接预览。 */
    READY,
    /**
     * 大部头按章按需生成：整本书章节太多或正文太长，上传时不自动出图，
     * 只发通知告诉用户“看到哪一章再生成哪一章”。
     */
    CHAPTER_ON_DEMAND,
    /** 该格式抽不出正文（例如 PDF、DOCX），因此没有章节也没有结构图。 */
    UNSUPPORTED,
    /** 生成过但失败了，用户可以重试。 */
    FAILED
}

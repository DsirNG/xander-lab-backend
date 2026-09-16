package com.xander.lab.knowledgebase.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 自动识别出的一个章节；同一文件内的章节按 {@code orderIndex} 排出阅读目录。 */
@Data
@TableName("knowledge_chapter")
public class KnowledgeChapter {

    /** 顶层章的父章 id 约定值。 */
    public static final long ROOT_PARENT_ID = 0L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long fileId;
    /** 父章节 id，0 表示顶层章。 */
    private Long parentId;
    /** 标题层级，1 为顶层章。 */
    private Integer level;
    /** 同一文件内的全局排序序号，从 0 开始，按正文出现顺序递增。 */
    private Integer orderIndex;
    private String title;
    /** 章节正文在 {@link KnowledgeFileContent#getContent()} 中的起始下标（含）。 */
    private Integer startOffset;
    /** 章节正文的结束下标（不含）。 */
    private Integer endOffset;
    private Integer charCount;
    /** 见 {@link StructureStatus}，章节只用 PENDING/READY/FAILED。 */
    private String structureStatus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

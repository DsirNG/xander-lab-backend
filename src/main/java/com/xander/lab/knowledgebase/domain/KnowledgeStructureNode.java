package com.xander.lab.knowledgebase.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识结构图的一个节点。图是树，边由 {@code parentId} 推导，不额外建边表。
 *
 * <p>一次生成的产物由 {@code (fileId, scopeChapterId)} 唯一界定，重新生成时按这个二元组整批替换，
 * 因此「重算整本书」不会顺手删掉用户按章生成的结果。</p>
 */
@Data
@TableName("knowledge_structure_node")
public class KnowledgeStructureNode {

    /** 整本书维度的 scope 约定值；非 0 表示只针对某一章生成。 */
    public static final long FILE_SCOPE = 0L;
    /** 本次生成的根节点的父 id 约定值。 */
    public static final long ROOT_PARENT_ID = 0L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long fileId;
    /** 0=整本书维度，非 0=只针对该 knowledge_chapter.id 生成。 */
    private Long scopeChapterId;
    /** 父节点 id，0 表示本次生成的根节点。 */
    private Long parentId;
    /** 见 {@link StructureNodeType}。 */
    private String nodeType;
    private String title;
    /** 节点摘要，取该节点正文开头一段，供预览展示。 */
    private String summary;
    private Integer orderIndex;
    /** 对应的章节 id；纯知识点节点为空，前端据此跳回原文位置。 */
    private Long chapterId;
    private LocalDateTime createdAt;
}

package com.xander.lab.knowledgebase.api;

import com.xander.lab.knowledgebase.domain.KnowledgeFile;
import com.xander.lab.knowledgebase.domain.KnowledgeStructureNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次生成的知识结构图。
 *
 * @param scopeChapterId 0 表示整本书维度，非 0 表示只针对该章生成
 * @param generated      是否真的有图；大部头文件在用户点开某一章之前，整本书维度是空的
 */
public record KnowledgeStructureView(
        Long fileId,
        String fileName,
        Long scopeChapterId,
        /** 文件维度的结构图状态，见 StructureStatus。 */
        String structureStatus,
        /** 面向用户的状态说明，可直接展示，例如「篇幅较大未自动生成整本结构图」。 */
        String structureMessage,
        boolean generated,
        int nodeCount,
        KnowledgeStructureNodeView root) {

    /** 结构图的一个节点。 */
    public record KnowledgeStructureNodeView(
            Long id,
            /** ROOT/CHAPTER/SECTION/POINT，见 StructureNodeType。 */
            String nodeType,
            String title,
            String summary,
            /** 对应章节 id；纯知识点为 null，前端据此决定能否跳回原文。 */
            Long chapterId,
            Integer orderIndex,
            List<KnowledgeStructureNodeView> children) {
    }

    /**
     * 由扁平节点行装出树。
     *
     * <p>行序已由 SQL 按 {@code (parentId, orderIndex, id)} 排好，因此同一父节点下的孩子天然有序，
     * 这里只做一次分组，不再排序。找不到根节点（还没生成）就返回 {@code generated=false} 的空图，
     * 让前端用 {@code structureMessage} 提示用户，而不是收到一个 404。</p>
     */
    public static KnowledgeStructureView of(KnowledgeFile file, long scope, List<KnowledgeStructureNode> nodes) {
        Map<Long, List<KnowledgeStructureNode>> childrenOf = new HashMap<>();
        KnowledgeStructureNode rootRow = null;
        for (KnowledgeStructureNode node : nodes) {
            long parentId = node.getParentId() == null
                    ? KnowledgeStructureNode.ROOT_PARENT_ID : node.getParentId();
            if (parentId == KnowledgeStructureNode.ROOT_PARENT_ID && rootRow == null) {
                rootRow = node;
                continue;
            }
            childrenOf.computeIfAbsent(parentId, key -> new ArrayList<>()).add(node);
        }
        KnowledgeStructureNodeView root = rootRow == null ? null : toView(rootRow, childrenOf);
        return new KnowledgeStructureView(file.getId(), file.getDisplayName(), scope,
                file.getStructureStatus(), file.getStructureMessage(),
                root != null, nodes.size(), root);
    }

    private static KnowledgeStructureNodeView toView(KnowledgeStructureNode node,
                                                     Map<Long, List<KnowledgeStructureNode>> childrenOf) {
        List<KnowledgeStructureNodeView> children = childrenOf
                .getOrDefault(node.getId(), List.of()).stream()
                .map(child -> toView(child, childrenOf))
                .toList();
        return new KnowledgeStructureNodeView(node.getId(), node.getNodeType(), node.getTitle(),
                node.getSummary(), node.getChapterId(), node.getOrderIndex(), children);
    }
}

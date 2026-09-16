package com.xander.lab.knowledgebase.api;

import com.xander.lab.knowledgebase.domain.KnowledgeFolder;

import java.time.LocalDateTime;

/**
 * 一个文件夹节点。接口返回的是扁平列表（按 {@code path} 升序即先序遍历），
 * 由前端按 {@code parentId} 自行装成树——树的形状前端要用，嵌套 JSON 反而不好做局部刷新。
 *
 * @param fileCount 直属文件数，不含子文件夹里的文件
 */
public record KnowledgeFolderView(
        Long id,
        Long parentId,
        String name,
        /** 物化路径，形如 {@code /3/17/}；前端用它排序与做面包屑。 */
        String path,
        Integer depth,
        int fileCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static KnowledgeFolderView of(KnowledgeFolder folder, int fileCount) {
        return new KnowledgeFolderView(folder.getId(), folder.getParentId(), folder.getName(),
                folder.getPath(), folder.getDepth(), fileCount,
                folder.getCreatedAt(), folder.getUpdatedAt());
    }
}

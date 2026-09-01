package com.xander.lab.knowledgebase.api;

import com.xander.lab.knowledgebase.domain.KnowledgeFile;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 列表与详情共用的文件视图。正文不在这里：列表很频繁，大文本要单独取（见 {@link KnowledgeFilePreviewView}）。
 *
 * @param tags 该文件上的标签，元素的 {@code source} 区分手动/自动
 */
public record KnowledgeFileView(
        Long id,
        Long folderId,
        String originalName,
        String displayName,
        String extension,
        String contentType,
        Long sizeBytes,
        /** 整文件 SHA-256，前端可用于秒传判断。 */
        String fileHash,
        String storageUrl,
        Boolean textExtracted,
        Integer charCount,
        /** 顶层章节数，0 表示未分章。 */
        Integer chapterCount,
        /** PENDING/READY/CHAPTER_ON_DEMAND/UNSUPPORTED/FAILED。 */
        String structureStatus,
        /** 面向用户的状态说明，可直接展示。 */
        String structureMessage,
        Long uploadSessionId,
        List<KnowledgeTagView> tags,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static KnowledgeFileView of(KnowledgeFile file, List<KnowledgeTagView> tags) {
        return new KnowledgeFileView(file.getId(), file.getFolderId(), file.getOriginalName(),
                file.getDisplayName(), file.getExtension(), file.getContentType(), file.getSizeBytes(),
                file.getFileHash(), file.getStorageUrl(), file.getTextExtracted(), file.getCharCount(),
                file.getChapterCount(), file.getStructureStatus(), file.getStructureMessage(),
                file.getUploadSessionId(), tags == null ? List.of() : tags,
                file.getCreatedAt(), file.getUpdatedAt());
    }
}

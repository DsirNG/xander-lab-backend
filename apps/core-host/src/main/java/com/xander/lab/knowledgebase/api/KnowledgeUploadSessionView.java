package com.xander.lab.knowledgebase.api;

import com.xander.lab.knowledgebase.domain.KnowledgeUploadSession;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分片上传会话的状态。断点续传靠 {@code missingChunks}：客户端拿到它就知道该补传哪几片，
 * 不必从头再传，也不必自己记账。
 *
 * @param chunkSize      服务端最终采用的分片大小，可能与客户端建议值不同，必须按这个切
 * @param totalChunks    服务端按 {@code sizeBytes / chunkSize} 算出的总片数，不采信客户端传值
 * @param missingChunks  还缺哪些分片序号（从 0 开始）；为空即可调用完成接口
 * @param fileId         完成后生成的文件 id，未完成时为 null
 * @param resumed        true 表示这次 init 命中了已有的活跃会话，直接续传
 */
public record KnowledgeUploadSessionView(
        String uploadId,
        Long folderId,
        String originalName,
        Long sizeBytes,
        Integer chunkSize,
        Integer totalChunks,
        Integer receivedChunks,
        List<Integer> missingChunks,
        String status,
        Long fileId,
        boolean resumed,
        LocalDateTime expiresAt,
        LocalDateTime createdAt) {

    public static KnowledgeUploadSessionView of(KnowledgeUploadSession session,
                                                List<Integer> missingChunks,
                                                boolean resumed) {
        return new KnowledgeUploadSessionView(session.getUploadId(), session.getFolderId(),
                session.getOriginalName(), session.getSizeBytes(), session.getChunkSize(),
                session.getTotalChunks(), session.getReceivedChunks(),
                missingChunks == null ? List.of() : missingChunks,
                session.getStatus(), session.getFileId(), resumed,
                session.getExpiresAt(), session.getCreatedAt());
    }
}

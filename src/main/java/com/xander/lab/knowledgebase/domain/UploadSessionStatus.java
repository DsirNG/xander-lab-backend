package com.xander.lab.knowledgebase.domain;

/**
 * 分片上传会话状态。
 *
 * <p>允许的流转：{@code UPLOADING → COMPLETED}（合并入库成功）、
 * {@code UPLOADING → ABORTED}（用户放弃）、{@code UPLOADING → EXPIRED}（超时被回收）。
 * 终态之间不再互转；终态会话的 {@code idempotency_key} 置空，让同一个文件日后能重新上传。</p>
 */
public enum UploadSessionStatus {
    UPLOADING,
    COMPLETED,
    ABORTED,
    EXPIRED;

    public boolean isTerminal() {
        return this != UPLOADING;
    }
}

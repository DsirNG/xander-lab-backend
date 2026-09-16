package com.xander.lab.knowledgebase.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 开启一次分片上传。
 *
 * <p>{@code fileHash} 是必填的：它既是断点续传的识别依据（同一用户、同一文件夹、同一内容、同一大小
 * 只会有一个活跃会话），也用于合并后校验完整性。客户端在切片前先算一遍整文件 SHA-256。</p>
 *
 * @param chunkSize 建议分片大小；服务端会夹到允许区间内，最终值以响应里的 {@code chunkSize} 为准
 */
public record InitUploadRequest(
        @NotNull(message = "目标文件夹不能为空")
        Long folderId,
        @NotBlank(message = "文件名不能为空")
        @Size(max = 255, message = "文件名最长 255 个字符")
        String originalName,
        @Size(max = 128, message = "内容类型最长 128 个字符")
        String contentType,
        @NotNull(message = "文件大小不能为空")
        @Positive(message = "文件大小必须大于 0")
        Long sizeBytes,
        Integer chunkSize,
        @NotBlank(message = "文件校验值不能为空")
        @Size(min = 64, max = 64, message = "文件校验值应为 64 位 SHA-256 十六进制串")
        String fileHash) {
}

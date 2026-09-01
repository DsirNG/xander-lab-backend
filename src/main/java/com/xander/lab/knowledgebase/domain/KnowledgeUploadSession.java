package com.xander.lab.knowledgebase.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次分片上传的会话。断点续传、完成幂等和过期回收都以本表为准。
 *
 * <p>{@code totalChunks} 由服务端按 {@code sizeBytes / chunkSize} 算出，不采信客户端传值；
 * 已落盘分片的权威来源是 {@code knowledge_upload_chunk}，{@code receivedChunks} 只是展示用的快照。</p>
 */
@Data
@TableName("knowledge_upload_session")
public class KnowledgeUploadSession {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** 对外暴露的会话标识（UUID）；内部主键不出现在 API 里。 */
    private String uploadId;
    private Long folderId;
    private String originalName;
    private String contentType;
    private Long sizeBytes;
    private Integer chunkSize;
    private Integer totalChunks;
    /** 客户端声明的整文件 SHA-256：既是续传识别依据，也用于合并后校验完整性。 */
    private String fileHash;
    private Integer receivedChunks;
    /** 见 {@link UploadSessionStatus}。 */
    private String status;
    /**
     * 活跃会话去重键 {@code SHA-256(userId|folderId|fileHash|sizeBytes)}。
     * 会话进入终态后置 NULL，让同一个文件日后可以重新上传（唯一索引不约束 NULL）。
     */
    private String idempotencyKey;
    /** 完成后生成的 knowledge_file.id；重复调用完成接口时直接返回它。 */
    private Long fileId;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

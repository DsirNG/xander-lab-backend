package com.xander.lab.knowledgebase.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 一个已确认落盘的上传分片。{@code (sessionId, chunkIndex)} 唯一，重复上传同一分片幂等。 */
@Data
@TableName("knowledge_upload_chunk")
public class KnowledgeUploadChunk {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    /** 分片序号，从 0 开始。 */
    private Integer chunkIndex;
    private Integer sizeBytes;
    private LocalDateTime createdAt;
}

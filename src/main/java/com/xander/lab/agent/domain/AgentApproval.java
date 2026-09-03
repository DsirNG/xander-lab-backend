package com.xander.lab.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable user decision for one high-impact agent tool invocation. */
@Data
@TableName("agent_approval")
public class AgentApproval {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long conversationId;
    private Long runId;
    private Long invocationDbId;
    private String invocationId;
    private String toolName;
    /** SHA-256 of tool name and canonical arguments; changed actions require another approval. */
    private String requestFingerprint;
    /** Safe tool/action summary; it never contains credentials or a raw model prompt. */
    private String summaryJson;
    private String status;
    private String decisionReason;
    private LocalDateTime decidedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

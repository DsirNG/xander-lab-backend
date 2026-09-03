package com.xander.lab.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable execution record for one claimed conversation generation. */
@Data
@TableName("agent_run")
public class AgentRun {
    public static final String STATUS_RUNNING = "RUNNING";
    /** A durable tool call is waiting for its owner's decision. */
    public static final String STATUS_WAITING_APPROVAL = "WAITING_APPROVAL";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;
    /** Owning user; duplicated from the conversation for journal authorization queries. */
    private Long userId;
    private Long conversationId;
    /** Immutable conversation execution generation, unique within one conversation. */
    private Long runVersion;
    /** RUNNING/WAITING_APPROVAL/COMPLETED/FAILED. */
    private String status;
    /** Reserved for the later skill resolver; no user prompt is stored here in phase 1. */
    private String intentJson;
    /** Reserved snapshot of selected skill versions. */
    private String skillsJson;
    /** Reserved snapshot of model route and step/token budget. */
    private String executionSnapshotJson;
    private String failureReason;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

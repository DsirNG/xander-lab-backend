package com.xander.lab.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable-boundary journal entry for one model decision in an agent run. */
@Data
@TableName("agent_step")
public class AgentStep {
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    /** One-based ordering of model decisions within a run. */
    private Integer stepNo;
    /** RUNNING/COMPLETED/FAILED/CANCELLED. */
    private String status;
    /** Minimal replay/audit input metadata; raw prompt content is intentionally excluded. */
    private String requestSnapshotJson;
    /** Parsed model decision required to explain the next tool or final reply. */
    private String decisionJson;
    private String modelName;
    private Long inputTokens;
    private Long cachedTokens;
    private Long outputTokens;
    private String failureReason;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

package com.xander.lab.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable identity and outcome of one model-requested tool execution. */
@Data
@TableName("agent_tool_invocation")
public class AgentToolInvocation {
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    /** The worker ended before the remote side effect could be reconciled. */
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long stepId;
    /** Stable UUID passed to tool adapters; future mutations use it as their business idempotency key. */
    private String invocationId;
    private String toolName;
    /** Model-supplied arguments, retained only inside the private agent journal. */
    private String argumentsJson;
    /** RUNNING/SUCCEEDED/FAILED/CANCELLED. */
    private String status;
    private String resultJson;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

package com.xander.lab.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Replayable SSE event of an agent conversation; id doubles as the resume cursor. */
@Data
@TableName("agent_event")
public class AgentEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    /** 事件所属执行代次；SSE 恢复只回放订阅时固定代次的事件。 */
    private Long runVersion;
    /** 事件类型包含工具、审批、回答和终态事件；审批事件为 approval_required/approved/rejected/executed。 */
    private String eventType;
    private String dataContent;
    private LocalDateTime createdAt;
}

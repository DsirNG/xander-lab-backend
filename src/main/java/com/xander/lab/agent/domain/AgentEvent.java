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
    /** 事件类型：thought/tool_start/tool_progress/tool_delta/tool_end/tool_error/answer_delta/answer/complete/error。 */
    private String eventType;
    private String dataContent;
    private LocalDateTime createdAt;
}

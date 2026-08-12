package com.xander.lab.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One persisted turn inside an agent conversation. {@code kind} distinguishes
 * user input (message), assistant thinking (thought), tool invocation
 * (tool_call), tool outcome (tool_result) and the final assistant answer
 * (answer). Content of tool records is JSON.
 */
@Data
@TableName("agent_message")
public class AgentMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    /** 消息角色：user/assistant/tool */
    private String role;
    /** 消息类型：message/thought/tool_call/tool_result/answer */
    private String kind;
    /** 工具名，仅 kind=tool_call/tool_result 时非空 */
    private String toolName;
    private String content;
    private LocalDateTime createdAt;
}
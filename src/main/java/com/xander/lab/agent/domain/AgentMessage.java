package com.xander.lab.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One persisted turn inside an agent conversation. {@code kind} distinguishes
 * user input (message), assistant thinking (thought), self-critique
 * (reflection), tool invocation (tool_call), tool outcome (tool_result), the
 * final assistant answer (answer), the agent task plan (plan), an answer card the assistant handed the user
 * (quiz) and the user's submission on that card (quiz_answer). Content of tool
 * records and of quiz cards is JSON.
 */
@Data
@TableName("agent_message")
public class AgentMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    /** Execution generation that created this message; used to update one plan card per user turn. */
    private Long runVersion;
    /** 消息角色：user/assistant/tool */
    private String role;
    /** 消息类型：message/thought/reflection/tool_call/tool_result/answer/quiz/quiz_answer */
    private String kind;
    /** 工具名，仅 kind=tool_call/tool_result 时非空 */
    private String toolName;
    private String content;
    /** 持久化的附件快照 JSON；API 仅返回解析后的 attachments。 */
    @JsonIgnore
    private String attachmentsJson;
    @TableField(exist = false)
    private List<AgentAttachment> attachments;
    private LocalDateTime createdAt;
}

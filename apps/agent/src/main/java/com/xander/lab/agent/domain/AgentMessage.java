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
 * (quiz), the user's submission on that card (quiz_answer) and a code deliverable
 * handed to the user (artifact). Content of tool records, quiz cards and
 * artifacts is JSON.
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
    /** 消息类型：message/thought/reflection/tool_call/tool_result/answer/quiz/quiz_answer/artifact */
    private String kind;
    /** 工具名，仅 kind=tool_call/tool_result 时非空 */
    private String toolName;
    /**
     * 这次工具调用的持久化身份，仅 kind=tool_call/tool_result 时非空。
     *
     * <p>它是"入参与结果属于同一次调用"的凭据。只有工具名的时候，一步里出现两个同名工具
     * （并行批次里查两个主题的知识点）就无法区分，前端只能把第二个的结果挂到第一张卡上。
     * 历史消息没有这个字段，消费方必须容忍空值并回退到按工具名配对。</p>
     *
     * <p>刻意放在列上而不是 {@code content} 里：{@code content} 会被渲染进提示词
     * （见 AgentRequestAssembler 的「【工具结果】」），身份是给人看的，不该占模型的 token。</p>
     */
    private String invocationId;
    private String content;
    /** 持久化的附件快照 JSON；API 仅返回解析后的 attachments。 */
    @JsonIgnore
    private String attachmentsJson;
    @TableField(exist = false)
    private List<AgentAttachment> attachments;
    private LocalDateTime createdAt;
}

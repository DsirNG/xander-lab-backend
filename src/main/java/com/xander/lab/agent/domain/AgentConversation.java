package com.xander.lab.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** A durable chat conversation between the user and the orchestration agent. */
@Data
@TableName("agent_conversation")
public class AgentConversation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    /** 会话状态：ready/failed 表示空闲可继续对话，running 表示一轮执行进行中。 */
    private String status;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
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
    /** 当前执行代次；每次成功 claim 后递增，用于隔离旧 worker 的迟到写入。 */
    private Long runVersion;
    /** 用户取消请求标记：0=未请求，1=执行中轮询到后立即停止；新一轮开始时复位。 */
    private Integer cancelRequested;
    /** 分享令牌，启用分享时生成 UUID，禁用时置 NULL。 */
    private String shareToken;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

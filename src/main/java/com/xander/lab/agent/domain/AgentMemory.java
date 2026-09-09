package com.xander.lab.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条跨会话的长期记忆。
 *
 * <p>与会话消息不同，记忆不随会话结束失效：每一轮对话都会把最相关的若干条注入上下文，
 * 让智能体在新会话里也保留对用户的连续认知。一条记忆只描述一件长期有效的事。</p>
 */
@Data
@TableName("agent_memory")
public class AgentMemory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** 记忆稳定标识，同一用户内唯一；智能体复用同一 key 即为更新而非新增。 */
    private String memoryKey;
    /** 记忆类型：PROFILE 用户画像 / PREFERENCE 偏好 / FACT 长期事实 / PROJECT 在办事项。 */
    private String kind;
    private String content;
    /** Optional user-owned media asset referenced by this memory. */
    private Long mediaAssetId;
    /** 首次写入该记忆的会话ID，便于追溯来源。 */
    private Long sourceConversationId;
    /** 被召回次数，用于优先注入高频记忆。 */
    private Integer hitCount;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

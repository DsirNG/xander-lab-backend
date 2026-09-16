package com.xander.lab.recitation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 一场由智能体发起、等待用户作答并最终评分的持久化知识测验。 */
@Data
@TableName("knowledge_quiz_session")
public class KnowledgeQuizSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long materialId;
    private Long conversationId;
    /** OPEN 等待评分，GRADED 已经生成唯一评分记录。 */
    private String status;
    private Long attemptId;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime gradedAt;
}

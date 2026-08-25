package com.xander.lab.recitation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一次个人知识测验的评分留档。
 *
 * <p>录音背诵有 {@link RecitationAttempt} 记录音频与识别结果，概念问答和练习题没有音频，
 * 需要留档的是"出了什么题、用户答了什么、每题给了多少分"——评分依据留在库里，
 * 掌握度才不是一个没有出处的数字。</p>
 */
@Data
@TableName("knowledge_quiz_attempt")
public class KnowledgeQuizAttempt {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long materialId;
    /** 出题与评分所在的智能体会话；从知识页直接看结果时可为空。 */
    private Long conversationId;
    private String testMode;
    private Integer questionCount;
    private Integer correctCount;
    private BigDecimal score;
    private String verdict;
    private String itemsJson;
    private LocalDateTime createdAt;
}

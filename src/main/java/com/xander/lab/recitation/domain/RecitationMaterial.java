package com.xander.lab.recitation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.math.BigDecimal;

/** 用户确认后的中文背诵原文。 */
@Data
@TableName("recitation_material")
public class RecitationMaterial {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    private String content;
    private String normalizedContent;
    private String knowledgeType;
    private String testMode;
    private BigDecimal masteryScore;
    private String masteryLevel;
    private Integer reviewCount;
    private LocalDateTime lastReviewAt;
    private LocalDateTime nextReviewAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

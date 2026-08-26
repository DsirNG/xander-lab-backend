package com.xander.lab.recitation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 一次录音背诵的持久化转写和检查任务。 */
@Data
@TableName("recitation_attempt")
public class RecitationAttempt {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long materialId;
    /** 客户端生成的稳定请求标识；同一用户重复提交时只创建一个任务。 */
    private String clientRequestId;
    private String audioUrl;
    private String status;
    private String providerTaskId;
    private Long providerId;
    private String model;
    private String transcript;
    private String resultJson;
    private BigDecimal score;
    private String errorMessage;
    private String runToken;
    private Integer pollAttempts;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

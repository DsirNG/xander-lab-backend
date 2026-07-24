package com.xander.lab.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("email_reminder_task")
public class EmailReminderTask {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String clientRequestId;
    private String recipientEmail;
    private String subject;
    private String message;
    private Instant scheduledAt;
    private String status;
    private Integer sendAttempts;
    private String errorMessage;
    private String processingToken;
    private Instant claimedAt;
    private Instant sentAt;
    private Instant deletedAt;
    private Instant createdAt;
    private Instant updatedAt;
}

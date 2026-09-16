package com.xander.lab.automation.domain;

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
    /** PLAIN | HTML */
    private String contentType;
    /** classic | minimal | card | notice */
    private String templateId;
    /** ONCE | DAILY | WEEKLY | MONTHLY | CUSTOM */
    private String frequency;
    /** IANA timezone, e.g. Asia/Shanghai */
    private String timezone;
    /** HH:mm local time for recurring frequencies */
    private String sendTime;
    /** WEEKLY: ISO weekday 1-7; MONTHLY: day of month 1-31 */
    private Integer recurrenceDay;
    /** CUSTOM: every N days */
    private Integer intervalDays;
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

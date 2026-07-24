package com.xander.lab.dto.emailreminder;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class EmailReminderTaskVO {
    private Long id;
    private String clientRequestId;
    private String senderEmail;
    private String recipientEmail;
    private String subject;
    private String message;
    private Instant scheduledAt;
    private String status;
    private Integer sendAttempts;
    private String errorMessage;
    private Instant sentAt;
    private Instant createdAt;
    private Instant updatedAt;
}

package com.xander.lab.service;

import com.xander.lab.dto.emailreminder.EmailReminderCreateRequest;
import com.xander.lab.entity.EmailReminderTask;
import com.xander.lab.mapper.EmailReminderTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailReminderServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-24T09:00:00Z");

    @Mock
    private EmailReminderTaskMapper taskMapper;

    private EmailReminderService service;

    @BeforeEach
    void setUp() {
        service = new EmailReminderService(
                taskMapper,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        ReflectionTestUtils.setField(service, "senderEmail", "noreply@example.com");
        ReflectionTestUtils.setField(service, "minimumLeadSeconds", 30L);
        ReflectionTestUtils.setField(service, "maxActivePerUser", 50L);
        ReflectionTestUtils.setField(service, "maxCreatedPerDay", 20L);

        when(taskMapper.lockUserForReminderCreation(7L)).thenReturn(7L);
        when(taskMapper.selectCount(any())).thenReturn(0L);
        when(taskMapper.insert(any(EmailReminderTask.class))).thenReturn(1);
    }

    @Test
    void createConvertsOffsetTimeToUtcInstant() {
        EmailReminderCreateRequest request = new EmailReminderCreateRequest();
        request.setClientRequestId("request-1");
        request.setRecipientEmail("USER@example.com");
        request.setSubject("Reminder");
        request.setMessage("Message");
        request.setScheduledAt(OffsetDateTime.parse("2026-07-24T18:30:00+08:00"));

        service.create(7L, request);

        ArgumentCaptor<EmailReminderTask> taskCaptor =
                ArgumentCaptor.forClass(EmailReminderTask.class);
        verify(taskMapper).insert(taskCaptor.capture());
        EmailReminderTask task = taskCaptor.getValue();

        assertThat(task.getScheduledAt())
                .isEqualTo(Instant.parse("2026-07-24T10:30:00Z"));
        assertThat(task.getCreatedAt()).isEqualTo(NOW);
        assertThat(task.getUpdatedAt()).isEqualTo(NOW);
        assertThat(task.getRecipientEmail()).isEqualTo("user@example.com");
    }
}

package com.xander.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xander.lab.dto.emailreminder.EmailReminderCreateRequest;
import com.xander.lab.dto.emailreminder.EmailReminderListResult;
import com.xander.lab.entity.EmailReminderTask;
import com.xander.lab.mapper.EmailReminderTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        service = new EmailReminderService(taskMapper);
        ReflectionTestUtils.setField(service, "senderEmail", "noreply@example.com");
        ReflectionTestUtils.setField(service, "minimumLeadSeconds", 30L);
        ReflectionTestUtils.setField(service, "maxActivePerUser", 50L);
        ReflectionTestUtils.setField(service, "maxCreatedPerDay", 20L);
    }

    @Test
    void createLetsMysqlPopulateSystemTimestamps() {
        when(taskMapper.lockUserForReminderCreation(7L)).thenReturn(7L);
        when(taskMapper.isScheduledAtLeastLeadTime(any(Instant.class), eq(30L)))
                .thenReturn(true);
        when(taskMapper.countActiveTasks(7L)).thenReturn(0L);
        when(taskMapper.countCreatedInPastDay(7L)).thenReturn(0L);
        when(taskMapper.insert(any(EmailReminderTask.class))).thenAnswer(invocation -> {
            EmailReminderTask task = invocation.getArgument(0);
            task.setId(81L);
            return 1;
        });

        EmailReminderCreateRequest request = new EmailReminderCreateRequest();
        request.setClientRequestId("request-1");
        request.setRecipientEmail("USER@example.com");
        request.setSubject("Reminder");
        request.setMessage("Message");
        request.setScheduledAt(OffsetDateTime.now().plusHours(2));
        request.setFrequency("ONCE");
        request.setTimezone("Asia/Shanghai");

        EmailReminderTask databaseTask = new EmailReminderTask();
        databaseTask.setId(81L);
        databaseTask.setClientRequestId("request-1");
        databaseTask.setRecipientEmail("user@example.com");
        databaseTask.setSubject("Reminder");
        databaseTask.setMessage("Message");
        databaseTask.setFrequency("ONCE");
        databaseTask.setTimezone("Asia/Shanghai");
        databaseTask.setScheduledAt(request.getScheduledAt().toInstant());
        databaseTask.setStatus(EmailReminderService.STATUS_PENDING);
        databaseTask.setSendAttempts(0);
        databaseTask.setCreatedAt(NOW);
        databaseTask.setUpdatedAt(NOW);
        when(taskMapper.selectById(81L)).thenReturn(databaseTask);

        var result = service.create(7L, request);

        ArgumentCaptor<EmailReminderTask> taskCaptor =
                ArgumentCaptor.forClass(EmailReminderTask.class);
        verify(taskMapper).insert(taskCaptor.capture());
        EmailReminderTask task = taskCaptor.getValue();

        assertThat(task.getScheduledAt()).isEqualTo(request.getScheduledAt().toInstant());
        assertThat(task.getFrequency()).isEqualTo("ONCE");
        assertThat(task.getTimezone()).isEqualTo("Asia/Shanghai");
        assertThat(task.getCreatedAt()).isNull();
        assertThat(task.getUpdatedAt()).isNull();
        assertThat(task.getRecipientEmail()).isEqualTo("user@example.com");
        assertThat(result.getCreatedAt()).isEqualTo(NOW);
        assertThat(result.getUpdatedAt()).isEqualTo(NOW);
        verify(taskMapper).selectById(81L);
    }

    @Test
    void listReturnsPagedResultWithStats() {
        EmailReminderTask task = new EmailReminderTask();
        task.setId(3L);
        task.setRecipientEmail("a@example.com");
        task.setSubject("Hello");
        task.setMessage("Body");
        task.setContentType("PLAIN");
        task.setTemplateId("none");
        task.setFrequency("ONCE");
        task.setTimezone("Asia/Shanghai");
        task.setScheduledAt(NOW);
        task.setStatus(EmailReminderService.STATUS_PENDING);
        task.setSendAttempts(0);
        task.setCreatedAt(NOW);
        task.setUpdatedAt(NOW);

        Page<EmailReminderTask> page = new Page<>(2, 10, 15);
        page.setRecords(java.util.List.of(task));
        when(taskMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);
        when(taskMapper.selectCount(any(LambdaQueryWrapper.class)))
                .thenReturn(15L, 4L, 1L, 6L);

        EmailReminderListResult result = service.list(7L, 2, 10, "PENDING", "hello");

        assertThat(result.getCurrent()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(10);
        assertThat(result.getTotal()).isEqualTo(15);
        assertThat(result.getPages()).isEqualTo(2);
        assertThat(result.isHasMore()).isFalse();
        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).getSubject()).isEqualTo("Hello");
        assertThat(result.getStats().getTotal()).isEqualTo(15);
        assertThat(result.getStats().getPending()).isEqualTo(4);
        assertThat(result.getStats().getActive()).isEqualTo(5);
        assertThat(result.getStats().getSent()).isEqualTo(6);
        verify(taskMapper).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
    }

    @Test
    void listFallsBackToDefaultPageSizeWhenUnsupported() {
        Page<EmailReminderTask> page = new Page<>(1, 10, 0);
        page.setRecords(java.util.List.of());
        when(taskMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            Page<?> requested = invocation.getArgument(0);
            assertThat(requested.getSize()).isEqualTo(10);
            return page;
        });
        when(taskMapper.selectCount(any(LambdaQueryWrapper.class)))
                .thenReturn(0L, 0L, 0L, 0L);

        EmailReminderListResult result = service.list(7L, 1, 7, null, null);

        assertThat(result.getSize()).isEqualTo(10);
        assertThat(result.getRecords()).isEmpty();
    }
}

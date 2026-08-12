package com.xander.lab.blogagent.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.xander.lab.blogagent.infrastructure.BlogAgentProperties;
import com.xander.lab.blogagent.infrastructure.BlogAgentModelClient;
import com.xander.lab.blogagent.infrastructure.BlogAgentImageClient;
import com.xander.lab.blogagent.infrastructure.BlogAgentLeaseScheduler;
import com.xander.lab.publishing.application.BlogMediaService;
import com.xander.lab.publishing.application.BlogService;
import com.xander.lab.blogagent.domain.BlogAgentTask;
import com.xander.lab.blogagent.infrastructure.BlogAgentMessageMapper;
import com.xander.lab.blogagent.infrastructure.BlogAgentSourceMapper;
import com.xander.lab.blogagent.infrastructure.BlogAgentTaskMapper;
import com.xander.lab.blogagent.infrastructure.BlogAgentVersionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlogAgentServiceIdempotencyTest {

    @Mock
    private BlogAgentTaskMapper taskMapper;
    @Mock
    private BlogAgentModelClient modelClient;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private BlogAgentProperties properties;
    @Mock
    private BlogAgentVersionMapper versionMapper;
    @Mock
    private BlogAgentLeaseScheduler leaseScheduler;

    private BlogAgentService service;

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                BlogAgentTask.class);
    }

    @BeforeEach
    void setUp() {
        service = new BlogAgentService(
                taskMapper,
                mock(BlogAgentSourceMapper.class),
                versionMapper,
                modelClient,
                mock(com.fasterxml.jackson.databind.ObjectMapper.class),
                transactionTemplate,
                mock(BlogService.class),
                mock(BlogAgentImageClient.class),
                mock(BlogMediaService.class),
                properties,
                mock(BlogAgentMessageMapper.class),
                mock(BlogAgentEventService.class),
                leaseScheduler);
        ScheduledFuture<?> renewal = mock(ScheduledFuture.class);
        org.mockito.Mockito.lenient().doReturn(renewal).when(leaseScheduler).scheduleAtFixedRate(
                any(Runnable.class), any(Long.class));
    }

    @Test
    void duplicateInitialRunDoesNotCallModelAgain() {
        stubTransaction();
        BlogAgentTask running = task(7L, 42L, "running");
        when(taskMapper.update(isNull(), any())).thenReturn(0);
        when(taskMapper.selectById(7L)).thenReturn(running);

        assertThatThrownBy(() -> service.runStream(7L, 42L, (event, data) -> { }))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("正在执行");
        verifyNoInteractions(modelClient);
    }

    @Test
    void duplicateRevisionDoesNotCallModelAgain() {
        stubTransaction();
        BlogAgentTask running = task(7L, 42L, "running");
        when(taskMapper.update(isNull(), any())).thenReturn(0);
        when(taskMapper.selectById(7L)).thenReturn(running);

        assertThatThrownBy(() -> service.reviseStream(7L, 42L, "继续完善", (event, data) -> { }))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("重复提交");
        verifyNoInteractions(modelClient);
    }

    @Test
    void staleRunningTaskWithVerifiedVersionReturnsToReady() {
        BlogAgentTask stale = task(7L, 42L, "running");
        stale.setContent("已生成的文章草稿");
        stale.setUpdatedAt(LocalDateTime.now().minusMinutes(31));
        BlogAgentTask recovered = task(7L, 42L, "ready");
        recovered.setContent(stale.getContent());
        recovered.setErrorMessage("任务执行中断，已恢复到上一版已完成文章");
        when(properties.getStaleTaskMinutes()).thenReturn(30);
        when(versionMapper.selectCount(any())).thenReturn(1L);
        when(taskMapper.update(isNull(), any())).thenReturn(1);
        when(taskMapper.selectById(7L)).thenReturn(recovered);

        BlogAgentTask result = service.resolveStaleTask(stale, 42L);

        assertThat(result.getStatus()).isEqualTo("ready");
        assertThat(result.getContent()).isEqualTo("已生成的文章草稿");
        assertThat(result.getErrorMessage()).contains("已恢复");
    }

    @Test
    void staleRunningTaskWithoutDraftBecomesFailed() {
        BlogAgentTask stale = task(8L, 42L, "running");
        stale.setUpdatedAt(LocalDateTime.now().minusMinutes(31));
        BlogAgentTask failed = task(8L, 42L, "failed");
        failed.setErrorMessage("任务执行中断，请重新创建任务");
        when(properties.getStaleTaskMinutes()).thenReturn(30);
        when(versionMapper.selectCount(any())).thenReturn(0L);
        when(taskMapper.update(isNull(), any())).thenReturn(1);
        when(taskMapper.selectById(8L)).thenReturn(failed);

        BlogAgentTask result = service.resolveStaleTask(stale, 42L);

        assertThat(result.getStatus()).isEqualTo("failed");
        assertThat(result.getErrorMessage()).contains("重新创建");
    }

    @Test
    void staleInitialDraftWithoutVerifiedVersionBecomesFailed() {
        BlogAgentTask stale = task(9L, 42L, "running");
        stale.setContent("未完成审校、没有版本记录的半成品");
        stale.setUpdatedAt(LocalDateTime.now().minusMinutes(31));
        BlogAgentTask failed = task(9L, 42L, "failed");
        failed.setContent(stale.getContent());
        failed.setErrorMessage("任务执行中断，请重新创建任务");
        when(properties.getStaleTaskMinutes()).thenReturn(30);
        when(versionMapper.selectCount(any())).thenReturn(0L);
        when(taskMapper.update(isNull(), any())).thenReturn(1);
        when(taskMapper.selectById(9L)).thenReturn(failed);

        BlogAgentTask result = service.resolveStaleTask(stale, 42L);

        assertThat(result.getStatus()).isEqualTo("failed");
    }


    private void stubTransaction() {
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private BlogAgentTask task(Long id, Long userId, String status) {
        BlogAgentTask task = new BlogAgentTask();
        task.setId(id);
        task.setUserId(userId);
        task.setStatus(status);
        return task;
    }
}

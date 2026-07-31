package com.xander.lab.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.xander.lab.config.BlogAgentProperties;
import com.xander.lab.entity.BlogAgentTask;
import com.xander.lab.mapper.BlogAgentMessageMapper;
import com.xander.lab.mapper.BlogAgentSourceMapper;
import com.xander.lab.mapper.BlogAgentTaskMapper;
import com.xander.lab.mapper.BlogAgentVersionMapper;
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

import java.util.function.Consumer;

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
                mock(BlogAgentVersionMapper.class),
                modelClient,
                mock(com.fasterxml.jackson.databind.ObjectMapper.class),
                transactionTemplate,
                mock(BlogService.class),
                mock(BlogAgentImageClient.class),
                mock(BlogMediaService.class),
                mock(BlogAgentProperties.class),
                mock(BlogAgentMessageMapper.class),
                mock(BlogAgentEventService.class));
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void duplicateInitialRunDoesNotCallModelAgain() {
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
        BlogAgentTask running = task(7L, 42L, "running");
        when(taskMapper.update(isNull(), any())).thenReturn(0);
        when(taskMapper.selectById(7L)).thenReturn(running);

        assertThatThrownBy(() -> service.reviseStream(7L, 42L, "继续完善", (event, data) -> { }))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("重复提交");
        verifyNoInteractions(modelClient);
    }

    private BlogAgentTask task(Long id, Long userId, String status) {
        BlogAgentTask task = new BlogAgentTask();
        task.setId(id);
        task.setUserId(userId);
        task.setStatus(status);
        return task;
    }
}

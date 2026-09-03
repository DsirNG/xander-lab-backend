package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentRun;
import com.xander.lab.agent.domain.AgentStep;
import com.xander.lab.agent.domain.AgentToolInvocation;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.agent.infrastructure.AgentRunMapper;
import com.xander.lab.agent.infrastructure.AgentStepMapper;
import com.xander.lab.agent.infrastructure.AgentToolInvocationMapper;
import com.xander.lab.common.ModelCallResult;
import com.xander.lab.common.ModelUsage;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies the pre-side-effect facts written by the Phase 1 execution journal. */
class AgentExecutionJournalTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), AgentConversation.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), AgentRun.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), AgentStep.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), AgentToolInvocation.class);
    }

    @Test
    void createsRunStepAndInvocationBeforeTheToolCanExecute() {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentStepMapper stepMapper = mock(AgentStepMapper.class);
        AgentToolInvocationMapper invocationMapper = mock(AgentToolInvocationMapper.class);
        AgentConversation conversation = runningConversation();
        AgentRun run = runningRun();
        when(conversationMapper.selectById(7L)).thenReturn(conversation);
        when(runMapper.selectOne(any())).thenReturn(null, run);
        when(runMapper.insert(any())).thenAnswer(invocation -> {
            invocation.<AgentRun>getArgument(0).setId(17L);
            return 1;
        });
        when(stepMapper.insert(any())).thenAnswer(invocation -> {
            invocation.<AgentStep>getArgument(0).setId(23L);
            return 1;
        });
        when(invocationMapper.insert(any())).thenAnswer(invocation -> {
            invocation.<AgentToolInvocation>getArgument(0).setId(29L);
            return 1;
        });

        AgentExecutionJournal journal = new AgentExecutionJournal(
                conversationMapper, runMapper, stepMapper, invocationMapper);
        journal.startRun(7L, 42L, 3L);
        AgentStep step = journal.startStep(7L, 42L, 3L, 1,
                "{\"contextChars\":120,\"toolSetHash\":\"abcd\"}");
        AgentToolInvocation tool = journal.startInvocation(step, "00000000-0000-0000-0000-000000000001",
                "publish_post", JsonNodeFactory.instance.objectNode().put("postId", 99));

        verify(runMapper).insert(org.mockito.ArgumentMatchers.argThat(inserted ->
                inserted.getConversationId().equals(7L)
                        && inserted.getRunVersion().equals(3L)
                        && AgentRun.STATUS_RUNNING.equals(inserted.getStatus())));
        verify(stepMapper).insert(org.mockito.ArgumentMatchers.argThat(inserted ->
                inserted.getRunId().equals(17L)
                        && inserted.getStepNo().equals(1)
                        && AgentStep.STATUS_RUNNING.equals(inserted.getStatus())));
        verify(invocationMapper).insert(org.mockito.ArgumentMatchers.argThat(inserted ->
                inserted.getStepId().equals(23L)
                        && "00000000-0000-0000-0000-000000000001".equals(inserted.getInvocationId())
                        && "publish_post".equals(inserted.getToolName())
                        && AgentToolInvocation.STATUS_RUNNING.equals(inserted.getStatus())));

        when(stepMapper.update(any(), any())).thenReturn(1);
        journal.completeStep(step, ModelCallResult.of(
                JsonNodeFactory.instance.objectNode().put("done", false), new ModelUsage(10, 2, 5), "test-model"));
        verify(stepMapper).update(any(), any());
        assertThat(tool.getId()).isEqualTo(29L);
    }

    private AgentConversation runningConversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(7L);
        conversation.setUserId(42L);
        conversation.setRunVersion(3L);
        conversation.setStatus("running");
        return conversation;
    }

    private AgentRun runningRun() {
        AgentRun run = new AgentRun();
        run.setId(17L);
        run.setUserId(42L);
        run.setConversationId(7L);
        run.setRunVersion(3L);
        run.setStatus(AgentRun.STATUS_RUNNING);
        return run;
    }
}

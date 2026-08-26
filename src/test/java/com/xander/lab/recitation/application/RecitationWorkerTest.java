package com.xander.lab.recitation.application;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.recitation.domain.RecitationAttempt;
import com.xander.lab.recitation.domain.RecitationAttemptStatus;
import com.xander.lab.recitation.domain.RecitationMaterial;
import com.xander.lab.recitation.infrastructure.RecitationAttemptMapper;
import com.xander.lab.recitation.infrastructure.RecitationMaterialMapper;
import com.xander.lab.recitation.infrastructure.RecitationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecitationWorkerTest {

    @Mock
    private RecitationAttemptMapper attemptMapper;
    @Mock
    private RecitationMaterialMapper materialMapper;
    @Mock
    private RecitationAsrPort asrPort;
    @Mock
    private RecitationAligner aligner;
    @Mock
    private TransactionTemplate transactionTemplate;

    private RecitationProperties properties;
    private RecitationWorker worker;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, RecitationAttempt.class);
        TableInfoHelper.initTableInfo(assistant, RecitationMaterial.class);
        properties = new RecitationProperties();
        properties.setEnabled(true);
        properties.setWorkerBatchSize(10);
        properties.setStaleClaimSeconds(120);
        worker = new RecitationWorker(attemptMapper, materialMapper, asrPort, aligner, properties,
                transactionTemplate, new ObjectMapper());
    }

    @Test
    void disabledWorkerDoesNotReadOrMutateTasks() {
        properties.setEnabled(false);

        worker.process();

        verifyNoInteractions(attemptMapper, materialMapper, asrPort, aligner, transactionTemplate);
    }

    @Test
    void lostSubmitClaimDoesNotCallTheRemoteProvider() {
        RecitationAttempt pending = attempt(11L, RecitationAttemptStatus.PENDING);
        when(attemptMapper.selectList(any())).thenReturn(List.of(pending), List.of());
        // 前两次更新是陈旧租约恢复，第三次才是当前任务的原子认领。
        when(attemptMapper.update(isNull(), any())).thenReturn(1, 1, 0);

        worker.process();

        verifyNoInteractions(asrPort);
    }

    @Test
    void exhaustedPollingFailsWithoutAnotherRemoteCall() {
        RecitationAttempt processing = attempt(12L, RecitationAttemptStatus.PROCESSING);
        processing.setProviderId(3L);
        processing.setProviderTaskId("provider-task");
        RecitationAttempt claimed = attempt(12L, RecitationAttemptStatus.POLLING);
        claimed.setPollAttempts(181);
        when(attemptMapper.selectList(any())).thenReturn(List.of(), List.of(processing));
        when(attemptMapper.update(isNull(), any())).thenReturn(1);
        when(attemptMapper.selectById(12L)).thenReturn(claimed);

        worker.process();

        verify(asrPort, never()).poll(any(Long.class), any(String.class));
        assertThat(capturedUpdateValues()).contains(RecitationAttemptStatus.FAILED);
    }

    @Test
    void staleSubmitAndPollClaimsAreRecoveredToSafeStates() {
        when(attemptMapper.selectList(any())).thenReturn(List.of(), List.of());
        when(attemptMapper.update(isNull(), any())).thenReturn(1);

        worker.process();

        List<Object> values = capturedUpdateValues();
        assertThat(values).contains(RecitationAttemptStatus.FAILED, RecitationAttemptStatus.PROCESSING);
    }

    @Test
    void successfulPollCompletesAttemptAndUpdatesMaterialInOneTransaction() {
        RecitationAttempt processing = attempt(13L, RecitationAttemptStatus.PROCESSING);
        processing.setUserId(42L);
        processing.setMaterialId(9L);
        processing.setProviderId(3L);
        processing.setProviderTaskId("provider-task");
        RecitationAttempt claimed = attempt(13L, RecitationAttemptStatus.POLLING);
        claimed.setPollAttempts(1);
        claimed.setProviderId(3L);
        claimed.setProviderTaskId("provider-task");
        RecitationMaterial material = new RecitationMaterial();
        material.setId(9L);
        material.setUserId(42L);
        material.setContent("知识原文");

        when(attemptMapper.selectList(any())).thenReturn(List.of(), List.of(processing));
        when(attemptMapper.update(isNull(), any())).thenReturn(1);
        when(attemptMapper.selectById(13L)).thenReturn(claimed);
        when(materialMapper.selectById(9L)).thenReturn(material);
        when(asrPort.poll(3L, "provider-task")).thenReturn(new RecitationAsrPort.PollResult(
                RecitationAsrPort.PollResult.State.SUCCEEDED, "知识原文", List.of(), null));
        when(aligner.align("知识原文", "知识原文")).thenReturn(new RecitationAligner().align("知识原文", "知识原文"));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(org.mockito.Mockito.mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        worker.process();

        verify(transactionTemplate).executeWithoutResult(any());
        verify(materialMapper).update(isNull(), any());
        assertThat(capturedUpdateValues()).contains(RecitationAttemptStatus.SUCCEEDED);
    }

    private RecitationAttempt attempt(Long id, String status) {
        RecitationAttempt attempt = new RecitationAttempt();
        attempt.setId(id);
        attempt.setUserId(42L);
        attempt.setMaterialId(9L);
        attempt.setAudioUrl("https://oss.example/voice.webm");
        attempt.setStatus(status);
        return attempt;
    }

    private List<Object> capturedUpdateValues() {
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(attemptMapper, org.mockito.Mockito.atLeastOnce()).update(isNull(), captor.capture());
        return captor.getAllValues().stream()
                .filter(LambdaUpdateWrapper.class::isInstance)
                .map(LambdaUpdateWrapper.class::cast)
                .flatMap(wrapper -> wrapper.getParamNameValuePairs().values().stream())
                .toList();
    }
}

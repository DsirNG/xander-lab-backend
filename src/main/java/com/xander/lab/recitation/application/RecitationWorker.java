package com.xander.lab.recitation.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.recitation.domain.RecitationAttempt;
import com.xander.lab.recitation.domain.RecitationAttemptStatus;
import com.xander.lab.recitation.domain.RecitationMaterial;
import com.xander.lab.recitation.infrastructure.RecitationAttemptMapper;
import com.xander.lab.recitation.infrastructure.RecitationMaterialMapper;
import com.xander.lab.recitation.infrastructure.RecitationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 多实例安全地提交并轮询背诵音转文任务。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecitationWorker {
    private static final int MAX_POLL_ATTEMPTS = 180;

    private final RecitationAttemptMapper attemptMapper;
    private final RecitationMaterialMapper materialMapper;
    private final RecitationAsrPort asrPort;
    private final RecitationAligner aligner;
    private final RecitationProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** 每轮有界处理待提交和待查询任务；单条失败不会中断整个批次。 */
    @Scheduled(fixedDelayString = "${recitation.worker-poll-interval-ms:2000}")
    public void process() {
        if (!properties.isEnabled()) return;
        recoverStaleClaims();
        submitPending();
        pollProcessing();
    }

    private void submitPending() {
        List<RecitationAttempt> tasks = attemptMapper.selectList(new LambdaQueryWrapper<RecitationAttempt>()
                .eq(RecitationAttempt::getStatus, RecitationAttemptStatus.PENDING)
                .orderByAsc(RecitationAttempt::getCreatedAt, RecitationAttempt::getId)
                .last("LIMIT " + properties.getWorkerBatchSize()));
        for (RecitationAttempt task : tasks) {
            String token = UUID.randomUUID().toString();
            int claimed = attemptMapper.update(null, new LambdaUpdateWrapper<RecitationAttempt>()
                    .eq(RecitationAttempt::getId, task.getId())
                    .eq(RecitationAttempt::getStatus, RecitationAttemptStatus.PENDING)
                    .set(RecitationAttempt::getStatus, RecitationAttemptStatus.SUBMITTING)
                    .set(RecitationAttempt::getRunToken, token)
                    .set(RecitationAttempt::getUpdatedAt, LocalDateTime.now()));
            if (claimed != 1) continue;
            try {
                RecitationAsrPort.SubmittedTask submitted = asrPort.submit(task.getAudioUrl());
                attemptMapper.update(null, new LambdaUpdateWrapper<RecitationAttempt>()
                        .eq(RecitationAttempt::getId, task.getId())
                        .eq(RecitationAttempt::getStatus, RecitationAttemptStatus.SUBMITTING)
                        .eq(RecitationAttempt::getRunToken, token)
                        .set(RecitationAttempt::getStatus, RecitationAttemptStatus.PROCESSING)
                        .set(RecitationAttempt::getProviderTaskId, submitted.taskId())
                        .set(RecitationAttempt::getProviderId, submitted.providerId())
                        .set(RecitationAttempt::getModel, submitted.model())
                        .set(RecitationAttempt::getRunToken, null)
                        .set(RecitationAttempt::getUpdatedAt, LocalDateTime.now()));
            } catch (Exception error) {
                log.warn("recitation attempt {} submit failed", task.getId());
                failClaimed(task.getId(), RecitationAttemptStatus.SUBMITTING, token,
                        "语音识别服务暂时不可用，请稍后重新录音");
            }
        }
    }

    private void pollProcessing() {
        List<RecitationAttempt> tasks = attemptMapper.selectList(new LambdaQueryWrapper<RecitationAttempt>()
                .eq(RecitationAttempt::getStatus, RecitationAttemptStatus.PROCESSING)
                .orderByAsc(RecitationAttempt::getUpdatedAt, RecitationAttempt::getId)
                .last("LIMIT " + properties.getWorkerBatchSize()));
        for (RecitationAttempt task : tasks) {
            String token = UUID.randomUUID().toString();
            int claimed = attemptMapper.update(null, new LambdaUpdateWrapper<RecitationAttempt>()
                    .eq(RecitationAttempt::getId, task.getId())
                    .eq(RecitationAttempt::getStatus, RecitationAttemptStatus.PROCESSING)
                    .set(RecitationAttempt::getStatus, RecitationAttemptStatus.POLLING)
                    .set(RecitationAttempt::getRunToken, token)
                    .setSql("poll_attempts = poll_attempts + 1")
                    .set(RecitationAttempt::getUpdatedAt, LocalDateTime.now()));
            if (claimed != 1) continue;
            RecitationAttempt claimedTask = attemptMapper.selectById(task.getId());
            if (claimedTask.getPollAttempts() != null && claimedTask.getPollAttempts() > MAX_POLL_ATTEMPTS) {
                failClaimed(task.getId(), RecitationAttemptStatus.POLLING, token, "语音识别等待超时，请重新录音");
                continue;
            }
            try {
                RecitationAsrPort.PollResult result = asrPort.poll(
                        claimedTask.getProviderId(), claimedTask.getProviderTaskId());
                switch (result.state()) {
                    case PROCESSING -> releaseForNextPoll(task.getId(), token);
                    case FAILED -> failClaimed(task.getId(), RecitationAttemptStatus.POLLING, token,
                            result.errorMessage() == null ? "语音识别失败" : result.errorMessage());
                    case SUCCEEDED -> complete(task, token, result);
                }
            } catch (Exception error) {
                log.warn("recitation attempt {} poll failed", task.getId());
                releaseForNextPoll(task.getId(), token);
            }
        }
    }

    private void complete(RecitationAttempt task, String token, RecitationAsrPort.PollResult result) {
        RecitationMaterial material = materialMapper.selectById(task.getMaterialId());
        if (material == null || !task.getUserId().equals(material.getUserId())) {
            failClaimed(task.getId(), RecitationAttemptStatus.POLLING, token, "背诵材料已不存在");
            return;
        }
        try {
            RecitationAligner.AlignmentResult alignment = aligner.align(material.getContent(), result.transcript());
            ObjectNode report = objectMapper.valueToTree(alignment);
            report.set("words", objectMapper.valueToTree(result.words()));
            String resultJson = objectMapper.writeValueAsString(report);
            LocalDateTime reviewedAt = LocalDateTime.now();
            MasteryPolicy.Result mastery = MasteryPolicy.evaluate(alignment.score(), reviewedAt);
            transactionTemplate.executeWithoutResult(status -> {
                int completed = attemptMapper.update(null, new LambdaUpdateWrapper<RecitationAttempt>()
                            .eq(RecitationAttempt::getId, task.getId())
                            .eq(RecitationAttempt::getStatus, RecitationAttemptStatus.POLLING)
                            .eq(RecitationAttempt::getRunToken, token)
                            .set(RecitationAttempt::getStatus, RecitationAttemptStatus.SUCCEEDED)
                            .set(RecitationAttempt::getTranscript, result.transcript())
                            .set(RecitationAttempt::getResultJson, resultJson)
                            .set(RecitationAttempt::getScore, alignment.score())
                            .set(RecitationAttempt::getErrorMessage, null)
                            .set(RecitationAttempt::getRunToken, null)
                            .set(RecitationAttempt::getUpdatedAt, reviewedAt));
                if (completed == 1) {
                    materialMapper.update(null, new LambdaUpdateWrapper<RecitationMaterial>()
                            .eq(RecitationMaterial::getId, material.getId())
                            .eq(RecitationMaterial::getUserId, task.getUserId())
                            .set(RecitationMaterial::getMasteryScore, alignment.score())
                            .set(RecitationMaterial::getMasteryLevel, mastery.level())
                            .setSql("review_count = review_count + 1")
                            .set(RecitationMaterial::getLastReviewAt, reviewedAt)
                            .set(RecitationMaterial::getNextReviewAt, mastery.nextReviewAt())
                            .set(RecitationMaterial::getUpdatedAt, reviewedAt));
                }
            });
        } catch (Exception error) {
            log.warn("recitation attempt {} alignment failed", task.getId());
            failClaimed(task.getId(), RecitationAttemptStatus.POLLING, token, "背诵结果分析失败");
        }
    }

    private void releaseForNextPoll(Long id, String token) {
        attemptMapper.update(null, new LambdaUpdateWrapper<RecitationAttempt>()
                .eq(RecitationAttempt::getId, id)
                .eq(RecitationAttempt::getStatus, RecitationAttemptStatus.POLLING)
                .eq(RecitationAttempt::getRunToken, token)
                .set(RecitationAttempt::getStatus, RecitationAttemptStatus.PROCESSING)
                .set(RecitationAttempt::getRunToken, null)
                .set(RecitationAttempt::getUpdatedAt, LocalDateTime.now()));
    }

    private void failClaimed(Long id, String expectedStatus, String token, String message) {
        attemptMapper.update(null, new LambdaUpdateWrapper<RecitationAttempt>()
                .eq(RecitationAttempt::getId, id)
                .eq(RecitationAttempt::getStatus, expectedStatus)
                .eq(RecitationAttempt::getRunToken, token)
                .set(RecitationAttempt::getStatus, RecitationAttemptStatus.FAILED)
                .set(RecitationAttempt::getErrorMessage, message)
                .set(RecitationAttempt::getRunToken, null)
                .set(RecitationAttempt::getUpdatedAt, LocalDateTime.now()));
    }

    private void recoverStaleClaims() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(properties.getStaleClaimSeconds());
        // 提交中断后无法确认供应商是否已计费，宁可失败也不自动重复提交。
        attemptMapper.update(null, new LambdaUpdateWrapper<RecitationAttempt>()
                .eq(RecitationAttempt::getStatus, RecitationAttemptStatus.SUBMITTING)
                .lt(RecitationAttempt::getUpdatedAt, cutoff)
                .set(RecitationAttempt::getStatus, RecitationAttemptStatus.FAILED)
                .set(RecitationAttempt::getErrorMessage, "语音识别提交中断，请重新录音")
                .set(RecitationAttempt::getRunToken, null)
                .set(RecitationAttempt::getUpdatedAt, LocalDateTime.now()));
        // 查询是只读操作，可安全释放过期租约并由下一轮继续。
        attemptMapper.update(null, new LambdaUpdateWrapper<RecitationAttempt>()
                .eq(RecitationAttempt::getStatus, RecitationAttemptStatus.POLLING)
                .lt(RecitationAttempt::getUpdatedAt, cutoff)
                .set(RecitationAttempt::getStatus, RecitationAttemptStatus.PROCESSING)
                .set(RecitationAttempt::getRunToken, null)
                .set(RecitationAttempt::getUpdatedAt, LocalDateTime.now()));
    }
}

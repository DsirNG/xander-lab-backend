package com.xander.lab.automation.application;

import com.xander.lab.automation.domain.BlogContentPlan;
import com.xander.lab.automation.domain.BlogNotification;
import com.xander.lab.automation.domain.BlogPlanRun;
import com.xander.lab.automation.infrastructure.BlogContentPlanMapper;
import com.xander.lab.automation.infrastructure.BlogNotificationMapper;
import com.xander.lab.automation.infrastructure.BlogPlanRunMapper;
import com.xander.lab.blogagent.api.BlogAgentTaskCreateRequest;
import com.xander.lab.blogagent.api.BlogAgentTaskVO;
import com.xander.lab.blogagent.application.BlogAgentService;
import com.xander.lab.blogagent.application.ComplianceReview;
import com.xander.lab.blogagent.domain.BlogAgentTask;
import com.xander.lab.integration.mcp.csdn.application.CsdnCredentialService;
import com.xander.lab.publishing.application.PublicationService;
import com.xander.lab.publishing.domain.PublishCommand;
import com.xander.lab.publishing.domain.PublishResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The single-run pipeline: generation, compliance gate (fail-closed), local
 * publication, CSDN best-effort sync, durable terminal status, notifications. */
@ExtendWith(MockitoExtension.class)
class BlogPlanRunServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-24T09:00:00Z");

    @Mock private BlogPlanRunMapper runMapper;
    @Mock private BlogNotificationMapper notificationMapper;
    @Mock private BlogAgentService agentService;
    @Mock private PublicationService publicationService;
    @Mock private CsdnCredentialService csdnCredentialService;
    @Mock private BlogContentPlanMapper planMapper;
    @Mock private com.xander.lab.automation.infrastructure.BlogPlanSseService sseService;
    @Mock private TransactionTemplate transactionTemplate;

    private BlogPlanRunService newService() {
        wireTransactionTemplate();
        return new BlogPlanRunService(runMapper, planMapper, notificationMapper, agentService,
                publicationService, csdnCredentialService, sseService, transactionTemplate);
    }

    private void wireTransactionTemplate() {
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<Void> callback = (Consumer<Void>) invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(org.mockito.ArgumentMatchers.any());
    }

    /** Regression: the CSDN backlink is built from -taskId, so the CSDN publish
     * must carry -localPostId (never -runId), otherwise the original link
     * points at /blog/{runId} and the post reads as unsynced. */
    @Test
    void csdnPublishUsesNegativeLocalPostIdAsTaskId() {
        mockRunRow();
        BlogAgentTask task = task(104L);
        BlogAgentTaskVO ready = readyVo(task);
        when(agentService.create(any(Long.class), any(BlogAgentTaskCreateRequest.class)))
                .thenReturn(task);
        when(agentService.run(task.getId(), 7L)).thenReturn(ready);
        when(agentService.reviewCompliance(task.getId(), 7L))
                .thenReturn(new ComplianceReview(true, null));
        when(agentService.get(task.getId(), 7L)).thenReturn(ready);
        when(publicationService.publish(any(), org.mockito.ArgumentMatchers.eq("local")))
                .thenReturn(new PublishResult("local", "45", null));
        when(csdnCredentialService.isAuthorized(7L)).thenReturn(true);
        when(publicationService.publish(any(), org.mockito.ArgumentMatchers.eq("csdn")))
                .thenReturn(new PublishResult("csdn", "csdn-45", "https://blog.csdn.net/x/45"));

        BlogPlanRunService service = newService();
        service.executeRun(runRow(), plan(true));

        ArgumentCaptor<PublishCommand> command = ArgumentCaptor.forClass(PublishCommand.class);
        verify(publicationService).publish(command.capture(), org.mockito.ArgumentMatchers.eq("csdn"));
        assertThat(command.getValue().taskId()).isEqualTo(-45L);
    }

    /** A plan topic queue consumes the next topic per run and advances the index. */
    @Test
    void topicQueueConsumesNextTopicAndAdvancesIndex() {
        BlogContentPlan queued = plan(false);
        queued.setTopicsJson(BlogContentPlan.toJson(List.of("K8s 调度器", "K8s 网络", "K8s 存储")));
        queued.setNextTopicIndex(1);
        when(planMapper.selectByIdForUpdate(1L)).thenReturn(queued);
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            org.springframework.transaction.support.TransactionCallback<Object> callback =
                    (org.springframework.transaction.support.TransactionCallback<Object>) invocation.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(org.mockito.ArgumentMatchers.any());

        BlogAgentTask task = task(105L);
        when(agentService.create(any(Long.class), any(BlogAgentTaskCreateRequest.class)))
                .thenAnswer(invocation -> {
                    BlogAgentTaskCreateRequest request = invocation.getArgument(1);
                    assertThat(request.getInput()).contains("K8s 网络");
                    return task;
                });

        BlogPlanRunService service = newService();
        BlogAgentTask created = service.createTask(plan(false), 7L);

        assertThat(created).isSameAs(task);
        verify(planMapper).updateById(org.mockito.ArgumentMatchers.argThat(
                saved -> saved.getNextTopicIndex() == 2));
    }

    @Test
    void complianceRejectionEndsRunRejectedAndPublishesNothing() {
        mockRunRow();
        BlogAgentTask task = task(101L);
        when(agentService.create(any(Long.class), any(BlogAgentTaskCreateRequest.class)))
                .thenReturn(task);
        when(agentService.run(task.getId(), 7L)).thenReturn(new BlogAgentTaskVO());
        when(agentService.reviewCompliance(task.getId(), 7L))
                .thenReturn(new ComplianceReview(false, "内容未通过审核"));

        BlogPlanRunService service = newService();
        service.executeRun(runRow(), plan(false));

        ArgumentCaptor<BlogNotification> notification = ArgumentCaptor.forClass(BlogNotification.class);
        verify(notificationMapper).insert(notification.capture());
        assertThat(notification.getValue().getType()).isEqualTo(BlogNotification.TYPE_REVIEW_REJECTED);

        BlogPlanRun updated = updatedRun();
        assertThat(updated.getStatus()).isEqualTo(BlogPlanRun.STATUS_REVIEW_REJECTED);
        assertThat(updated.getReviewPass()).isFalse();
        assertThat(updated.getReviewReason()).isEqualTo("内容未通过审核");
        verify(publicationService, org.mockito.Mockito.never()).publish(any(), any());
    }

    @Test
    void successfulRunPublishesLocallyAndSetsSucceeded() {
        mockRunRow();
        BlogAgentTask task = task(102L);
        BlogAgentTaskVO ready = readyVo(task);
        when(agentService.create(any(Long.class), any(BlogAgentTaskCreateRequest.class)))
                .thenReturn(task);
        when(agentService.run(task.getId(), 7L)).thenReturn(ready);
        when(agentService.reviewCompliance(task.getId(), 7L))
                .thenReturn(new ComplianceReview(true, null));
        when(agentService.get(task.getId(), 7L)).thenReturn(ready);
        when(publicationService.publish(any(), any(String.class)))
                .thenReturn(new PublishResult("local", "55", null));

        BlogPlanRunService service = newService();
        service.executeRun(runRow(), plan(false));

        verify(publicationService).publish(any(), org.mockito.ArgumentMatchers.eq("local"));
        BlogPlanRun updated = updatedRun();
        assertThat(updated.getStatus()).isEqualTo(BlogPlanRun.STATUS_SUCCEEDED);
        assertThat(updated.getReviewPass()).isTrue();
        assertThat(updated.getLocalPostId()).isEqualTo(55L);
    }

    @Test
    void csdnFailureAfterLocalPublishStillSucceedsButNotifies() {
        mockRunRow();
        BlogAgentTask task = task(103L);
        BlogAgentTaskVO ready = readyVo(task);
        when(agentService.create(any(Long.class), any(BlogAgentTaskCreateRequest.class)))
                .thenReturn(task);
        when(agentService.run(task.getId(), 7L)).thenReturn(ready);
        when(agentService.reviewCompliance(task.getId(), 7L))
                .thenReturn(new ComplianceReview(true, null));
        when(agentService.get(task.getId(), 7L)).thenReturn(ready);
        when(publicationService.publish(any(), any(String.class)))
                .thenReturn(new PublishResult("local", "56", null));
        when(csdnCredentialService.isAuthorized(7L)).thenReturn(true);
        when(publicationService.publish(any(), org.mockito.ArgumentMatchers.eq("csdn")))
                .thenThrow(new RuntimeException("csdn 503"));

        BlogPlanRunService service = newService();
        service.executeRun(runRow(), plan(true));

        BlogPlanRun updated = updatedRun();
        assertThat(updated.getStatus()).isEqualTo(BlogPlanRun.STATUS_SUCCEEDED);
        assertThat(updated.getReviewPass()).isTrue();
        assertThat(updated.getCsdnStatus()).isEqualTo(BlogPlanRun.CSDN_FAILED);

        ArgumentCaptor<BlogNotification> notification = ArgumentCaptor.forClass(BlogNotification.class);
        verify(notificationMapper).insert(notification.capture());
        assertThat(notification.getValue().getType()).isEqualTo(BlogNotification.TYPE_CSDN_FAILED);
    }

    @Test
    void generationCrashMarksRunFailedAndNotifies() {
        mockRunRow();
        when(agentService.create(any(Long.class), any(BlogAgentTaskCreateRequest.class)))
                .thenThrow(new IllegalStateException("model timeout"));

        BlogPlanRunService service = newService();
        service.executeRun(runRow(), plan(false));

        BlogPlanRun updated = updatedRun();
        assertThat(updated.getStatus()).isEqualTo(BlogPlanRun.STATUS_FAILED);
        assertThat(updated.getErrorMessage()).contains("model timeout");

        ArgumentCaptor<BlogNotification> notification = ArgumentCaptor.forClass(BlogNotification.class);
        verify(notificationMapper).insert(notification.capture());
        assertThat(notification.getValue().getType()).isEqualTo(BlogNotification.TYPE_RUN_FAILED);
    }

    private void mockRunRow() {
        when(runMapper.selectById(77L)).thenReturn(runRow());
    }

    private BlogPlanRun runRow() {
        BlogPlanRun run = new BlogPlanRun();
        run.setId(77L);
        run.setPlanId(1L);
        run.setScheduledAt(NOW);
        run.setStatus(BlogPlanRun.STATUS_GENERATING);
        run.setCsdnStatus(BlogPlanRun.CSDN_PENDING);
        return run;
    }

    private BlogPlanRun updatedRun() {
        return runMapper.selectById(77L);
    }

    private BlogAgentTask task(Long id) {
        BlogAgentTask task = new BlogAgentTask();
        task.setId(id);
        return task;
    }

    private BlogAgentTaskVO readyVo(BlogAgentTask task) {
        task.setTitle("Kubernetes 入门实践");
        task.setSummary("从零开始理解 Kubernetes 的部署与调优");
        task.setContent("这是一篇测试文章正文内容");
        task.setCategoryId("66");
        BlogAgentTaskVO ready = new BlogAgentTaskVO();
        ready.setTask(task);
        ready.setTags(List.of("k8s", "devops"));
        return ready;
    }

    private BlogContentPlan plan(boolean syncCsdn) {
        BlogContentPlan plan = new BlogContentPlan();
        plan.setId(1L);
        plan.setUserId(7L);
        plan.setTopic("Kubernetes 入门");
        plan.setTimezone("Asia/Shanghai");
        plan.setTriggerTime("09:00");
        plan.setSyncCsdn(syncCsdn);
        return plan;
    }
}
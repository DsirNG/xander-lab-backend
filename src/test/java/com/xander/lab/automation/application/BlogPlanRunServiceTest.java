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
        return new BlogPlanRunService(runMapper, notificationMapper, agentService,
                publicationService, csdnCredentialService, sseService, transactionTemplate);
    }

    private void wireTransactionTemplate() {
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<Void> callback = (Consumer<Void>) invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(org.mockito.ArgumentMatchers.any());
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
        BlogAgentTaskVO ready = new BlogAgentTaskVO();
        ready.setTags(List.of("k8s", "devops"));
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
        BlogAgentTaskVO ready = new BlogAgentTaskVO();
        ready.setTags(List.of("t1"));
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
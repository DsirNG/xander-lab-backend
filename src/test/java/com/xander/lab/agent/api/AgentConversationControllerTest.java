package com.xander.lab.agent.api;

import com.xander.lab.agent.application.AgentConversationService;
import com.xander.lab.agent.application.AgentAttachmentService;
import com.xander.lab.agent.application.AgentEventService;
import com.xander.lab.agent.application.AgentApprovalService;
import com.xander.lab.agent.domain.AgentApproval;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.identity.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentConversationControllerTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void createReturnsReadyConversationWithoutStartingTurn() {
        AgentConversationService service = mock(AgentConversationService.class);
        AgentEventService eventService = mock(AgentEventService.class);
        TaskExecutor directExecutor = Runnable::run;
        AgentConversationController controller = new AgentConversationController(
                service, mock(AgentAttachmentService.class), eventService,
                directExecutor, directExecutor, mock(TaskScheduler.class));
        AgentConversation ready = new AgentConversation();
        ready.setId(7L);
        ready.setUserId(42L);
        ready.setTitle("你好智能体");
        ready.setStatus("ready");
        ready.setRunVersion(1L);
        when(service.create(42L, "你好智能体")).thenReturn(ready);
        when(service.get(7L, 42L)).thenReturn(ready);
        when(service.listMessages(7L, 42L)).thenReturn(List.of());
        UserContext.setUserId(42L);
        AgentConversationMessageRequest request = new AgentConversationMessageRequest();
        request.setContent("你好智能体");

        AgentConversationVO body = controller.create(request).getData();

        assertThat(body.getConversation()).isSameAs(ready);
        verify(service).create(42L, "你好智能体");
        verify(service, never()).executeClaimedTurn(any(), any(), anyLong(), any(), anyBoolean(), any());
    }

    @Test
    void messageStreamPassesTheExactClaimedRunVersionToWorker() {
        AgentConversationService service = mock(AgentConversationService.class);
        AgentEventService eventService = mock(AgentEventService.class);
        TaskExecutor directExecutor = Runnable::run;
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        org.mockito.Mockito.doReturn(heartbeat).when(scheduler).scheduleAtFixedRate(
                any(Runnable.class), any(java.time.Instant.class), any(java.time.Duration.class));
        AgentConversationController controller = new AgentConversationController(
                service, mock(AgentAttachmentService.class), eventService,
                directExecutor, directExecutor, scheduler);
        AgentConversation claimed = new AgentConversation();
        claimed.setId(7L);
        claimed.setUserId(42L);
        claimed.setStatus("running");
        claimed.setRunVersion(9L);
        when(service.prepareTurn(7L, 42L, "继续", List.of())).thenReturn(claimed);
        UserContext.setUserId(42L);
        AgentConversationMessageRequest request = new AgentConversationMessageRequest();
        request.setContent("继续");

        SseEmitter emitter = controller.sendMessage(7L, request, new MockHttpServletResponse());

        assertThat(emitter).isNotNull();
        verify(service).executeClaimedTurn(eq(7L), eq(42L), eq(9L), eq("继续"), eq(false), any());
        verify(heartbeat).cancel(false);
    }

    @Test
    void messageStreamForwardsTheDeepThinkingChoiceToWorker() {
        AgentConversationService service = mock(AgentConversationService.class);
        AgentEventService eventService = mock(AgentEventService.class);
        TaskExecutor directExecutor = Runnable::run;
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        org.mockito.Mockito.doReturn(heartbeat).when(scheduler).scheduleAtFixedRate(
                any(Runnable.class), any(java.time.Instant.class), any(java.time.Duration.class));
        AgentConversationController controller = new AgentConversationController(
                service, mock(AgentAttachmentService.class), eventService,
                directExecutor, directExecutor, scheduler);
        AgentConversation claimed = new AgentConversation();
        claimed.setId(7L);
        claimed.setUserId(42L);
        claimed.setStatus("running");
        claimed.setRunVersion(9L);
        when(service.prepareTurn(7L, 42L, "继续", List.of())).thenReturn(claimed);
        UserContext.setUserId(42L);
        AgentConversationMessageRequest request = new AgentConversationMessageRequest();
        request.setContent("继续");
        request.setDeepThinking(true);

        controller.sendMessage(7L, request, new MockHttpServletResponse());

        verify(service).executeClaimedTurn(eq(7L), eq(42L), eq(9L), eq("继续"), eq(true), any());
    }

    @Test
    void messageTriggerClaimsTurnAndRunsInBackgroundWithoutEmitter() {
        AgentConversationService service = mock(AgentConversationService.class);
        AgentEventService eventService = mock(AgentEventService.class);
        TaskExecutor directExecutor = Runnable::run;
        AgentConversationController controller = new AgentConversationController(
                service, mock(AgentAttachmentService.class), eventService,
                directExecutor, directExecutor, mock(TaskScheduler.class));
        AgentConversation claimed = new AgentConversation();
        claimed.setId(7L);
        claimed.setUserId(42L);
        claimed.setStatus("running");
        claimed.setRunVersion(9L);
        when(service.prepareTurn(7L, 42L, "继续", List.of())).thenReturn(claimed);
        UserContext.setUserId(42L);
        AgentConversationMessageRequest request = new AgentConversationMessageRequest();
        request.setContent("继续");

        Long runVersion = controller.triggerMessage(7L, request).getData();

        assertThat(runVersion).isEqualTo(9L);
        verify(service).executeClaimedTurn(eq(7L), eq(42L), eq(9L), eq("继续"), eq(false), any());
        verify(service, never()).failClaimedTurn(any(), any(), anyLong(), any());
    }

    @Test
    void messageTriggerMarksClaimFailedWhenWorkerPoolRejects() {
        AgentConversationService service = mock(AgentConversationService.class);
        AgentEventService eventService = mock(AgentEventService.class);
        TaskExecutor rejectingExecutor = runnable -> {
            throw new RejectedExecutionException("pool full");
        };
        AgentConversationController controller = new AgentConversationController(
                service, mock(AgentAttachmentService.class), eventService,
                rejectingExecutor, rejectingExecutor, mock(TaskScheduler.class));
        AgentConversation claimed = new AgentConversation();
        claimed.setId(7L);
        claimed.setUserId(42L);
        claimed.setStatus("running");
        claimed.setRunVersion(9L);
        when(service.prepareTurn(7L, 42L, "继续", List.of())).thenReturn(claimed);
        UserContext.setUserId(42L);
        AgentConversationMessageRequest request = new AgentConversationMessageRequest();
        request.setContent("继续");

        assertThatThrownBy(() -> controller.triggerMessage(7L, request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
        verify(service, never()).executeClaimedTurn(any(), any(), anyLong(), any(), anyBoolean(), any());
        verify(service).failClaimedTurn(eq(7L), eq(42L), eq(9L), eq("智能体执行队列繁忙，请稍后重试"));
    }

    @Test
    void approvalDecisionIsScopedToTheCurrentConversationOwner() {
        AgentApprovalService approvals = mock(AgentApprovalService.class);
        AgentConversationController controller = new AgentConversationController(
                mock(AgentConversationService.class), mock(AgentAttachmentService.class),
                mock(AgentEventService.class), approvals, Runnable::run, Runnable::run, mock(TaskScheduler.class));
        AgentApproval approval = new AgentApproval();
        approval.setId(9L);
        approval.setStatus(AgentApproval.STATUS_APPROVED);
        when(approvals.decide(7L, 9L, 42L, true, "confirm")).thenReturn(approval);
        UserContext.setUserId(42L);
        AgentApprovalDecisionRequest request = new AgentApprovalDecisionRequest();
        request.setApproved(true);
        request.setReason("confirm");

        AgentApproval result = controller.decideApproval(7L, 9L, request).getData();

        assertThat(result).isSameAs(approval);
        verify(approvals).decide(7L, 9L, 42L, true, "confirm");
    }

    /**
     * 恢复之后必须把**同一代次**交回工作线程池。
     *
     * <p>两件事一起钉：① `resume` 只改状态、不派活，所以端点上必须接着提交一次，
     * 否则会话停在 `running` 而无人执行；② 交出去的必须是**原来那一代次**——
     * 恢复如果顺手 `prepareTurn` 开了新一代，就等于"用户点继续"变成了"用户又说了一句话"，
     * 而 `intent_epoch` 会跟着换新，之前那条中断的执行永远接不上（这正是 R-1 要防的事）。</p>
     */
    @Test
    void resumeSubmitsTheSameRunVersionToTheWorker() {
        AgentConversationService service = mock(AgentConversationService.class);
        TaskExecutor directExecutor = Runnable::run;
        AgentConversationController controller = new AgentConversationController(
                service, mock(AgentAttachmentService.class), mock(AgentEventService.class),
                directExecutor, directExecutor, mock(TaskScheduler.class));
        AgentConversation resumed = new AgentConversation();
        resumed.setId(7L);
        resumed.setUserId(42L);
        resumed.setStatus("running");
        resumed.setRunVersion(9L);
        when(service.resume(7L, 42L)).thenReturn(resumed);
        UserContext.setUserId(42L);

        AgentConversation body = controller.resume(7L).getData();

        assertThat(body).isSameAs(resumed);
        verify(service).executeClaimedTurn(eq(7L), eq(42L), eq(9L), isNull(), eq(false), any());
        // 恢复不是新的一轮：绝不能顺手 claim 一个新时代次。
        verify(service, never()).prepareTurn(any(), any(), any(), any());
        verify(service, never()).failClaimedTurn(any(), any(), anyLong(), any());
    }

    /**
     * 工作线程池拒绝时不能留下一个"看起来在跑、其实没人跑"的会话。
     *
     * <p>状态这时已经被推到 {@code running} 了，所以必须显式把它标记为失败——否则用户看到
     * "正在生成"却永远等不到结果，只能等 reaper 按租约超时再收一次。</p>
     */
    @Test
    void resumeMarksTheRunFailedWhenWorkerPoolRejects() {
        AgentConversationService service = mock(AgentConversationService.class);
        TaskExecutor rejectingExecutor = runnable -> {
            throw new RejectedExecutionException("pool full");
        };
        AgentConversationController controller = new AgentConversationController(
                service, mock(AgentAttachmentService.class), mock(AgentEventService.class),
                rejectingExecutor, rejectingExecutor, mock(TaskScheduler.class));
        AgentConversation resumed = new AgentConversation();
        resumed.setId(7L);
        resumed.setUserId(42L);
        resumed.setStatus("running");
        resumed.setRunVersion(9L);
        when(service.resume(7L, 42L)).thenReturn(resumed);
        UserContext.setUserId(42L);

        assertThatThrownBy(() -> controller.resume(7L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
        verify(service, never()).executeClaimedTurn(any(), any(), anyLong(), any(), anyBoolean(), any());
        verify(service).failClaimedTurn(eq(7L), eq(42L), eq(9L), eq("智能体执行队列繁忙，请稍后重试"));
    }

    /**
     * 不可恢复的状态要原样变成 409，而不是被兜底成 500。
     *
     * <p>这是"资源当前状态不允许这个动作"，是正常的业务拒绝；收成 500 会让前端把它当成服务器
     * 故障去重试，而重试永远不会成功。服务层抛的是 {@code AgentRunNotResumableException}
     * （那个类在 application 包内可见，这里按它的语义断言，不依赖类型本身）。</p>
     */
    @Test
    void resumePropagatesTheConflictWhenTheRunIsNotResumable() {
        AgentConversationService service = mock(AgentConversationService.class);
        TaskExecutor directExecutor = Runnable::run;
        AgentConversationController controller = new AgentConversationController(
                service, mock(AgentAttachmentService.class), mock(AgentEventService.class),
                directExecutor, directExecutor, mock(TaskScheduler.class));
        when(service.resume(7L, 42L)).thenThrow(new ResponseStatusException(
                HttpStatus.CONFLICT, "当前执行状态不可恢复"));
        UserContext.setUserId(42L);

        assertThatThrownBy(() -> controller.resume(7L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        verify(service, never()).executeClaimedTurn(any(), any(), anyLong(), any(), anyBoolean(), any());
    }
}

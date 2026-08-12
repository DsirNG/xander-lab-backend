package com.xander.lab.agent.api;

import com.xander.lab.agent.application.AgentConversationService;
import com.xander.lab.agent.application.AgentEventService;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.identity.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentConversationControllerTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void createStartsAlreadyClaimedInitialTurnOnServer() {
        AgentConversationService service = mock(AgentConversationService.class);
        AgentEventService eventService = mock(AgentEventService.class);
        TaskExecutor directExecutor = Runnable::run;
        AgentConversationController controller = new AgentConversationController(
                service, eventService, directExecutor, directExecutor, mock(TaskScheduler.class));
        AgentConversation running = new AgentConversation();
        running.setId(7L);
        running.setUserId(42L);
        running.setStatus("running");
        running.setRunVersion(1L);
        when(service.create(42L, "你好智能体")).thenReturn(running);
        when(service.get(7L, 42L)).thenReturn(running);
        when(service.listMessages(7L, 42L)).thenReturn(List.of());
        UserContext.setUserId(42L);
        AgentConversationMessageRequest request = new AgentConversationMessageRequest();
        request.setContent("你好智能体");

        AgentConversationVO body = controller.create(request).getData();

        assertThat(body.getConversation()).isSameAs(running);
        verify(service).executeClaimedTurn(eq(7L), eq(42L), eq(1L), any());
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
                service, eventService, directExecutor, directExecutor, scheduler);
        AgentConversation claimed = new AgentConversation();
        claimed.setId(7L);
        claimed.setUserId(42L);
        claimed.setStatus("running");
        claimed.setRunVersion(9L);
        when(service.prepareTurn(7L, 42L, "继续")).thenReturn(claimed);
        UserContext.setUserId(42L);
        AgentConversationMessageRequest request = new AgentConversationMessageRequest();
        request.setContent("继续");

        SseEmitter emitter = controller.sendMessage(7L, request, new MockHttpServletResponse());

        assertThat(emitter).isNotNull();
        verify(service).executeClaimedTurn(eq(7L), eq(42L), eq(9L), any());
        verify(heartbeat).cancel(false);
    }
}

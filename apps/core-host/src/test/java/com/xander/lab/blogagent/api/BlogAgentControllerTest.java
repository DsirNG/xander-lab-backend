package com.xander.lab.blogagent.api;

import com.xander.lab.blogagent.application.BlogAgentEventService;
import com.xander.lab.blogagent.application.BlogAgentService;
import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.auth.core.UserContext;
import com.xander.lab.core.stream.CoreHostStreamProperties;
import com.xander.lab.core.stream.CoreLegacyStreamDrainRegistry;
import com.xander.lab.core.stream.CoreLegacyStreamState;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class BlogAgentControllerTest {
    private static final String TASK_QUEUE_BUSY_MESSAGE = "博客生成助手执行队列繁忙，请稍后重试";
    private static final String SUBSCRIPTION_QUEUE_BUSY_MESSAGE = "博客生成助手事件订阅繁忙，请稍后重试";

    @Test
    void drainRejectsNewLegacyBlogAgentStreamsBeforeTouchingTheTaskService() {
        CoreHostStreamProperties properties = new CoreHostStreamProperties();
        properties.setDrainEnabled(true);
        BlogAgentController controller = new BlogAgentController(
                null, null, null, null, null, properties, new CoreLegacyStreamState());
        UserContext.setUserId(42L);

        assertThatThrownBy(() -> controller.runStream(7L, mock(HttpServletResponse.class)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void closedSubscriberDoesNotPropagateIntoTaskExecution() {
        BlogAgentController controller = new BlogAgentController(null, null, null, null, null);
        SseEmitter emitter = new SseEmitter(0L);
        BlogAgentController.SseSubscriber subscriber = new BlogAgentController.SseSubscriber(
                new ConnectionRegistry(), 7L, 42L, emitter);
        emitter.complete();

        assertThatCode(() -> controller.send(subscriber, "delta", "content"))
                .doesNotThrowAnyException();
        assertThat(subscriber.isOpen()).isFalse();
    }

    @Test
    void legacyDrainSendsMigrationSignalBeforeCompletingSse() throws Exception {
        ConnectionRegistry connectionRegistry = new ConnectionRegistry();
        CoreLegacyStreamDrainRegistry drainRegistry = new CoreLegacyStreamDrainRegistry();
        CoreLegacyStreamState streamState = new CoreLegacyStreamState();
        streamState.openedLegacyAgentSse();
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = mock(SseEmitter.class);
        BlogAgentController.SseSubscriber subscriber = new BlogAgentController.SseSubscriber(
                connectionRegistry, drainRegistry, streamState, 7L, 42L, emitter);
        drainRegistry.register(subscriber);

        subscriber.close(ConnectionRegistry.REASON_STREAM_MIGRATION);

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        assertThat(streamState.legacyAgentSseActive()).isZero();
        assertThat(drainRegistry.activeCount()).isZero();
    }

    @Test
    void heartbeatStartsAfterOneFullInterval() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
        doReturn(scheduledFuture).when(scheduler).scheduleAtFixedRate(
                any(Runnable.class), any(Instant.class), eq(Duration.ofSeconds(15)));
        BlogAgentController controller = new BlogAgentController(
                null, null, null, null, scheduler);
        BlogAgentController.SseSubscriber subscriber = new BlogAgentController.SseSubscriber(
                new ConnectionRegistry(), 7L, 42L, new SseEmitter(0L));
        Instant beforeScheduling = Instant.now();

        ScheduledFuture<?> result = controller.scheduleHeartbeat(subscriber);

        ArgumentCaptor<Instant> startTime = ArgumentCaptor.forClass(Instant.class);
        verify(scheduler).scheduleAtFixedRate(
                any(Runnable.class), startTime.capture(), eq(Duration.ofSeconds(15)));
        assertSame(scheduledFuture, result);
        assertThat(startTime.getValue()).isAfterOrEqualTo(beforeScheduling.plusSeconds(14));
    }

    @Test
    void runStreamPersistsFailureAndCompletesEmitterWhenTaskExecutorRejects() {
        BlogAgentService service = mock(BlogAgentService.class);
        BlogAgentEventService eventService = mock(BlogAgentEventService.class);
        BlogAgentEventService.EventSession events = mock(BlogAgentEventService.EventSession.class);
        TaskExecutor taskExecutor = rejectingExecutor();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> heartbeat = scheduledHeartbeat(scheduler);
        SseEmitter emitter = spy(new SseEmitter(0L));
        BlogAgentController controller = controllerWithEmitter(
                service, eventService, taskExecutor, null, scheduler, emitter);
        doReturn(events).when(eventService).openSession(eq(7L), any());
        UserContext.setUserId(42L);

        SseEmitter result = controller.runStream(7L, mock(HttpServletResponse.class));

        assertSame(emitter, result);
        verify(service).failQueuedInitialRun(7L, 42L, TASK_QUEUE_BUSY_MESSAGE);
        verify(service, never()).runStream(
                any(), any(), org.mockito.ArgumentMatchers.<BiConsumer<String, String>>any());
        verify(events).accept("error", TASK_QUEUE_BUSY_MESSAGE);
        verify(events).close();
        verify(heartbeat).cancel(false);
        verify(emitter).complete();
    }

    @Test
    void reviseStreamPersistsFailureAndCompletesEmitterWhenTaskExecutorRejects() {
        BlogAgentService service = mock(BlogAgentService.class);
        BlogAgentEventService eventService = mock(BlogAgentEventService.class);
        BlogAgentEventService.EventSession events = mock(BlogAgentEventService.EventSession.class);
        TaskExecutor taskExecutor = rejectingExecutor();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> heartbeat = scheduledHeartbeat(scheduler);
        SseEmitter emitter = spy(new SseEmitter(0L));
        BlogAgentController controller = controllerWithEmitter(
                service, eventService, taskExecutor, null, scheduler, emitter);
        doReturn(events).when(eventService).openSession(eq(7L), any());
        BlogAgentMessageRequest request = new BlogAgentMessageRequest();
        request.setContent("补充并发控制章节");
        UserContext.setUserId(42L);

        SseEmitter result = controller.reviseStream(
                7L, request, mock(HttpServletResponse.class));

        assertSame(emitter, result);
        verify(service).failQueuedRevision(7L, 42L, TASK_QUEUE_BUSY_MESSAGE);
        verify(service, never()).reviseStream(
                any(), any(), any(), org.mockito.ArgumentMatchers.<BiConsumer<String, String>>any());
        verify(events).accept("error", TASK_QUEUE_BUSY_MESSAGE);
        verify(events).close();
        verify(heartbeat).cancel(false);
        verify(emitter).complete();
    }

    @Test
    void runStreamCompensatesWhenHeartbeatSchedulerRejects() {
        BlogAgentService service = mock(BlogAgentService.class);
        BlogAgentEventService eventService = mock(BlogAgentEventService.class);
        BlogAgentEventService.EventSession events = mock(BlogAgentEventService.EventSession.class);
        TaskExecutor taskExecutor = mock(TaskExecutor.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        doThrow(new RejectedExecutionException("heartbeat queue full"))
                .when(scheduler).scheduleAtFixedRate(
                        any(Runnable.class), any(Instant.class), eq(Duration.ofSeconds(15)));
        SseEmitter emitter = spy(new SseEmitter(0L));
        BlogAgentController controller = controllerWithEmitter(
                service, eventService, taskExecutor, null, scheduler, emitter);
        doReturn(events).when(eventService).openSession(eq(7L), any());
        UserContext.setUserId(42L);

        SseEmitter result = controller.runStream(7L, mock(HttpServletResponse.class));

        assertSame(emitter, result);
        verify(service).failQueuedInitialRun(7L, 42L, TASK_QUEUE_BUSY_MESSAGE);
        verify(taskExecutor, never()).execute(any());
        verify(events).accept("error", TASK_QUEUE_BUSY_MESSAGE);
        verify(events).close();
        verify(emitter).complete();
    }

    @Test
    void subscribeReturnsServiceUnavailableAndClosesEmitterWhenExecutorRejects() {
        BlogAgentService service = mock(BlogAgentService.class);
        TaskExecutor sseExecutor = rejectingExecutor();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> heartbeat = scheduledHeartbeat(scheduler);
        SseEmitter emitter = spy(new SseEmitter(0L));
        BlogAgentController controller = controllerWithEmitter(
                service, mock(BlogAgentEventService.class), null, sseExecutor, scheduler, emitter);
        UserContext.setUserId(42L);

        assertThatThrownBy(() -> controller.subscribe(
                7L, null, null, mock(HttpServletResponse.class)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException response = (ResponseStatusException) exception;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(response.getReason()).isEqualTo(SUBSCRIPTION_QUEUE_BUSY_MESSAGE);
                });

        verify(service).get(7L, 42L);
        verify(heartbeat).cancel(false);
        verify(emitter).complete();
    }

    @Test
    void subscribeReturnsServiceUnavailableAndCompletesEmitterWhenHeartbeatSchedulerRejects() {
        BlogAgentService service = mock(BlogAgentService.class);
        TaskExecutor sseExecutor = mock(TaskExecutor.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        doThrow(new RejectedExecutionException("heartbeat queue full"))
                .when(scheduler).scheduleAtFixedRate(
                        any(Runnable.class), any(Instant.class), eq(Duration.ofSeconds(15)));
        SseEmitter emitter = spy(new SseEmitter(0L));
        BlogAgentController controller = controllerWithEmitter(
                service, mock(BlogAgentEventService.class), null, sseExecutor, scheduler, emitter);
        UserContext.setUserId(42L);

        assertThatThrownBy(() -> controller.subscribe(
                7L, null, null, mock(HttpServletResponse.class)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        verify(service).get(7L, 42L);
        verify(sseExecutor, never()).execute(any());
        verify(emitter).complete();
    }

    private TaskExecutor rejectingExecutor() {
        TaskExecutor executor = mock(TaskExecutor.class);
        doThrow(new RejectedExecutionException("queue full"))
                .when(executor).execute(any(Runnable.class));
        return executor;
    }

    private ScheduledFuture<?> scheduledHeartbeat(TaskScheduler scheduler) {
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        doReturn(heartbeat).when(scheduler).scheduleAtFixedRate(
                any(Runnable.class), any(Instant.class), eq(Duration.ofSeconds(15)));
        return heartbeat;
    }

    private BlogAgentController controllerWithEmitter(BlogAgentService service,
                                                      BlogAgentEventService eventService,
                                                      TaskExecutor taskExecutor,
                                                      TaskExecutor sseExecutor,
                                                      TaskScheduler scheduler,
                                                      SseEmitter emitter) {
        BlogAgentController controller = spy(new BlogAgentController(
                service, eventService, taskExecutor, sseExecutor, scheduler));
        doReturn(emitter).when(controller).createEmitter(eq(7L), any(HttpServletResponse.class));
        return controller;
    }
}

package com.xander.lab.controller;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BlogAgentControllerTest {

    @Test
    void closedSubscriberDoesNotPropagateIntoTaskExecution() {
        BlogAgentController controller = new BlogAgentController(null, null, null, null, null);
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean subscriberOpen = new AtomicBoolean(true);
        emitter.complete();

        assertThatCode(() -> controller.send(7L, emitter, subscriberOpen, "delta", "content"))
                .doesNotThrowAnyException();
        assertThat(subscriberOpen).isFalse();
    }

    @Test
    void heartbeatStartsAfterOneFullInterval() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
        doReturn(scheduledFuture).when(scheduler).scheduleAtFixedRate(
                any(Runnable.class), any(Instant.class), eq(Duration.ofSeconds(15)));
        BlogAgentController controller = new BlogAgentController(
                null, null, null, null, scheduler);
        Instant beforeScheduling = Instant.now();

        ScheduledFuture<?> result = controller.scheduleHeartbeat(
                7L, new SseEmitter(0L), new AtomicBoolean(true));

        ArgumentCaptor<Instant> startTime = ArgumentCaptor.forClass(Instant.class);
        verify(scheduler).scheduleAtFixedRate(
                any(Runnable.class), startTime.capture(), eq(Duration.ofSeconds(15)));
        assertSame(scheduledFuture, result);
        assertThat(startTime.getValue()).isAfterOrEqualTo(beforeScheduling.plusSeconds(14));
    }
}

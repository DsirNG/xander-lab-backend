package com.xander.lab.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
}

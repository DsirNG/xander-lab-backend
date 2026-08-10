package com.xander.lab.automation.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** The notification stream is an in-memory convenience over the durable
 * notification table: offline users and closed connectors must never throw. */
class BlogPlanSseServiceTest {

    private final BlogPlanSseService service = new BlogPlanSseService(new ObjectMapper());

    @Test
    void pushToUserWithNoSubscriptionIsHarmless() {
        assertThatCode(() -> service.push(999L, "REVIEW_REJECTED", "t", "m", 1L, 2L))
                .doesNotThrowAnyException();
    }

    @Test
    void subscribeReturnsEmitterAndPushAfterCompletionNeverThrows() {
        SseEmitter emitter = service.subscribe(7L);
        assertThat(emitter).isNotNull();
        emitter.complete();
        assertThatCode(() -> service.push(7L, "RUN_FAILED", "t", "m", 1L, 2L))
                .doesNotThrowAnyException();
    }
}
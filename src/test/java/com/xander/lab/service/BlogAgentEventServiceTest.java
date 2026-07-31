package com.xander.lab.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.entity.BlogAgentEvent;
import com.xander.lab.mapper.BlogAgentEventMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class BlogAgentEventServiceTest {

    @Test
    void batchesDeltasBeforePersistingOrderedEvents() {
        BlogAgentEventMapper mapper = mock(BlogAgentEventMapper.class);
        AtomicLong ids = new AtomicLong(10);
        doAnswer(invocation -> {
            BlogAgentEvent event = invocation.getArgument(0);
            event.setId(ids.incrementAndGet());
            return 1;
        }).when(mapper).insert(any(BlogAgentEvent.class));
        BlogAgentEventService service = new BlogAgentEventService(mapper, new ObjectMapper());
        List<BlogAgentEvent> delivered = new ArrayList<>();

        try (BlogAgentEventService.EventSession session = service.openSession(7L, delivered::add)) {
            session.accept("delta", "hello ");
            session.accept("delta", "world");
            session.accept("stage", "review|正在检查文章");
            session.accept("complete", Map.of("status", "ready"));
        }

        assertThat(delivered).extracting(BlogAgentEvent::getEventType)
                .containsExactly("delta", "stage", "complete");
        assertThat(delivered.get(0).getDataContent()).isEqualTo("hello world");
        assertThat(delivered.get(0).getId()).isEqualTo(11L);
        assertThat(delivered.get(2).getDataContent()).isEqualTo("{\"status\":\"ready\"}");
    }

    @Test
    void closeFlushesRemainingDelta() {
        BlogAgentEventMapper mapper = mock(BlogAgentEventMapper.class);
        BlogAgentEventService service = new BlogAgentEventService(mapper, new ObjectMapper());
        List<BlogAgentEvent> delivered = new ArrayList<>();

        try (BlogAgentEventService.EventSession session = service.openSession(7L, delivered::add)) {
            session.accept("delta", "tail");
        }

        assertThat(delivered).singleElement().satisfies(event -> {
            assertThat(event.getEventType()).isEqualTo("delta");
            assertThat(event.getDataContent()).isEqualTo("tail");
        });
    }
}

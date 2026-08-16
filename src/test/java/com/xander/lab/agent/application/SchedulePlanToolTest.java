package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.automation.api.BlogContentPlanCreateRequest;
import com.xander.lab.automation.api.BlogContentPlanVO;
import com.xander.lab.automation.application.BlogContentPlanService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchedulePlanToolTest {

    @Test
    void createsPlanAndReportsNextRun() {
        BlogContentPlanService service = mock(BlogContentPlanService.class);
        BlogContentPlanVO plan = BlogContentPlanVO.builder()
                .id(11L)
                .topic("前端周报")
                .triggerTimes(List.of("09:00", "20:00"))
                .nextRunAt(Instant.parse("2026-08-17T09:00:00Z"))
                .status("ACTIVE")
                .build();
        when(service.create(eq(42L), any())).thenAnswer(invocation -> {
            BlogContentPlanCreateRequest request = invocation.getArgument(1);
            assertThat(request.getTopic()).isEqualTo("前端周报");
            assertThat(request.getTriggerTimes()).containsExactly("09:00", "20:00");
            assertThat(request.getTimezone()).isEqualTo("Asia/Shanghai");
            return plan;
        });

        SchedulePlanTool tool = new SchedulePlanTool(service);
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode args = factory.objectNode()
                .put("topic", "前端周报")
                .put("syncCsdn", true);
        args.set("triggerTimes", factory.arrayNode().add("09:00").add("20:00"));
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("planId").asLong()).isEqualTo(11L);
        assertThat(result.path("triggerTimes")).hasSize(2);
        assertThat(result.path("status").asText()).isEqualTo("ACTIVE");
        verify(service).create(eq(42L), any());
    }

    @Test
    void rejectsMissingTopic() {
        BlogContentPlanService service = mock(BlogContentPlanService.class);
        SchedulePlanTool tool = new SchedulePlanTool(service);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("triggerTime", "09:00");
        assertThatThrownBy(() -> tool.execute(42L, args, new RecordingProgress()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topic");
    }

    private static final class RecordingProgress implements AgentTool.ProgressListener {
        @Override
        public void onProgress(String message) {
        }

        @Override
        public void onToolDelta(String delta) {
        }
    }
}
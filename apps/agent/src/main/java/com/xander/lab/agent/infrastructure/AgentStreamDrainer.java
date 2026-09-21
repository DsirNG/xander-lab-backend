package com.xander.lab.agent.infrastructure;

import com.xander.lab.common.security.ConnectionRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** Executes the Wave 2 deadline for Agent SSE/WS connections on this instance. */
@Component
public class AgentStreamDrainer {

    private final AgentStreamProperties properties;
    private final ConnectionRegistry connectionRegistry;
    private final Counter forcedCloseRate;
    private final AtomicBoolean deadlineApplied = new AtomicBoolean();

    public AgentStreamDrainer(AgentStreamProperties properties,
                              ConnectionRegistry connectionRegistry,
                              MeterRegistry meterRegistry) {
        this.properties = properties;
        this.connectionRegistry = connectionRegistry;
        Tags tags = Tags.of("instance", instanceLabel(properties.getInstanceId()));
        this.forcedCloseRate = Counter.builder("drain_connection_close_rate")
                .description("Wave 2 Phase 3 force-closed Agent connections")
                .tags(tags)
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${agent.stream.drain-check-interval-ms:1000}")
    public void reconcileDeadline() {
        if (!properties.isDrainEnabled()) {
            deadlineApplied.set(false);
            return;
        }
        long deadline = properties.getDrainDeadlineAt();
        if (deadline <= 0L || System.currentTimeMillis() < deadline
                || !deadlineApplied.compareAndSet(false, true)) {
            return;
        }
        int closed = connectionRegistry.closeAll(ConnectionRegistry.REASON_STREAM_MIGRATION);
        if (closed > 0) forcedCloseRate.increment(closed);
    }

    private String instanceLabel(String instanceId) {
        return instanceId == null || instanceId.isBlank() ? "unknown" : instanceId;
    }
}

package com.xander.lab.automation.host;

import com.xander.lab.common.security.ConnectionRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** Executes the Wave 2 deadline for content-owned SSE connections on this instance. */
@Component
public class ContentAutomationStreamDrainer {

    private final ContentAutomationProperties properties;
    private final ConnectionRegistry connectionRegistry;
    private final Counter forcedCloseRate;
    private final AtomicBoolean deadlineApplied = new AtomicBoolean();

    public ContentAutomationStreamDrainer(ContentAutomationProperties properties,
                                          ConnectionRegistry connectionRegistry,
                                          MeterRegistry meterRegistry) {
        this.properties = properties;
        this.connectionRegistry = connectionRegistry;
        Tags tags = Tags.of("instance", instanceLabel(properties.getStreamInstanceId()));
        this.forcedCloseRate = Counter.builder("drain_connection_close_rate")
                .description("Wave 2 Phase 3 force-closed content connections")
                .tags(tags)
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${content-automation.stream.drain-check-interval-ms:1000}")
    public void reconcileDeadline() {
        if (!properties.isStreamDrainEnabled()) {
            deadlineApplied.set(false);
            return;
        }
        long deadline = properties.getStreamDrainDeadlineAt();
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

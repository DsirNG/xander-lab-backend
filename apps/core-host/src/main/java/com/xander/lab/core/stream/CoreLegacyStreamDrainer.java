package com.xander.lab.core.stream;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** Executes the V9 Phase 3 deadline for Core-hosted legacy Agent/content SSE streams. */
@Component
public class CoreLegacyStreamDrainer {

    private final CoreHostStreamProperties properties;
    private final CoreLegacyStreamDrainRegistry registry;
    private final Counter forcedCloseRate;
    private final AtomicBoolean deadlineApplied = new AtomicBoolean();

    public CoreLegacyStreamDrainer(CoreHostStreamProperties properties,
                                   CoreLegacyStreamDrainRegistry registry,
                                   MeterRegistry meterRegistry) {
        this.properties = properties;
        this.registry = registry;
        Tags tags = Tags.of("instance", instanceLabel(properties.getInstanceId()));
        this.forcedCloseRate = Counter.builder("drain_connection_close_rate")
                .description("Wave 2 Phase 3 deadline force-closed legacy connections")
                .tags(tags)
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${core.stream.drain-check-interval-ms:1000}")
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
        int closed = registry.drainAll();
        if (closed > 0) forcedCloseRate.increment(closed);
    }

    private String instanceLabel(String instanceId) {
        return instanceId == null || instanceId.isBlank() ? "unknown" : instanceId;
    }
}

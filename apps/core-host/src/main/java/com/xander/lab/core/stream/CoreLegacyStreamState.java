package com.xander.lab.core.stream;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/** Per-instance counts for the Core-hosted streams that are being drained in Wave 2. */
@Component
public class CoreLegacyStreamState {

    private final AtomicInteger legacyAgentSse = new AtomicInteger();
    private final AtomicInteger legacyContentSse = new AtomicInteger();

    public void openedLegacyAgentSse() {
        legacyAgentSse.incrementAndGet();
    }

    public void closedLegacyAgentSse() {
        legacyAgentSse.updateAndGet(value -> Math.max(0, value - 1));
    }

    public void openedLegacyContentSse() {
        legacyContentSse.incrementAndGet();
    }

    public void closedLegacyContentSse() {
        legacyContentSse.updateAndGet(value -> Math.max(0, value - 1));
    }

    public int legacyAgentSseActive() {
        return legacyAgentSse.get();
    }

    public int legacyContentSseActive() {
        return legacyContentSse.get();
    }
}

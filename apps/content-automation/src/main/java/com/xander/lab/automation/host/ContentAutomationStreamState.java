package com.xander.lab.automation.host;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/** Per-instance stream state used by the Wave 2 drain and metrics checks. */
@Component
public class ContentAutomationStreamState {

    private final AtomicInteger activeSse = new AtomicInteger();

    public void opened() {
        activeSse.incrementAndGet();
    }

    public void closed() {
        activeSse.updateAndGet(value -> Math.max(0, value - 1));
    }

    public int activeSse() {
        return activeSse.get();
    }
}

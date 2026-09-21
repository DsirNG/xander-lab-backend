package com.xander.lab.automation.host;

import com.xander.lab.common.security.ConnectionRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContentAutomationStreamMetricsTest {

    @Test
    void registersActiveStreamMetricsWithInstanceLabel() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ContentAutomationProperties properties = new ContentAutomationProperties();
        properties.setStreamInstanceId("content-a");

        new ContentAutomationStreamMetrics(meters, new ContentAutomationStreamState(), properties);

        assertThat(meters.get("content_automation_sse_active")
                .tag("instance", "content-a").gauge().getId().getTag("instance"))
                .isEqualTo("content-a");
    }
}

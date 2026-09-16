package com.xander.lab.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.application.AgentMemoryService;
import com.xander.lab.agent.application.AgentToolRegistry;
import com.xander.lab.agent.application.RememberTool;
import com.xander.lab.agent.infrastructure.AgentMessageMapper;
import com.xander.lab.publishing.application.BlogMediaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that the production constructor of {@link RememberTool} is selected by Spring. */
@SpringBootTest(classes = {
        AgentToolRegistry.class,
        RememberTool.class,
        RememberToolWiringTest.JsonConfiguration.class,
})
class RememberToolWiringTest {

    @MockBean
    private AgentMemoryService memoryService;
    @MockBean
    private AgentMessageMapper messageMapper;
    @MockBean
    private BlogMediaService mediaService;

    @Autowired
    private AgentToolRegistry registry;
    @Autowired
    private RememberTool tool;

    @Test
    void rememberToolUsesTheProductionConstructorAndRegisters() {
        assertThat(registry.get("remember")).isSameAs(tool);
    }

    @Configuration
    static class JsonConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}

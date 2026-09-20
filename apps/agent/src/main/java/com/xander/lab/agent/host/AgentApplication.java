package com.xander.lab.agent.host;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Wave 2 Agent host bootstrap.
 *
 * <p>The host intentionally contains no business route yet. V9 §96 requires the
 * new service to be healthy before Gateway cutover, and a separate bootstrap gives
 * that deployment step a real process without creating a second Agent owner.</p>
 */
@SpringBootApplication
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}

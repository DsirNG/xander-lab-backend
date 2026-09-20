package com.xander.lab.migration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone Flyway job. Deploy this process before business services and never as part of
 * {@code dinqor-core-host}; sharing a database does not mean sharing schema ownership.
 */
@SpringBootApplication
public class MigrationRunnerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MigrationRunnerApplication.class, args);
    }
}

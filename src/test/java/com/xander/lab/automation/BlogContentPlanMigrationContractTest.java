package com.xander.lab.automation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the fresh-install and upgrade paths for agent-created content-plan idempotency. */
class BlogContentPlanMigrationContractTest {

    @Test
    void planCreateIdempotencyColumnAndUniqueKeyExistInBothSchemaPaths() throws IOException {
        String baseline = resource("db/blog_content_plan_schema.sql");
        String upgrade = resource("db/20260903_blog_content_plan_invocation_idempotency_migration.sql");

        assertThat(baseline)
                .contains("`client_request_id` VARCHAR(64) NULL COMMENT")
                .contains("UNIQUE KEY `uk_blog_plan_user_request` (`user_id`, `client_request_id`)");
        assertThat(upgrade)
                .contains("ALTER TABLE `blog_content_plan`")
                .contains("ADD COLUMN `client_request_id` VARCHAR(64) NULL COMMENT")
                .contains("ADD UNIQUE KEY `uk_blog_plan_user_request` (`user_id`, `client_request_id`)");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

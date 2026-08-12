package com.xander.lab.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the fresh-install/upgrade compatibility of the agent schema scripts. */
class AgentMigrationContractTest {

    @Test
    void cancelRequestUpgradeIsSafeAfterCurrentFreshBaseline() throws IOException {
        String baseline = resource("db/agent_conversation_migration.sql");
        String upgrade = resource("db/agent_cancel_request_migration.sql");

        assertThat(baseline)
                .contains("`migration_key` VARCHAR(128) NOT NULL COMMENT '迁移标识键'")
                .contains("COMMENT='数据库迁移执行标记'")
                .contains("CREATE TABLE IF NOT EXISTS `agent_conversation`")
                .contains("`cancel_requested` TINYINT NOT NULL DEFAULT 0");
        assertThat(upgrade)
                .contains("information_schema.COLUMNS")
                .contains("COLUMN_NAME = 'cancel_requested'")
                .contains("PREPARE stmt_agent_cancel_request")
                .contains("20260812_agent_cancel_request");
    }

    @Test
    void runVersionUpgradeKeepsFreshAndExistingDatabasesAligned() throws IOException {
        String baseline = resource("db/agent_conversation_migration.sql");
        String upgrade = resource("db/agent_run_version_migration.sql");

        assertThat(baseline)
                .contains("`run_version` BIGINT NOT NULL DEFAULT 0")
                .contains("idx_agent_event_conversation_run_id");
        assertThat(upgrade)
                .contains("TABLE_NAME = 'agent_conversation'")
                .contains("TABLE_NAME = 'agent_event'")
                .contains("COLUMN_NAME = 'run_version'")
                .contains("PREPARE stmt_agent_run_version")
                .contains("PREPARE stmt_agent_event_run_version")
                .contains("idx_agent_event_conversation_run_id")
                .contains("PREPARE stmt_agent_event_run_index")
                .contains("PREPARE stmt_agent_event_type_comment")
                .contains("tool_progress/tool_delta/tool_end/tool_error/answer_delta")
                .contains("stmt_agent_close_legacy_run")
                .contains("WHERE `status` = ''running''")
                .contains("AND `run_version` = 0")
                .contains("20260812_agent_run_version");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

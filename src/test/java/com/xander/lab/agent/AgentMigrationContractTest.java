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

    @Test
    void attachmentUpgradeKeepsMessageBaselineAligned() throws IOException {
        String baseline = resource("db/agent_conversation_migration.sql");
        String upgrade = resource("db/20260824_agent_message_attachment_migration.sql");

        assertThat(baseline).contains("`attachments_json` LONGTEXT NULL COMMENT");
        assertThat(upgrade)
                .contains("information_schema.COLUMNS")
                .contains("COLUMN_NAME = 'attachments_json'")
                .contains("20260824_agent_message_attachment");
    }

    @Test
    void autonomyUpgradeMatchesTheFreshBaselineForPlanAndMemory() throws IOException {
        String baseline = resource("db/agent_conversation_migration.sql");
        String upgrade = resource("db/20260825_agent_autonomy_migration.sql");

        // 新装走 baseline、老库走 upgrade，两条路径必须落到同一套列与表上。
        assertThat(baseline)
                .contains("`plan_json` LONGTEXT NULL COMMENT")
                .contains("CREATE TABLE IF NOT EXISTS `agent_memory`")
                .contains("UNIQUE KEY `uk_agent_memory_user_key` (`user_id`, `memory_key`)")
                .contains("thought/plan/reflection/tool_start")
                .contains("message/thought/reflection/tool_call/tool_result/answer");
        assertThat(upgrade)
                .contains("information_schema.COLUMNS")
                .contains("COLUMN_NAME = 'plan_json'")
                .contains("PREPARE stmt_agent_plan_column")
                .contains("PREPARE stmt_agent_event_type_autonomy")
                .contains("PREPARE stmt_agent_message_kind_autonomy")
                .contains("thought/plan/reflection/tool_start")
                .contains("message/thought/reflection/tool_call/tool_result/answer")
                .contains("CREATE TABLE IF NOT EXISTS `agent_memory`")
                .contains("20260825_agent_autonomy");
    }

    @Test
    void quizCardUpgradeOnlyRestatesTheEnlargedKindAndEventVocabulary() throws IOException {
        String baseline = resource("db/agent_conversation_migration.sql");
        String upgrade = resource("db/20260831_agent_quiz_card_migration.sql");

        // 答题卡只是多了两种取值，两列都是无约束的 VARCHAR(24)：
        // 新装和老库都不需要改类型，但注释里的取值表必须一致，否则线上字典对不上代码。
        // 事件取值表后来还会继续加（artifact 就插在 quiz 之后），所以这里只钉住 quiz 自己那一段。
        assertThat(baseline)
                .contains("answer/quiz（下发的答题卡，正文为JSON）/quiz_answer")
                .contains("tool_error/quiz/");
        assertThat(upgrade)
                .contains("MODIFY COLUMN `kind` VARCHAR(24) NOT NULL")
                .contains("answer/quiz（下发的答题卡，正文为JSON）/quiz_answer")
                .contains("MODIFY COLUMN `event_type` VARCHAR(24) NOT NULL")
                .contains("tool_error/quiz/answer_delta")
                .contains("20260831_agent_quiz_card");
    }

    @Test
    void artifactCardUpgradeOnlyRestatesTheEnlargedKindAndEventVocabulary() throws IOException {
        String baseline = resource("db/agent_conversation_migration.sql");
        String upgrade = resource("db/20260902_agent_artifact_card_migration.sql");

        // 代码交付卡同理：kind 和 event_type 都只是多一种取值，列类型不动，
        // 但新装（baseline）和老库（upgrade）的注释必须同时写上 artifact，不然两条路径的字典会分叉。
        assertThat(baseline)
                .contains("quiz_answer（用户在答题卡上的作答）/artifact（下发的代码交付卡，正文为JSON）")
                .contains("tool_error/quiz/artifact/answer_delta");
        assertThat(upgrade)
                .contains("MODIFY COLUMN `kind` VARCHAR(24) NOT NULL")
                .contains("quiz_answer（用户在答题卡上的作答）/artifact（下发的代码交付卡，正文为JSON）")
                .contains("MODIFY COLUMN `event_type` VARCHAR(24) NOT NULL")
                .contains("tool_error/quiz/artifact/answer_delta")
                .contains("20260902_agent_artifact_card");
    }

    @Test
    void harnessJournalBaselineAndUpgradeContainTheSameDurableExecutionTables() throws IOException {
        String baseline = resource("db/agent_harness_schema.sql");
        String upgrade = resource("db/20260903_agent_harness_journal_migration.sql");

        for (String table : java.util.List.of("agent_run", "agent_step", "agent_tool_invocation")) {
            assertThat(baseline).contains("CREATE TABLE IF NOT EXISTS `" + table + "`");
            assertThat(upgrade).contains("CREATE TABLE IF NOT EXISTS `" + table + "`");
        }
        assertThat(baseline)
                .contains("UNIQUE KEY `uk_agent_run_conversation_version`")
                .contains("UNIQUE KEY `uk_agent_step_run_number`")
                .contains("UNIQUE KEY `uk_agent_tool_invocation_id`")
                .contains("invocation_id` CHAR(36) NOT NULL COMMENT");
        assertThat(upgrade)
                .contains("20260903_agent_harness_journal")
                .doesNotContain("SOURCE agent_harness_schema.sql");
    }

    @Test
    void interruptedInvocationUpgradeDocumentsTheReconciliationState() throws IOException {
        String baseline = resource("db/agent_harness_schema.sql");
        String upgrade = resource("db/20260903_agent_harness_invocation_unknown_migration.sql");

        assertThat(baseline).contains("UNKNOWN进程中断后需对账");
        assertThat(upgrade)
                .contains("ALTER TABLE `agent_tool_invocation`")
                .contains("UNKNOWN进程中断后需对账")
                .contains("20260903_agent_harness_invocation_unknown");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

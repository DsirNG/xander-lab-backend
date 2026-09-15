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
        // 同理不假设 quiz 紧跟 answer：baseline 里 plan 排在两者之间（计划卡片先于答题卡落地），
        // 那个邻接关系只属于 20260831 那一刻的 upgrade 文件。
        assertThat(baseline)
                .contains("quiz（下发的答题卡，正文为JSON）/quiz_answer（用户在答题卡上的作答）")
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
    void compactionCheckpointUpgradeOnlyRestatesTheEnlargedKindVocabulary() throws IOException {
        String baseline = resource("db/agent_conversation_migration.sql");
        String upgrade = resource("db/20260903_agent_context_compaction_migration.sql");

        // 检查点是一条普通 agent_message（role=user、kind=compaction），没有新表也没有新列：
        // 只有 kind 多一种取值，所以新装（baseline）与老库（upgrade）的注释必须同时写上它。
        // 它不推事件，agent_event 的取值表刻意不动——多写一种前端不认识的事件反而是噪音。
        assertThat(baseline)
                .contains("artifact（下发的代码交付卡，正文为JSON）/compaction（上下文压缩检查点")
                .doesNotContain("tool_error/quiz/artifact/compaction");
        assertThat(upgrade)
                .contains("MODIFY COLUMN `kind` VARCHAR(24) NOT NULL")
                .contains("artifact（下发的代码交付卡，正文为JSON）/compaction（上下文压缩检查点")
                .doesNotContain("ALTER TABLE `agent_event`")
                .contains("20260903_agent_context_compaction");
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

    @Test
    void approvalUpgradeAddsDurableDecisionRecordsAndPausedExecutionStates() throws IOException {
        String baseline = resource("db/agent_harness_schema.sql");
        String upgrade = resource("db/20260903_agent_harness_approval_migration.sql");

        assertThat(baseline)
                .contains("CREATE TABLE IF NOT EXISTS `agent_approval`")
                .contains("UNIQUE KEY `uk_agent_approval_invocation`")
                .contains("`request_fingerprint` CHAR(64) NOT NULL COMMENT");
        assertThat(upgrade)
                .contains("MODIFY COLUMN `status` VARCHAR(24) NOT NULL")
                .contains("WAITING_APPROVAL")
                .contains("CREATE TABLE IF NOT EXISTS `agent_approval`")
                .contains("approval_required/approval_approved/approval_rejected/approval_executed")
                .contains("20260903_agent_harness_approval");
    }

    @Test
    void blogGenerationInvocationKeyIsPresentForFreshAndExistingDatabases() throws IOException {
        String baseline = resource("db/blog_agent_schema.sql");
        String upgrade = resource("db/20260903_blog_agent_task_invocation_idempotency_migration.sql");

        assertThat(baseline)
                .contains("`client_request_id` VARCHAR(64) NULL COMMENT")
                .contains("UNIQUE KEY `uk_blog_agent_task_user_request`");
        assertThat(upgrade)
                .contains("ALTER TABLE `blog_agent_task`")
                .contains("ADD COLUMN `client_request_id` VARCHAR(64) NULL COMMENT")
                .contains("ADD UNIQUE KEY `uk_blog_agent_task_user_request`");
    }

    @Test
    void messageInvocationIdIsPresentForFreshAndExistingDatabases() throws IOException {
        String baseline = resource("db/agent_conversation_migration.sql");
        String upgrade = resource("db/20260914_agent_message_invocation_id_migration.sql");

        // 新装走 baseline、老库走 upgrade：两条路径都必须落下同一列，否则新库上
        // 前端拿不到配对键、老库上拿得到，同一个页面在两种部署上行为不同。
        assertThat(baseline)
                .contains("`invocation_id` VARCHAR(64) NULL COMMENT")
                .contains("关联agent_tool_invocation.invocation_id");
        assertThat(upgrade)
                .contains("information_schema.COLUMNS")
                .contains("COLUMN_NAME = 'invocation_id'")
                .contains("ADD COLUMN `invocation_id` VARCHAR(64) NULL COMMENT")
                .contains("AFTER `tool_name`")
                .contains("20260914_agent_message_invocation_id");
    }

    @Test
    void planMessageUpgradeMatchesTheFreshBaselineForItsColumnAndDictionary() throws IOException {
        String baseline = resource("db/agent_conversation_migration.sql");
        String upgrade = resource("db/20260901_agent_plan_message_migration.sql");
        String artifactWriter = resource("db/20260902_agent_artifact_card_migration.sql");
        String lastKindWriter = resource("db/20260903_agent_context_compaction_migration.sql");

        // 计划卡片不是新增一张卡，而是就地更新同一张：定位条件是
        // (conversation_id, run_version, kind)。所以新装 baseline 必须同时带上列与索引——
        // 少了列，insertForActiveRun 这条固定列名的 INSERT 在新库上直接失败；
        // 少了索引，upgrade 的守卫检查会在新库上真的执行一次 ALTER。
        assertThat(baseline)
                .contains("`run_version` BIGINT NULL COMMENT '创建该消息的会话执行代次")
                .contains("idx_agent_message_conversation_run_kind` (`conversation_id`, `run_version`, `kind`)");
        assertThat(upgrade)
                .contains("information_schema.COLUMNS")
                .contains("COLUMN_NAME = 'run_version'")
                .contains("ADD COLUMN `run_version` BIGINT NULL COMMENT")
                .contains("AFTER `conversation_id`")
                .contains("ADD KEY `idx_agent_message_conversation_run_kind`")
                .contains("20260901_agent_plan_message");

        // plan 是真实取值（AgentEventProjector 会写入并查询 kind=plan），kind 又无枚举约束，
        // 所以漏掉它不会有任何报错——只会让线上字典少一种取值，运维按注释排查时对不上代码。
        // 三处都要钉住：baseline（新装）、20260902（曾把 plan 从取值表里抹掉的那次重述）、
        // 20260903（kind 注释的最后一个写入者，它的文本才是升级后真正生效的那份）。
        for (String sql : java.util.List.of(baseline, artifactWriter, lastKindWriter)) {
            assertThat(sql).contains("plan（任务计划卡片，正文为JSON数组）");
        }
        assertThat(lastKindWriter).contains("20260903_agent_context_compaction");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

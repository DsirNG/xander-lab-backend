package com.xander.lab.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

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

    /**
     * 会话状态列必须放得下它注释里承诺的每一个取值。
     *
     * <p>回归：这一列长期是 {@code VARCHAR(16)}，而注释里承诺的 {@code awaiting_approval}
     * 有 **17** 个字符。MySQL 8 默认 {@code STRICT_TRANS_TABLES} 下这条写入直接报
     * 1406 Data too long；非严格模式下会被截成 {@code awaiting_approva}，于是之后所有
     * {@code WHERE status = 'awaiting_approval'} 的条件更新都命中 0 行——审批恢复静默失效，
     * 而列注释还在说这个取值是合法的。所以两边一起钉：注释承诺的取值、以及列宽。</p>
     */
    @Test
    void conversationStatusColumnFitsEveryValueItsCommentPromises() throws IOException {
        String baseline = resource("db/agent_conversation_migration.sql");
        String upgrade = resource("db/20260925_agent_run_status_vocabulary_migration.sql");
        String runBaseline = resource("db/agent_harness_schema.sql");

        assertThat(baseline)
                .contains("`status` VARCHAR(24) NOT NULL DEFAULT 'ready'")
                .contains("awaiting_approval")
                .contains("budget_exhausted");
        assertThat(upgrade)
                .contains("MODIFY COLUMN `status` VARCHAR(24) NOT NULL DEFAULT 'ready'")
                .contains("MODIFY COLUMN `status` VARCHAR(24) NOT NULL")
                .contains("interrupted")
                .contains("20260925");
        // 权威侧同步扩到九态，否则投影有值、权威列注释却还在说四态。
        assertThat(runBaseline)
                .contains("`status` VARCHAR(24) NOT NULL COMMENT '运行状态（权威，九态）")
                .contains("CANCELLED用户请求停止后收口");
    }

    /**
     * 预算三列在基线与升级脚本里必须一致，且都要带中文注释。
     *
     * <p>钉三件事：① 基线（新库）与迁移（老库）加的是同一组列——只改一边会让两套库
     * 在同一个代码路径上表现不同；② 每列都有中文 {@code COMMENT}；③ 迁移已登记到
     * {@code xander_schema_migration}，否则它在部署时会**被跳过**，而现象是
     * "代码在读一个不存在的列"。</p>
     *
     * <p>另外钉住三处被修正的误导注释：{@code intent_json}/{@code skills_json}/
     * {@code execution_snapshot_json} 的原注释写着"预留…第一阶段为空"，但实现里每轮都写它们。
     * 注释说"为空"而实现写值，比没有注释更危险。</p>
     */
    @Test
    void budgetColumnsExistInBothTheBaselineAndTheUpgradeScript() throws IOException {
        String baseline = resource("db/agent_harness_schema.sql");
        String upgrade = resource("db/20260925_agent_run_budget_migration.sql");

        for (String column : List.of("`budget_json`", "`active_duration_ms`", "`last_running_at`")) {
            assertThat(baseline).as("基线缺少 %s", column).contains(column);
            assertThat(upgrade).as("升级脚本缺少 %s", column).contains(column);
        }
        assertThat(upgrade)
                .contains("INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)")
                .contains("20260925_agent_run_budget")
                .contains("COMMENT '本轮冻结的资源上限快照")
                .contains("COMMENT '累计活跃时长(毫秒)");
        assertThat(baseline).contains("COMMENT '累计活跃时长(毫秒)");

        // 三处过期注释必须已经改掉：它们描述的"第一阶段为空"与实现相反。
        assertThat(baseline)
                .doesNotContain("预留的模型路由与预算快照；第一阶段为空")
                .doesNotContain("预留的激活技能与版本快照；第一阶段为空")
                .doesNotContain("预留的结构化意图判定快照；第一阶段为空");
    }

    /**
     * 意图周期两列在基线与升级脚本里必须一致。
     *
     * <p>{@code intent_epoch} 是"崩溃后重试"与"用户又说了新的一句话"的分界，也是幂等采纳的
     * 唯一判据。它必须**可空**：存量 Run 没有周期标识，凭猜测回填会把"这次重试属于哪次诉求"
     * 写错，而那个归属正是采纳判据——写错比留空更糟，留空至少能被读侧识别为"老数据"。
     * 所以这里同时钉住 {@code NULL} 与"迁移里不能出现回填 UPDATE"。</p>
     *
     * <p>{@code auto_resumed_at} 是"每个 Run 最多自动恢复一次"的落点，同样必须可空——
     * 非空表示名额已用掉。</p>
     */
    @Test
    void intentEpochColumnsExistInBothTheBaselineAndTheUpgradeScript() throws IOException {
        String baseline = resource("db/agent_harness_schema.sql");
        String upgrade = resource("db/20260925_agent_intent_epoch_migration.sql");

        for (String column : List.of("`intent_epoch`", "`auto_resumed_at`")) {
            assertThat(baseline).as("基线缺少 %s", column).contains(column);
            assertThat(upgrade).as("升级脚本缺少 %s", column).contains(column);
        }
        assertThat(upgrade)
                .contains("`intent_epoch` CHAR(36) NULL")
                .contains("`auto_resumed_at` DATETIME NULL")
                .contains("COMMENT '用户意图周期标识(UUID)")
                .contains("COMMENT '最近一次被调度器自动 resume 的时间")
                .contains("INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)")
                .contains("20260925_agent_intent_epoch");
        // 不回填：留空能被读侧识别为"迁移前的数据"，猜一个值则会让采纳判据出错。
        assertThat(upgrade.toUpperCase(java.util.Locale.ROOT))
                .as("周期列不得回填，否则会把老 Run 塞进一个凭猜测编出来的周期")
                .doesNotContain("UPDATE `AGENT_RUN` SET `INTENT_EPOCH`");
    }

    /**
     * 逻辑副作用身份（Effect）的表与列必须在新装与升级两条路径上一致。
     *
     * <p>钉四件事：① 新库（baseline）与老库（migration）落下**同一组列**，只改一边会让两套库在
     * 同一个代码路径上表现不同；② 采纳判据所依赖的索引存在，否则 {@code resolveEffect} 的查询会
     * 全表扫；③ {@code agent_tool_invocation.effect_id} 必须**可空**；④ 迁移已登记到
     * {@code xander_schema_migration}，否则部署时会被跳过，而现象是"代码在读一个不存在的列"。</p>
     *
     * <p>第 ③ 条单独说明：只读工具不建 Effect，存量 invocation 也没有 Effect，所以这一列**必须**
     * 允许 NULL。若它被写成 {@code NOT NULL}，那么每一次只读工具调用都会插入失败——而失败点
     * 在 {@code startInvocation} 里，表现为"工具一个都跑不起来"，不是"某些统计缺了值"。</p>
     */
    @Test
    void effectIdentityTableIsPresentForFreshAndExistingDatabases() throws IOException {
        String baseline = resource("db/agent_harness_schema.sql");
        String upgrade = resource("db/20260925_agent_effect_migration.sql");

        for (String column : List.of("`effect_id`", "`user_id`", "`conversation_id`", "`run_id`",
                "`intent_epoch`", "`tool_name`", "`args_fingerprint`", "`args_json`",
                "`created_step_no`", "`external_id`", "`status`", "`settled_at`")) {
            assertThat(baseline).as("基线缺少 %s", column).contains(column);
            assertThat(upgrade).as("升级脚本缺少 %s", column).contains(column);
        }

        for (String sql : List.of(baseline, upgrade)) {
            assertThat(sql)
                    .contains("CREATE TABLE IF NOT EXISTS `agent_effect`")
                    // 业务身份必须唯一，否则"同一个动作"可以有两条记录，采纳变成抛硬币。
                    .contains("UNIQUE KEY `uk_agent_effect_id` (`effect_id`)")
                    // 采纳查询的形状：(周期, 工具名, 状态)。缺了它每次重试都全表扫。
                    .contains("KEY `idx_agent_effect_adoption` (`intent_epoch`, `tool_name`, `status`)")
                    .contains("CONSTRAINT `fk_agent_effect_run`");
        }

        assertThat(upgrade)
                .contains("ALTER TABLE `agent_tool_invocation`")
                .contains("ADD COLUMN `effect_id` CHAR(36) NULL")
                .contains("AFTER `step_id`")
                .contains("ADD KEY `idx_agent_tool_invocation_effect` (`effect_id`)")
                .contains("INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)")
                .contains("20260925_agent_effect");

        // invocation 侧必须是可空：只读工具与存量行都没有 Effect。
        assertThat(baseline).contains("`effect_id` CHAR(36) NULL");
        assertThat(upgrade).contains("`effect_id` CHAR(36) NULL");
        assertThat(baseline).contains("KEY `idx_agent_tool_invocation_effect` (`effect_id`)");

        // 身份列是 UUID 文本，指纹列是 SHA-256 十六进制——宽度必须与代码里算出来的长度对齐，
        // 否则落库会被 MySQL 静默截断（非严格模式）或直接报 1406。
        for (String sql : List.of(baseline, upgrade)) {
            assertThat(sql).contains("`effect_id` CHAR(36) NOT NULL").contains("`args_fingerprint` CHAR(64) NOT NULL");
        }
    }

    /**
     * invocation 的副作用执行阶段三列，在新装与升级两条路径上必须一致。
     *
     * <p><b>这条测试的前身是一条"这三列不该存在"的断言</b>（B6 用它把批次的边界钉住，
     * 理由写在当时的 JavaDoc 里：静态身份与动态进度是两个问题，混在一批里会让它们在代码里
     * 也糊成一件）。B7 落地时它如预期地失败了——那正是它存在的意义：范围变更被明确说出来，
     * 而不是悄悄长进来。现在它反转成"这三列必须存在且两边一致"。</p>
     *
     * <p>三件事分开钉：① 两条路径落下同一组列；② `effect_state` 的**默认值与可空性**——
     * 它必须 {@code NOT NULL DEFAULT 'NONE'}`：存量行与只读调用都没有副作用阶段，
     * 允许 NULL 会让"按 effect_state 分流"的每一条 UPDATE 都要额外处理 NULL 分支，
     * 而漏掉 NULL 分支的表现是"这些行在崩溃收口时被完全跳过"；两个时间戳则必须**可空**
     * （未发生就是没有，用零值时间会与真实时间混淆）；③ 迁移已登记，否则部署时会被跳过，
     * 而现象是"代码在读一个不存在的列"。</p>
     *
     * <p>同时钉住**不回填**：存量 invocation 里既有"发出去了"也有"没发出去"，而区分它们所需的
     * 证据从来没被记录过；凭 status 猜会把已提交的写判成"没发出"，那正是这一批要消灭的漏判。</p>
     */
    @Test
    void effectStateColumnsExistInBothTheBaselineAndTheUpgradeScript() throws IOException {
        String baseline = resource("db/agent_harness_schema.sql");
        String upgrade = resource("db/20260925_agent_effect_state_migration.sql");

        for (String column : List.of("`effect_state`", "`effect_dispatched_at`", "`effect_confirmed_at`")) {
            assertThat(baseline).as("基线缺少 %s", column).contains(column);
            assertThat(upgrade).as("升级脚本缺少 %s", column).contains(column);
        }

        for (String sql : List.of(baseline, upgrade)) {
            assertThat(sql)
                    .contains("`effect_state` VARCHAR(16) NOT NULL DEFAULT 'NONE'")
                    .contains("`effect_dispatched_at` DATETIME NULL")
                    .contains("`effect_confirmed_at` DATETIME NULL")
                    // 四个取值必须写进注释：没有字典的 VARCHAR 一旦多一种取值，
                    // 不会有任何报错，只会让按注释排查的运维对不上代码。
                    .contains("NONE未进入副作用路径")
                    .contains("PREPARED意图已落库但请求尚未发出")
                    .contains("DISPATCHED请求已交给工具")
                    .contains("CONFIRMED已拿到远端成功凭据")
                    // NONE 不等于"没有 effect_id"：存量行可能两者都有。读侧若按
                    // "NONE 就是没发出"处理，B6→B7 部署窗口里的写入会被静默终结。
                    .contains("判读须连effect_id一起看");
        }

        assertThat(upgrade)
                .contains("ALTER TABLE `agent_tool_invocation`")
                .contains("INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)")
                .contains("20260925_agent_effect_state")
                // 标记顺序那条约束是这一批最要紧的注释：写反了会漏掉一次真实写入。
                .contains("必须写在把请求交给工具之前");
        assertThat(upgrade.toUpperCase(java.util.Locale.ROOT))
                .as("阶段列不得回填：凭 status 猜会把已提交的写判成没发出，而那正是要消灭的漏判")
                .doesNotContain("UPDATE `AGENT_TOOL_INVOCATION` SET `EFFECT_STATE`");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

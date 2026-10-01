package com.xander.lab.agent.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 状态机契约。
 *
 * <p>这些断言的作用不是"证明实现是对的"，而是把**词汇表本身**钉住：加一个状态、改一个投影、
 * 把一个终态改成可恢复，都必须同时改这个文件。于是改动会留下痕迹，而不是悄悄让某个前端分支
 * 落进 default——状态机的问题从来不是"算错了"，而是"多了一个没人处理的值"。</p>
 */
class AgentRunStatusContractTest {

    @Test
    void theRunVocabularyIsExactlyTheNineAgreedStates() {
        assertThat(AgentRun.allStatuses()).containsExactlyInAnyOrder(
                "CREATED", "RUNNING", "WAITING_APPROVAL", "PAUSED",
                "INTERRUPTED", "BUDGET_EXHAUSTED", "COMPLETED", "CANCELLED", "FAILED");
    }

    /** 三分类必须是划分：每个状态恰好属于一类，既不能重叠也不能漏。 */
    @Test
    void inProgressResumableAndTerminalPartitionTheVocabulary() {
        for (String status : AgentRun.allStatuses()) {
            int classes = (AgentRun.isInProgress(status) ? 1 : 0)
                    + (AgentRun.isResumable(status) ? 1 : 0)
                    + (AgentRun.isTerminal(status) ? 1 : 0);
            assertThat(classes).as("状态 %s 必须且只能属于一类", status).isEqualTo(1);
        }
    }

    /**
     * 终态的基本不变量：{@code terminal → 不再迁移}。
     *
     * <p>这条是 B3 修掉"可恢复终态"那个自造词的判据：Resume 要求
     * {@code INTERRUPTED → RUNNING}，而一个还能迁移的状态按定义就不是终态。</p>
     */
    @Test
    void terminalStatesAreNeverResumable() {
        for (String status : AgentRun.allStatuses()) {
            if (!AgentRun.isTerminal(status)) continue;
            assertThat(AgentRun.isResumable(status))
                    .as("终态 %s 不能被 resume，否则它就不是终态", status).isFalse();
            assertThat(AgentRun.isInProgress(status))
                    .as("终态 %s 不能同时是进行中", status).isFalse();
        }
    }

    @Test
    void resumeAcceptsExactlyTheFourResumableStates() {
        assertThat(AgentRun.allStatuses().stream().filter(AgentRun::isResumable))
                .containsExactlyInAnyOrder("WAITING_APPROVAL", "PAUSED", "INTERRUPTED", "BUDGET_EXHAUSTED");
    }

    /**
     * {@code resumableStatuses()} 是给拒绝消息用的，必须与 {@link AgentRun#isResumable} 同源。
     *
     * <p>两处各列一遍的代价不是多写几行，而是消息里少一个状态——"只有 X 可以继续"说了半句，
     * 而调用方照着它去排查会得出错误结论。顺序也必须跟 {@link AgentRun#allStatuses()} 一致：
     * 它出现在用户可见的文案里，顺序抖动会让同一句话每次不一样。</p>
     */
    @Test
    void theResumableListIsDerivedFromTheSameRuleAndKeepsLifecycleOrder() {
        assertThat(AgentRun.resumableStatuses())
                .containsExactlyElementsOf(
                        AgentRun.allStatuses().stream().filter(AgentRun::isResumable).toList());
    }

    @Test
    void onlyCreatedAndRunningCountAsInProgress() {
        assertThat(AgentRun.allStatuses().stream().filter(AgentRun::isInProgress))
                .containsExactlyInAnyOrder("CREATED", "RUNNING");
    }

    /** 每个 Run 状态都必须有投影，且投影值必须是前端契约里定义过的。 */
    @Test
    void everyRunStatusProjectsIntoTheFrontendVocabulary() {
        for (String status : AgentRun.allStatuses()) {
            assertThat(AgentRunStatusProjection.isKnown(AgentRunStatusProjection.of(status)))
                    .as("状态 %s 的投影必须在会话契约里", status).isTrue();
        }
    }

    /** 未定义的状态必须响亮失败：兜底成 ready 会让一个拼错的状态静默变成"可以继续"。 */
    @Test
    void anUnknownRunStatusFailsLoudlyInsteadOfFallingBack() {
        assertThatThrownBy(() -> AgentRunStatusProjection.of("DONE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DONE");
        assertThatThrownBy(() -> AgentRunStatusProjection.of(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 投影不是单射：做完了和我按了停止，对用户来说是同一个后续动作。 */
    @Test
    void completedAndCancelledShareTheReadyProjectionOnPurpose() {
        assertThat(AgentRunStatusProjection.of(AgentRun.STATUS_COMPLETED))
                .isEqualTo(AgentRunStatusProjection.READY);
        assertThat(AgentRunStatusProjection.of(AgentRun.STATUS_CANCELLED))
                .isEqualTo(AgentRunStatusProjection.READY);
    }

    /**
     * 进程消失与业务失败必须投影成不同的值。
     *
     * <p>两者都是 {@code failed} 时前端分不出"重试有用"和"重试也没用"——
     * 这正是 B3 要修的三处语义错误之一。</p>
     */
    @Test
    void interruptedAndFailedStayDistinguishableForTheFrontend() {
        assertThat(AgentRunStatusProjection.of(AgentRun.STATUS_INTERRUPTED))
                .isEqualTo(AgentRunStatusProjection.INTERRUPTED)
                .isNotEqualTo(AgentRunStatusProjection.FAILED);
    }

    /**
     * {@code claim()} 用拒绝名单：只有进行中与"有待决审批"不行。
     *
     * <p>关键是**所有可恢复态和终态都放行**——允许名单漏掉一个状态就会把用户锁在会话外面，
     * 而那是静默的。</p>
     */
    @Test
    void claimRejectsOnlyRunningAndAwaitingApproval() {
        assertThat(AgentRunStatusProjection.canClaim(AgentRunStatusProjection.RUNNING)).isFalse();
        assertThat(AgentRunStatusProjection.canClaim(AgentRunStatusProjection.AWAITING_APPROVAL)).isFalse();
        assertThat(AgentRunStatusProjection.allStatuses().stream()
                .filter(AgentRunStatusProjection::canClaim))
                .containsExactlyInAnyOrder("ready", "paused", "interrupted", "budget_exhausted", "failed");
    }

    /**
     * 可恢复态投影之后仍然可以被 claim：暂停/中断/超预算都不该锁住用户。
     *
     * <p>{@code WAITING_APPROVAL} 是这条规则的**唯一例外**，而且是有意的：它投影成
     * {@code awaiting_approval}，此刻正有一条 invocation 等着用户批准。允许 claim 就等于开一个新意图，
     * 那条待决审批连同它的 invocation 会永久悬空——没有任何扫描会收它（reconciler 只看 {@code UNKNOWN}）。
     * 所以它必须留在拒绝名单里，直到"被顶掉的待决审批"有了明确去向（B6/B7）。</p>
     *
     * <p>这条例外与 §3.4 迁移表里 {@code WAITING_APPROVAL × claim} 的 ✅ 不一致，
     * 是**实现层对设计表的有意收窄**，需要单独确认。</p>
     */
    @Test
    void everyResumableStateExceptAPendingApprovalRemainsClaimable() {
        for (String status : AgentRun.allStatuses()) {
            if (!AgentRun.isResumable(status)) continue;
            String projection = AgentRunStatusProjection.of(status);
            if (AgentRun.STATUS_WAITING_APPROVAL.equals(status)) {
                assertThat(AgentRunStatusProjection.canClaim(projection))
                        .as("待决审批投影后必须不可 claim，否则那条审批会悬空").isFalse();
                continue;
            }
            assertThat(AgentRunStatusProjection.canClaim(projection))
                    .as("可恢复态 %s 投影后仍必须可 claim", status).isTrue();
        }
    }

    /** 终态投影后也必须可 claim：这就是"能继续对话"的含义。 */
    @Test
    void everyTerminalStateRemainsClaimableThroughItsProjection() {
        for (String status : AgentRun.allStatuses()) {
            if (!AgentRun.isTerminal(status)) continue;
            assertThat(AgentRunStatusProjection.canClaim(AgentRunStatusProjection.of(status)))
                    .as("终态 %s 投影后必须可 claim", status).isTrue();
        }
    }

    @Test
    void theFrontendVocabularyIsExactlyTheSevenProjectionValues() {
        assertThat(AgentRunStatusProjection.allStatuses()).containsExactlyInAnyOrder(
                "ready", "running", "awaiting_approval", "paused", "interrupted", "budget_exhausted", "failed");
    }

    /** 前端契约值不得超过列宽。会话状态列现在是 VARCHAR(24)，而 awaiting_approval 是 17。 */
    @Test
    void everyProjectionValueFitsTheConversationStatusColumn() {
        for (String status : AgentRunStatusProjection.allStatuses()) {
            assertThat(status.length()).as("投影值 %s 必须能放进 VARCHAR(24)", status).isLessThanOrEqualTo(24);
        }
    }
}

package com.xander.lab.agent.application;

import com.xander.lab.agent.domain.RunBudget;
import com.xander.lab.agent.domain.RunUsage;
import com.xander.lab.agent.infrastructure.AgentConversationProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 预算判定。
 *
 * <p>这里的断言口径统一是"**边界值**"而不是"明显超了"：预算闸门出错的典型形态不是"完全没判"，
 * 而是"差一格"——上限 60 却在第 60 次就停、或者允许第 61 次通过。所以每个维度都同时钉住
 * "刚好用尽不算超"和"再多一点就算超"两侧。</p>
 */
class RunBudgetGuardTest {

    private final RunBudgetGuard guard = new RunBudgetGuard();

    /** 配置折算：0 表示不限制，步数永远是有限值。 */
    @Test
    void configurationZeroMeansUnlimitedExceptForSteps() {
        AgentConversationProperties properties = new AgentConversationProperties();
        properties.setMaxSteps(0);
        properties.setMaxToolCalls(0);
        properties.setMaxTotalTokens(0);
        properties.setMaxMilliPoints(0);
        properties.setMaxActiveMinutes(0);

        RunBudget budget = guard.budgetFrom(properties);

        // 步数是唯一例外：不设上界就是一个没有出口的循环，所以配 0 一律按 1 处理。
        assertThat(budget.maxSteps()).isEqualTo(1);
        assertThat(budget.limitsSteps()).isTrue();
        assertThat(budget.limitsToolCalls()).isFalse();
        assertThat(budget.limitsTotalTokens()).isFalse();
        assertThat(budget.limitsMilliPoints()).isFalse();
        assertThat(budget.limitsActiveDuration()).isFalse();
    }

    @Test
    void anUnlimitedBudgetNeverExhausts() {
        RunUsage huge = new RunUsage(9999, 9999L, 999_999L, 999_999L, 0L, 999_999L,
                Duration.ofDays(30));

        assertThat(guard.check(RunBudget.unlimited(), huge).exhausted()).isFalse();
    }

    /** 步数上限 20 的含义是"最多跑 20 步"：跑到第 20 步之后就该停，不是还能再跑一步。 */
    @Test
    void stepBudgetTripsOnTheTwentyFirstStepNotTheTwentieth() {
        RunBudget budget = new RunBudget(20, 0, 0, 0, 0, 0, null);

        assertThat(guard.check(budget, usageWithSteps(19)).exhausted()).isFalse();
        assertThat(guard.check(budget, usageWithSteps(20)).reason())
                .isEqualTo(RunBudgetGuard.Reason.MAX_STEPS);
    }

    /**
     * 工具次数是**预扣**：判的是"加上这一批会不会超"，不是"现在已经超了没"。
     *
     * <p>后扣的话"余额判定"与"真实执行"之间存在窗口，一个批次可以合法地带着 3 次余额执行
     * 4 次调用，于是"上限 60"实际能跑到 63。</p>
     */
    @Test
    void toolBudgetIsPreDeductedForTheWholeBatch() {
        RunBudget budget = new RunBudget(0, 60, 0, 0, 0, 0, null);

        // 57 + 3 = 60，正好用尽，放行。
        assertThat(guard.check(budget, usageWithToolCalls(57), 3).exhausted()).isFalse();
        // 58 + 3 = 61，越界一格，挡下。
        assertThat(guard.check(budget, usageWithToolCalls(58), 3).reason())
                .isEqualTo(RunBudgetGuard.Reason.MAX_TOOL_CALLS);
        // 不预扣（pendingToolCalls = 0）时 58 本身没超——这正是"预扣"要补的那个窗口。
        assertThat(guard.check(budget, usageWithToolCalls(58)).exhausted()).isFalse();
    }

    @Test
    void activeDurationBudgetIgnoresTheWaitForApproval() {
        RunBudget budget = new RunBudget(0, 0, 0, 0, 0, 0, Duration.ofMinutes(30));

        assertThat(guard.check(budget, usageWithActiveDuration(Duration.ofMinutes(29))).exhausted())
                .isFalse();
        assertThat(guard.check(budget, usageWithActiveDuration(Duration.ofMinutes(30))).reason())
                .isEqualTo(RunBudgetGuard.Reason.MAX_ACTIVE_DURATION);
    }

    @Test
    void tokenAndMoneyBudgetsTripIndependently() {
        RunBudget tokens = new RunBudget(0, 0, 0, 0, 1000, 0, null);
        assertThat(guard.check(tokens, usageWithTokens(999)).exhausted()).isFalse();
        assertThat(guard.check(tokens, usageWithTokens(1000)).reason())
                .isEqualTo(RunBudgetGuard.Reason.MAX_TOTAL_TOKENS);

        RunBudget money = new RunBudget(0, 0, 0, 0, 0, 500, null);
        assertThat(guard.check(money, usageWithMilliPoints(499)).exhausted()).isFalse();
        assertThat(guard.check(money, usageWithMilliPoints(500)).reason())
                .isEqualTo(RunBudgetGuard.Reason.MAX_MILLI_POINTS);
    }

    /**
     * 多个维度同时越界时，报的是用户能自己处理的理由。
     *
     * <p>"步数用完了，继续说一句就行"是可行动的；"你花了 12 万 token"不是。
     * 顺序写死在 {@code check} 里，不依赖枚举声明序。</p>
     */
    @Test
    void theMostActionableReasonWinsWhenSeveralDimensionsAreExhausted() {
        RunBudget budget = new RunBudget(20, 60, 0, 0, 1000, 500, null);
        RunUsage everything = new RunUsage(20, 60L, 999L, 999L, 0L, 999L, Duration.ZERO);

        assertThat(guard.check(budget, everything).reason()).isEqualTo(RunBudgetGuard.Reason.MAX_STEPS);
    }

    /** 耗尽必须带原因：没有原因的"预算耗尽"没法被日志、告警或前端分别处理。 */
    @Test
    void anExhaustedVerdictAlwaysCarriesAReason() {
        RunBudget budget = new RunBudget(1, 0, 0, 0, 0, 0, null);

        RunBudgetGuard.Verdict verdict = guard.check(budget, usageWithSteps(1));

        assertThat(verdict.exhausted()).isTrue();
        assertThat(verdict.reason()).isNotNull();
    }

    /** 时长为 0 或负视为不限制：它最可能的来源是配置缺省，不是有人真想禁止执行。 */
    @Test
    void aZeroOrNegativeDurationIsTreatedAsUnlimited() {
        assertThat(new RunBudget(0, 0, 0, 0, 0, 0, Duration.ZERO).limitsActiveDuration()).isFalse();
        assertThat(new RunBudget(0, 0, 0, 0, 0, 0, Duration.ofMinutes(-5)).limitsActiveDuration())
                .isFalse();
    }

    /** 全维度都不限制时应当能被识别出来，调用方据此跳过整轮聚合查询。 */
    @Test
    void anAllUnlimitedBudgetIsRecognisable() {
        assertThat(RunBudget.unlimited().isUnlimited()).isTrue();
        assertThat(new RunBudget(20, 0, 0, 0, 0, 0, null).isUnlimited()).isFalse();
    }

    private static RunUsage usageWithSteps(int steps) {
        return new RunUsage(steps, 0L, 0L, 0L, 0L, 0L, Duration.ZERO);
    }

    private static RunUsage usageWithToolCalls(long toolCalls) {
        return new RunUsage(0, toolCalls, 0L, 0L, 0L, 0L, Duration.ZERO);
    }

    private static RunUsage usageWithTokens(long total) {
        return new RunUsage(0, 0L, total, 0L, 0L, 0L, Duration.ZERO);
    }

    private static RunUsage usageWithMilliPoints(long milliPoints) {
        return new RunUsage(0, 0L, 0L, 0L, 0L, milliPoints, Duration.ZERO);
    }

    private static RunUsage usageWithActiveDuration(Duration duration) {
        return new RunUsage(0, 0L, 0L, 0L, 0L, 0L, duration);
    }
}

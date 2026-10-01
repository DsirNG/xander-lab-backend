package com.xander.lab.agent.application;

import com.xander.lab.agent.domain.RunBudget;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code budget_json} 的编解码。
 *
 * <p>这份 JSON 的形态是**长期契约**：一个 Run 的生命周期内每次预算判定都要读它，排障时还要拿它
 * 复现"当时用的是哪套标准"。所以这里钉的不是"能不能读回来"，而是"形态不许被序列化配置悄悄改掉"。</p>
 */
class RunBudgetJsonTest {

    @Test
    void everyDimensionSurvivesARoundTrip() {
        RunBudget original = new RunBudget(20, 60, 1000, 2000, 3000, 4000, Duration.ofMinutes(30));

        RunBudget restored = RunBudgetJson.read(RunBudgetJson.write(original));

        assertThat(restored).isEqualTo(original);
    }

    /** 不限制的维度用负数往返，不能被读成 0——那会把"没配"变成"上限为 0"。 */
    @Test
    void unlimitedDimensionsRoundTripAsUnlimited() {
        RunBudget restored = RunBudgetJson.read(RunBudgetJson.write(RunBudget.unlimited()));

        assertThat(restored).isNotNull();
        assertThat(restored.isUnlimited()).isTrue();
        assertThat(restored.limitsSteps()).isFalse();
        assertThat(restored.limitsActiveDuration()).isFalse();
    }

    /** 时长以秒存：解析不依赖任何模块注册，且秒是这个字段唯一有意义的精度。 */
    @Test
    void theActiveDurationIsStoredAsSeconds() {
        String json = RunBudgetJson.write(new RunBudget(1, 0, 0, 0, 0, 0, Duration.ofMinutes(5)));

        assertThat(json).contains("\"maxActiveSeconds\":300");
        assertThat(RunBudgetJson.read(json).maxActiveDuration()).isEqualTo(Duration.ofMinutes(5));
    }

    /** 损坏的快照读成 null 而不是 unlimited：让调用方能记一条日志，并退回当前配置。 */
    @Test
    void malformedInputYieldsNullRatherThanAnUnlimitedBudget() {
        assertThat(RunBudgetJson.read(null)).isNull();
        assertThat(RunBudgetJson.read("")).isNull();
        assertThat(RunBudgetJson.read("  ")).isNull();
        assertThat(RunBudgetJson.read("not json at all")).isNull();
        assertThat(RunBudgetJson.read("[1,2,3]")).isNull();
    }

    /** 字段缺失时按"不限制"补齐，而不是让整个 Run 起不来。 */
    @Test
    void missingFieldsFallBackToUnlimited() {
        RunBudget restored = RunBudgetJson.read("{\"maxSteps\":5}");

        assertThat(restored.maxSteps()).isEqualTo(5);
        assertThat(restored.limitsToolCalls()).isFalse();
        assertThat(restored.limitsActiveDuration()).isFalse();
    }
}

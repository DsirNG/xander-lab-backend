package com.xander.lab.common.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全平面就绪标记测试（V11 §27.1 第 1 / 8 步）。
 */
class SecurityPlaneReadinessTest {

    @Test
    void startsNotReady() {
        SecurityPlaneReadiness readiness = new SecurityPlaneReadiness();

        assertThat(readiness.isReady()).as("§27.1 第 1 步：readiness 必须是 NOT_READY 起步").isFalse();
        assertThat(readiness.state()).isEqualTo(SecurityPlaneReadiness.STATE_NOT_READY);
        assertThat(readiness.readyAtMillis()).isZero();
        assertThat(readiness.failureReason()).isNull();
    }

    @Test
    void markReadyFlipsTheStateAndStampsTheTime() {
        SecurityPlaneReadiness readiness = new SecurityPlaneReadiness();

        readiness.markReady(1_700_000_000_000L);

        assertThat(readiness.isReady()).isTrue();
        assertThat(readiness.state()).isEqualTo(SecurityPlaneReadiness.STATE_READY);
        assertThat(readiness.readyAtMillis()).isEqualTo(1_700_000_000_000L);
    }

    @Test
    void markFailedKeepsTheInstanceNotReadyAndRecordsWhy() {
        SecurityPlaneReadiness readiness = new SecurityPlaneReadiness();

        readiness.markFailed("account_security_state 读不到");

        assertThat(readiness.isReady()).isFalse();
        assertThat(readiness.state()).isEqualTo(SecurityPlaneReadiness.STATE_NOT_READY);
        assertThat(readiness.failureReason()).isEqualTo("account_security_state 读不到");
        assertThat(readiness.readyAtMillis()).as("失败不得留下就绪时间").isZero();
    }

    @Test
    void markReadyClearsAPreviousFailureReason() {
        SecurityPlaneReadiness readiness = new SecurityPlaneReadiness();
        readiness.markFailed("第一次尝试失败");

        readiness.markReady(42L);

        assertThat(readiness.isReady()).isTrue();
        assertThat(readiness.failureReason())
                .as("已就绪的实例不应继续报出旧的失败原因")
                .isNull();
    }

    @Test
    void stateLiteralsAreStableBecauseDeployProbesMatchOnThem() {
        // 这两个字面量会出现在 /health/ready 的响应体里，部署侧的探针脚本可能按值匹配，
        // 所以它们是被冻结的字符串契约，不是随手起的名字。
        assertThat(SecurityPlaneReadiness.STATE_READY).isEqualTo("READY");
        assertThat(SecurityPlaneReadiness.STATE_NOT_READY).isEqualTo("NOT_READY");
    }
}

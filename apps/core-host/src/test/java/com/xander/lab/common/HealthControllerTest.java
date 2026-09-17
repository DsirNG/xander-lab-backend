package com.xander.lab.common;

import com.xander.lab.common.security.SecurityPlaneReadiness;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class HealthControllerTest {

    @Test
    void reportsRedisAsUpWhenPingSucceeds() {
        RedisHealthProbe probe = mock(RedisHealthProbe.class);

        var response = new HealthController(probe, new SecurityPlaneReadiness()).health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP").containsEntry("redis", "UP");
        verify(probe).ping();
    }

    @Test
    void reportsRedisAsDownWhenPingFails() {
        RedisHealthProbe probe = mock(RedisHealthProbe.class);
        doThrow(new RuntimeException("Redis is unavailable")).when(probe).ping();

        var response = new HealthController(probe, new SecurityPlaneReadiness()).health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("status", "DOWN").containsEntry("redis", "DOWN");
        verify(probe).ping();
    }

    @Test
    void livenessStaysUpWhileTheSecurityPlaneIsNotReady() {
        // 存活探针的语义必须与安全平面解耦：docker-compose 的 healthcheck 用的是 /health，
        // 若它在启动窗口里返回 503，一次正常启动会被判为不健康并触发重启。
        RedisHealthProbe probe = mock(RedisHealthProbe.class);

        var response = new HealthController(probe, new SecurityPlaneReadiness()).health();

        assertThat(response.getStatusCode())
                .as("安全平面未就绪不影响存活语义")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("securityPlane", SecurityPlaneReadiness.STATE_NOT_READY);
    }

    @Test
    void readinessReturns503WhileTheSecurityPlaneIsNotReady() {
        RedisHealthProbe probe = mock(RedisHealthProbe.class);
        SecurityPlaneReadiness readiness = new SecurityPlaneReadiness();

        var response = new HealthController(probe, readiness).readiness();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody())
                .containsEntry("status", "DOWN")
                .containsEntry("securityPlane", SecurityPlaneReadiness.STATE_NOT_READY);
        assertThat(response.getBody()).doesNotContainKey("securityPlaneReadyAt");
    }

    @Test
    void readinessReportsWhyThePlaneFailedToBecomeReady() {
        SecurityPlaneReadiness readiness = new SecurityPlaneReadiness();
        readiness.markFailed("连接池耗尽");

        var response = new HealthController(mock(RedisHealthProbe.class), readiness).readiness();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("securityPlaneFailure", "连接池耗尽");
    }

    @Test
    void readinessReturns200OnlyWhenThePlaneIsReadyAndRedisAnswers() {
        RedisHealthProbe probe = mock(RedisHealthProbe.class);
        SecurityPlaneReadiness readiness = new SecurityPlaneReadiness();
        readiness.markReady(1_700_000_000_000L);

        var response = new HealthController(probe, readiness).readiness();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("status", "UP")
                .containsEntry("redis", "UP")
                .containsEntry("securityPlane", SecurityPlaneReadiness.STATE_READY)
                .containsEntry("securityPlaneReadyAt", 1_700_000_000_000L);
        verify(probe).ping();
    }

    @Test
    void readinessFailsClosedWhenRedisIsDownEvenIfThePlaneIsReady() {
        RedisHealthProbe probe = mock(RedisHealthProbe.class);
        doThrow(new RuntimeException("Redis is unavailable")).when(probe).ping();
        SecurityPlaneReadiness readiness = new SecurityPlaneReadiness();
        readiness.markReady(1L);

        var response = new HealthController(probe, readiness).readiness();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("redis", "DOWN");
    }
}

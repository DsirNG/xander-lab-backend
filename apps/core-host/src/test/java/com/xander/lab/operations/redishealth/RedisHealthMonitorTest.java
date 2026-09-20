package com.xander.lab.operations.redishealth;

import com.xander.lab.common.RedisHealthProbe;
import com.xander.lab.integration.api.MailPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RedisHealthMonitorTest {

    private RedisHealthProbe probe;
    private MailPort mailPort;
    private RedisHealthMonitor monitor;

    @BeforeEach
    void setUp() {
        probe = mock(RedisHealthProbe.class);
        mailPort = mock(MailPort.class);
        monitor = new RedisHealthMonitor(probe, mailPort);
        org.springframework.test.util.ReflectionTestUtils.setField(monitor, "recipient", "owner@example.com");
        org.springframework.test.util.ReflectionTestUtils.setField(monitor, "failureThreshold", 2);
        org.springframework.test.util.ReflectionTestUtils.setField(monitor, "notifyRecovery", true);
        org.springframework.test.util.ReflectionTestUtils.setField(monitor, "alertRetryIntervalMs", 300_000L);
    }

    @Test
    void sendsOneAlertAfterConsecutiveFailuresAndOneRecoveryNotice() {
        doThrow(new RuntimeException("connection refused")).when(probe).ping();

        monitor.checkRedis();
        verify(mailPort, never()).sendSimpleMail(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        );

        monitor.checkRedis();
        monitor.checkRedis();
        verify(mailPort).sendSimpleMail(
                eq("owner@example.com"),
                eq("【DinQorAI】Redis 不可用告警"),
                org.mockito.ArgumentMatchers.contains("connection refused")
        );

        org.mockito.Mockito.reset(probe);
        monitor.checkRedis();
        verify(mailPort).sendSimpleMail(
                eq("owner@example.com"),
                eq("【DinQorAI】Redis 已恢复"),
                org.mockito.ArgumentMatchers.contains("检测时间")
        );
    }

    @Test
    void mailFailureDoesNotStopSubsequentMonitoring() {
        doThrow(new RuntimeException("connection refused")).when(probe).ping();
        doThrow(new RuntimeException("smtp down")).when(mailPort).sendSimpleMail(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        );

        monitor.checkRedis();
        monitor.checkRedis();
        monitor.checkRedis();

        verify(mailPort).sendSimpleMail(
                eq("owner@example.com"),
                eq("【DinQorAI】Redis 不可用告警"),
                org.mockito.ArgumentMatchers.contains("connection refused")
        );
    }
}

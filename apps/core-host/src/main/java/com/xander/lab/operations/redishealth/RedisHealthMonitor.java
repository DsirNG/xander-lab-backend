package com.xander.lab.operations.redishealth;

import com.xander.lab.common.RedisHealthProbe;
import com.xander.lab.integration.api.MailPort;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Monitors Redis in the background and sends one email per outage/recovery transition. */
@Slf4j
@Component
@ConditionalOnProperty(name = "redis-monitor.enabled", havingValue = "true")
public class RedisHealthMonitor {

    private final RedisHealthProbe redisHealthProbe;
    private final MailPort mailPort;

    @Value("${redis-monitor.recipient:}")
    private String recipient;

    @Value("${redis-monitor.failure-threshold:2}")
    private int failureThreshold;

    @Value("${redis-monitor.notify-recovery:true}")
    private boolean notifyRecovery;

    @Value("${redis-monitor.alert-retry-interval-ms:300000}")
    private long alertRetryIntervalMs = 300_000L;

    private int consecutiveFailures;
    private boolean outageNotified;
    private boolean missingRecipientLogged;
    private long lastAlertAttemptAt = Long.MIN_VALUE;

    public RedisHealthMonitor(RedisHealthProbe redisHealthProbe, MailPort mailPort) {
        this.redisHealthProbe = redisHealthProbe;
        this.mailPort = mailPort;
    }

    /** Checks Redis periodically; SMTP failures are logged and never stop monitoring. */
    @Scheduled(
            fixedDelayString = "${redis-monitor.poll-interval-ms:30000}",
            initialDelayString = "${redis-monitor.initial-delay-ms:30000}"
    )
    @SchedulerLock(name = "redis-health-monitor", lockAtMostFor = "PT1M")
    public synchronized void checkRedis() {
        try {
            redisHealthProbe.ping();
            if (outageNotified && notifyRecovery) {
                sendAlert(
                        "【DinQorAI】Redis 已恢复",
                        "Redis 连接已恢复。\n\n检测时间：" + Instant.now()
                );
            }
            consecutiveFailures = 0;
            outageNotified = false;
            lastAlertAttemptAt = Long.MIN_VALUE;
        } catch (RuntimeException ex) {
            consecutiveFailures++;
            log.warn("[RedisMonitor] Redis 探测失败：consecutiveFailures={}, reason={}",
                    consecutiveFailures, rootMessage(ex));
            long now = System.currentTimeMillis();
            boolean retryAllowed = lastAlertAttemptAt == Long.MIN_VALUE
                    || now - lastAlertAttemptAt >= Math.max(1, alertRetryIntervalMs);
            if (!outageNotified && consecutiveFailures >= Math.max(1, failureThreshold) && retryAllowed) {
                lastAlertAttemptAt = now;
                outageNotified = sendAlert(
                        "【DinQorAI】Redis 不可用告警",
                        "Redis 连续 " + consecutiveFailures + " 次探测失败，依赖 Redis 的登录、会话等业务可能不可用。\n\n"
                                + "检测时间：" + Instant.now() + "\n"
                                + "失败原因：" + limit(rootMessage(ex), 500)
                );
            }
        }
    }

    private boolean sendAlert(String subject, String content) {
        if (recipient == null || recipient.isBlank()) {
            if (!missingRecipientLogged) {
                log.error("[RedisMonitor] 告警收件人未配置，无法发送 Redis 邮件告警");
                missingRecipientLogged = true;
            }
            return false;
        }
        try {
            mailPort.sendSimpleMail(recipient, subject, content);
            log.info("[RedisMonitor] Redis 告警邮件已发送：recipient={}", recipient);
            return true;
        } catch (RuntimeException mailFailure) {
            log.error("[RedisMonitor] Redis 告警邮件发送失败：{}", rootMessage(mailFailure));
            return false;
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private String limit(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}

package com.xander.lab.identity.application;

import com.xander.lab.common.Constants;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** V9 §58/§59 遗留 Redis 键族的低频盘点与封禁撤销耗时指标。 */
@Slf4j
@Component
public class LegacyRedisMetrics {

    private static final long SCAN_COUNT_HINT = 500L;

    private final StringRedisTemplate redisTemplate;
    private final AtomicLong tokenKeyCount = new AtomicLong();
    private final AtomicLong userTokenKeyCount = new AtomicLong();
    private final AtomicLong userTokenMemberCount = new AtomicLong();
    private final AtomicLong userTokenMaxCardinality = new AtomicLong();
    private final AtomicLong activeEstimate = new AtomicLong();
    private final Counter accessIssued;
    private final Counter accessRevoked;
    private final Counter legacyRefreshRequests;
    private final Timer revokeDuration;

    public LegacyRedisMetrics(MeterRegistry meterRegistry, StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        gauge(meterRegistry, "legacy_login_token_count", tokenKeyCount,
                "遗留 login:token:* 键数量");
        gauge(meterRegistry, "legacy_login_user_tokens_key_count", userTokenKeyCount,
                "遗留 login:user_tokens:* 键数量");
        gauge(meterRegistry, "legacy_login_user_tokens_total_members", userTokenMemberCount,
                "遗留 login:user_tokens:* Set 成员总数");
        gauge(meterRegistry, "legacy_login_user_tokens_max_cardinality", userTokenMaxCardinality,
                "遗留 login:user_tokens:* 的最大 Set 基数");
        gauge(meterRegistry, "legacy_access_active_estimate", activeEstimate,
                "低频 SCAN 盘点得到的遗留 Access key 存量估计");
        accessIssued = Counter.builder("legacy_access_issued_total")
                .description("迁移期遗留 Access 签发总数；T1 后必须停止增长")
                .register(meterRegistry);
        accessRevoked = Counter.builder("legacy_access_revoked_total")
                .description("迁移期主动撤销的遗留 Access 总数")
                .register(meterRegistry);
        legacyRefreshRequests = Counter.builder("legacy_refresh_requests_total")
                .description("迁移期遗留 JWT Refresh 请求总数；T3 前必须归零")
                .register(meterRegistry);
        revokeDuration = Timer.builder("legacy_revoke_duration")
                .description("遗留 Redis 会话撤销耗时")
                .register(meterRegistry);
    }

    /** 迁移期低频盘点，避免在业务高峰用 KEYS 扫描 Redis。 */
    @Scheduled(initialDelay = 60_000L, fixedDelay = 3_600_000L)
    public void reconcile() {
        try {
            LegacySnapshot snapshot = redisTemplate.execute((RedisCallback<LegacySnapshot>)
                    LegacyRedisMetrics::scanSnapshot);
            if (snapshot == null) {
                return;
            }
            tokenKeyCount.set(snapshot.tokenKeyCount());
            userTokenKeyCount.set(snapshot.userTokenKeyCount());
            userTokenMemberCount.set(snapshot.userTokenMemberCount());
            userTokenMaxCardinality.set(snapshot.userTokenMaxCardinality());
            activeEstimate.set(snapshot.tokenKeyCount());
        } catch (RuntimeException ex) {
            log.warn("[LegacyRedis] 键族盘点失败，保留上一轮观测值: {}", ex.getMessage());
        }
    }

    public void recordRevokeDuration(long elapsedNanos) {
        revokeDuration.record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    public void recordAccessIssued() {
        accessIssued.increment();
    }

    public void recordAccessRevoked(long count) {
        if (count > 0L) {
            accessRevoked.increment(count);
        }
    }

    public void recordLegacyRefreshRequest() {
        legacyRefreshRequests.increment();
    }

    private static LegacySnapshot scanSnapshot(RedisConnection connection) {
        long tokenKeys = countKeys(connection, Constants.REDIS_TOKEN_PREFIX + "*");
        long userTokenKeys = 0L;
        long totalMembers = 0L;
        long maxCardinality = 0L;
        ScanOptions options = ScanOptions.scanOptions()
                .match(Constants.REDIS_USER_TOKENS_PREFIX + "*")
                .count(SCAN_COUNT_HINT)
                .build();
        try (Cursor<byte[]> cursor = connection.scan(options)) {
            while (cursor.hasNext()) {
                byte[] key = cursor.next();
                long cardinality = valueOrZero(connection.sCard(key));
                userTokenKeys++;
                totalMembers += cardinality;
                maxCardinality = Math.max(maxCardinality, cardinality);
            }
        }
        return new LegacySnapshot(tokenKeys, userTokenKeys, totalMembers, maxCardinality);
    }

    private static long countKeys(RedisConnection connection, String pattern) {
        long count = 0L;
        ScanOptions options = ScanOptions.scanOptions()
                .match(pattern)
                .count(SCAN_COUNT_HINT)
                .build();
        try (Cursor<byte[]> cursor = connection.scan(options)) {
            while (cursor.hasNext()) {
                cursor.next();
                count++;
            }
        }
        return count;
    }

    private static long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private static void gauge(MeterRegistry registry, String name, AtomicLong value,
                              String description) {
        Gauge.builder(name, value, AtomicLong::doubleValue)
                .description(description)
                .register(registry);
    }

    record LegacySnapshot(long tokenKeyCount, long userTokenKeyCount,
                          long userTokenMemberCount, long userTokenMaxCardinality) {
    }
}

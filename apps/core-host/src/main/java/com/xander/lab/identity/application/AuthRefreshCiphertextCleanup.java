package com.xander.lab.identity.application;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 已过期 successor 密文的物理清空（V9 §43）。
 *
 * <pre>
 * execution_scope:      global_once
 * singleton_strategy:   mysql_shedlock
 * interval:             30~60 秒
 * </pre>
 *
 * <h3>这个任务不承担任何安全语义</h3>
 * <p>§43 原文：「即使物理字段晚几十秒清空，业务代码也必须在 grace 到期后拒绝返回。
 * 所以安全语义依赖<b>时间判断</b>，不是依赖清理任务刚好准点运行。」</p>
 *
 * <p>代码侧已经按这个要求实现：重放路径在解密<b>之前</b>先问
 * {@code AuthRefreshTokenHistory#isCiphertextReadable(now)}，
 * 它同时检查密文非空<b>和</b> {@code now <= successorCiphertextExpiresAt}。
 * 所以本任务停摆（甚至被误删）不会让任何过期密文重新可用 ——
 * 它只影响磁盘上留着多少没用的字节。</p>
 *
 * <p>这条区分必须写在代码里，否则下一次修订很容易把它"优化"成安全边界：
 * 一旦有人让重放路径改成"密文为空就当作已过期"，任务停摆就变成了可被利用的窗口。</p>
 *
 * <h3>为什么是 UPDATE 而不是 DELETE</h3>
 * <p>密文窗口（秒级）远短于 Reuse 判定窗口（{@code retained_until}，24 小时）。
 * 删行会让这段时间里的真实盗用无法被识别（§39 分支 3 依赖行还在）。
 * 所以只清空字节，行留着。</p>
 *
 * <h3>依赖的索引</h3>
 * <p>{@code idx_refresh_history_ciphertext_expires}。缺了它就是全表扫描。</p>
 */
@Slf4j
@Component
public class AuthRefreshCiphertextCleanup {

    private final AuthRefreshRetentionService retentionService;
    private final RetentionBatchLoop batchLoop;
    private final RefreshTokenProperties properties;
    private final Clock clock;

    @Autowired
    public AuthRefreshCiphertextCleanup(AuthRefreshRetentionService retentionService,
                                        RetentionBatchLoop batchLoop,
                                        RefreshTokenProperties properties) {
        this(retentionService, batchLoop, properties, Clock.systemDefaultZone());
    }

    /** 供测试注入固定时钟，让本轮的时间边界可被确定性断言。 */
    AuthRefreshCiphertextCleanup(AuthRefreshRetentionService retentionService,
                                 RetentionBatchLoop batchLoop,
                                 RefreshTokenProperties properties,
                                 Clock clock) {
        this.retentionService = retentionService;
        this.batchLoop = batchLoop;
        this.properties = properties;
        this.clock = clock;
    }

    /** 分批清空已过逻辑过期时间的密文字段。 */
    @Scheduled(fixedDelayString = "${auth.refresh.ciphertext-cleanup-interval-ms:30000}")
    @SchedulerLock(name = "auth-refresh-ciphertext-cleanup", lockAtMostFor = "PT2M")
    public void clearExpiredCiphertext() {
        int batchSize = Math.max(1, properties.getCiphertextCleanupBatchSize());
        int maxBatches = Math.max(1, properties.getCiphertextCleanupMaxBatches());
        int cleared = batchLoop.run("密文清空", LocalDateTime.now(clock), batchSize, maxBatches,
                retentionService::clearExpiredCiphertext);
        if (cleared > 0) {
            log.debug("[Refresh] 密文清空本轮清空 {} 行", cleared);
        }
    }
}

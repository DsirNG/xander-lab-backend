package com.xander.lab.identity.application;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 新 Refresh 协议参数（V9 §24 / §35 / §40）。
 *
 * <p>这些值全部属于 Contract：{@code VITE_REQUEST_TIMEOUT} / refresh retry count /
 * refresh backoff / server grace 任何一项变化都必须同步复核（V9 §36），
 * 变更要写进 {@code docs/contract-changes.yaml}。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "auth.refresh")
public class RefreshTokenProperties {

    /**
     * 总开关。Wave 0A 默认关闭 —— 实现与单测先落地，切流是 Wave 2 的事
     * （V9 §94「新 Token Model 切流」）。关闭时旧的无状态 refresh JWT 行为完全不变。
     */
    private boolean enabled = false;

    /**
     * Access Token 有效期（秒）。V9 §24 推荐初始 30 分钟，安全评审允许 15~30 分钟。
     *
     * <p>注意：选 15 分钟会使 §40 的 history 容量上界翻倍（480,000 → 960,000 行），
     * 必须同步复核 §42 的清理吞吐。</p>
     */
    private long accessTtlSeconds = 1800;

    /** Refresh Session 绝对有效期（秒），默认 30 天。轮换不延长它。 */
    private long sessionTtlSeconds = 2_592_000;

    /**
     * 幂等返回窗口（秒）。
     *
     * <p>公式（V9 §35）：{@code grace >= refresh request timeout + 最大 retry backoff
     * + 单程网络/调度余量 + clock/scheduler margin}。</p>
     *
     * <p>当前取值依据：前端 refresh timeout = 15s，最大重试 1 次，
     * backoff 500~1000ms + jitter，网络/调度余量 >= 5s → <b>25s</b>。</p>
     */
    private long graceSeconds = 25;

    /**
     * Reuse 判定窗口（小时）。V9 §40.1：retention = 24h。
     *
     * <p>与 grace 是两个独立窗口：只保留 grace 会导致 25 秒之后的真实盗用
     * 无法被识别。</p>
     */
    private long retentionHours = 24;

    /**
     * 每 sid 历史条数上界。V9 §40.1：最多最近 48 代。
     *
     * <p>时间上界与条数上界必须同时生效。</p>
     */
    private int maxGenerationsPerSid = 48;

    /**
     * successor 密文加密密钥（Base64 编码的 32 字节）。
     *
     * <p>V9 §16：不进 Nacos 明文，走部署 Secret / 环境变量
     * （{@code AUTH_REFRESH_ENCRYPTION_KEY}）。开启本特性时缺失会直接启动失败，
     * 不允许回落到已知默认密钥。</p>
     */
    private String encryptionKey = "";

    /** §42 历史清理单批行数。建议 500~1000。 */
    private int historyCleanupBatchSize = 500;

    /** §42 批间 sleep 毫秒数（100~500），避免长时间占用主库。 */
    private long historyCleanupSleepMs = 200;

    /** §42 单次调度最多执行的批数，防止一次跑太久。 */
    private int historyCleanupMaxBatches = 20;

    /** §43 密文清理单批行数。 */
    private int ciphertextCleanupBatchSize = 500;

    /** §43 密文清理调度间隔（毫秒），建议 30~60 秒。 */
    private long ciphertextCleanupIntervalMs = 30_000;

    /** §42 历史清理调度间隔（毫秒），全天低强度。 */
    private long historyCleanupIntervalMs = 300_000;

    /** 安全状态 reconcile 间隔（毫秒）。V9 §27 / §28.3：必须 <= 2 秒。 */
    private long securityReconcileIntervalMs = 1_500;

    /** 安全状态 reconcile 单批条数。 */
    private int securityReconcileBatchSize = 500;
}

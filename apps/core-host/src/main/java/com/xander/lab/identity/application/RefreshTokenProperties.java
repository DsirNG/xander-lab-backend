package com.xander.lab.identity.application;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 新 Refresh 协议参数。
 *
 * <p>这些值全部属于 Contract：{@code VITE_REQUEST_TIMEOUT} / refresh retry count /
 * refresh backoff / server grace 任何一项变化都必须同步复核。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "auth.refresh")
public class RefreshTokenProperties {

    /**
     * 总开关。默认关闭 —— 实现与单测先落地，切流由后续部署决定。
     * 关闭时旧的无状态 refresh JWT 行为完全不变。
     */
    private boolean enabled = false;

    /**
     * Access Token 有效期（秒）。推荐初始 30 分钟，安全评审允许 15~30 分钟。
     *
     * <p>注意：选 15 分钟会使 history 容量上界翻倍（480,000 → 960,000 行），
     * 必须同步复核清理吞吐。</p>
     */
    private long accessTtlSeconds = 1800;

    /** Refresh Session 绝对有效期（秒），默认 30 天。轮换不延长它。 */
    private long sessionTtlSeconds = 2_592_000;

    /**
     * 幂等返回窗口（秒）。
     *
     * <p>公式：{@code grace >= refresh request timeout + 最大 retry backoff
     * + 单程网络/调度余量 + clock/scheduler margin}。</p>
     *
     * <p>当前取值依据：前端 refresh timeout = 15s，最大重试 1 次，
     * backoff 500~1000ms + jitter，网络/调度余量 >= 5s → <b>25s</b>。</p>
     */
    private long graceSeconds = 25;

    /**
     * Reuse 判定窗口（小时）。retention = 24h。
     *
     * <p>与 grace 是两个独立窗口：只保留 grace 会导致 25 秒之后的真实盗用
     * 无法被识别。</p>
     */
    private long retentionHours = 24;

    /**
     * 每 sid 历史条数上界。最多最近 48 代。
     *
     * <p>时间上界与条数上界必须同时生效。</p>
     */
    private int maxGenerationsPerSid = 48;

    /**
     * successor 密文加密密钥（Base64 编码的 32 字节）。
     *
     * <p>不进 Nacos 明文，走部署 Secret / 环境变量
     * （{@code AUTH_REFRESH_ENCRYPTION_KEY}）。开启本特性时缺失会直接启动失败，
     * 不允许回落到已知默认密钥。</p>
     */
    private String encryptionKey = "";

    /** 历史清理单批行数。建议 500~1000。 */
    private int historyCleanupBatchSize = 500;

    /**
     * 批间 sleep 毫秒数（100~500），避免长时间占用主库。
     *
     * <p>实际停顿是 {@code sleepMs + [0, sleepMs]} 的随机值（jitter）。
     * 两个清理任务共用它：批间节奏是同一个问题，分成两个键只会让其中一个
     * 在有人调优时被漏掉。</p>
     */
    private long historyCleanupSleepMs = 200;

    /** 历史清理单次调度最多执行的批数，防止一次跑太久。 */
    private int historyCleanupMaxBatches = 20;

    /** 密文清理单批行数。 */
    private int ciphertextCleanupBatchSize = 500;

    /**
     * 密文清理单次调度最多执行的批数。
     *
     * <p>密文到期是<b>连续</b>发生的（每次 grace 到期就多一批），所以这里也需要
     * 上界：一轮最多清 20 × 500 = 10,000 行，积压则留到 30 秒后的下一轮，
     * 而不是让一次调度长时间占着主库。</p>
     */
    private int ciphertextCleanupMaxBatches = 20;

    /**
     * 单批耗时阈值（毫秒），超过即主动暂停本轮。
     *
     * <p>保留策略要求「数据库负载高时自动暂停」。这里用的是<b>启发式代理信号</b>：
     * 单批 DELETE/UPDATE 变慢通常意味着主库在争用（锁等待、IO 饱和），
     * 继续跑完剩下的批次只会加剧它，而保留策略没有任何时效性。</p>
     *
     * <p><b>已知局限</b>：这不是真正的负载检测（读不到 {@code Threads_running}
     * 之类的指标），也看不到"慢是因为本任务自己"还是"因为别的高峰流量"。
     * 真正的负载感知暂停属于后续的可观测性工作。</p>
     */
    private long cleanupSlowBatchMs = 2_000;

    /** 密文清理调度间隔（毫秒），建议 30~60 秒。 */
    private long ciphertextCleanupIntervalMs = 30_000;

    /** 历史清理调度间隔（毫秒），全天低强度。 */
    private long historyCleanupIntervalMs = 300_000;

    /** 安全状态 reconcile 间隔（毫秒）。必须 <= 2 秒。 */
    private long securityReconcileIntervalMs = 1_500;

    /** 安全状态 reconcile 单批条数。 */
    private int securityReconcileBatchSize = 500;

    /**
     * 安全事件发件箱投递间隔（毫秒）。
     *
     * <p>封禁传播的 5 秒预算里，"事件产生 → 到达宿主"给了 2 秒。快路径与兜底路径
     * 并行，所以两者都不需要单独吃满 2 秒；这里取 1 秒，让快路径通常先到。</p>
     */
    private long securityOutboxPublishIntervalMs = 1_000;

    /** 安全事件发件箱单轮投递条数。 */
    private int securityOutboxPublishBatchSize = 200;

    /**
     * 是否启用安全事件 Redis Pub/Sub 快路径。
     *
     * <p>默认关闭以保持单实例/本地开发行为不变。多实例部署时必须在所有
     * core-host 实例上同时开启；关闭时发件箱仍直接应用到本实例。</p>
     */
    private boolean securityPubsubEnabled = false;

    /** 多实例安全事件广播频道。频道名必须在同一部署单元内保持一致。 */
    private String securityPubsubChannel = "dinqor:security-events";
}

package com.xander.lab.common.security;

/**
 * 安全平面就绪标记（V11 §27.1 第 1 步与第 8 步）。
 *
 * <p>V11 §27.1 把一个实例的生命周期切成两段：{@code NOT_READY}（本地 deny cache
 * 尚未建立，<b>不得</b>接流量）与 {@code READY}（snapshot + replay 已完成，可以进入
 * 服务发现 / 负载均衡）。本类就是这两段之间的唯一开关。</p>
 *
 * <h3>为什么是独立对象，而不是 {@link SecurityStateCache} 上的一个布尔字段</h3>
 * <p>因为写入者必须唯一。缓存有三个写入者（{@code SecurityStateBootstrap}、
 * {@code SecurityStateReconciler}、{@code SecurityEventOutboxPublisher}），
 * 若就绪标记长在缓存上，任何一次"顺手置位"都能让实例在缓存不完整时宣布自己就绪 ——
 * 而这正是 §27.1 明令禁止的失败模式（"实例启动后先接流量，再慢慢填 deny cache"）。
 * 独立成对象之后，唯一的写入者是 {@code SecurityStateBootstrap}。</p>
 *
 * <h3>为什么放在共享内核</h3>
 * <p>与 {@link SecurityStateCache} 同理：Gateway（WebFlux）、core-host、agent、
 * content-automation 要用同一套语义，而本类<b>只依赖 JDK</b> —— 不依赖 Spring MVC、
 * MyBatis 或任何业务域，所以 Gateway 依赖它不会拖进 {@code spring-boot-starter-web}
 * （那会直接让 WebFlux 应用启动失败）。</p>
 *
 * <h3>线程安全</h3>
 * <p>字段都是 {@code volatile}，读侧（每个请求、每个探针）无锁。写侧的字段顺序是刻意的：
 * {@link #markReady} <b>最后</b>写 {@code ready}，所以任何看到 {@code ready == true}
 * 的线程一定也能看到已经写好的 {@code readyAtMillis}；{@link #markFailed} 则<b>先</b>写
 * {@code ready}，避免读侧观察到一个"未就绪但带着就绪时间"的中间态。</p>
 */
public class SecurityPlaneReadiness {

    /** 未就绪字面量；与 {@code /health/ready} 响应体一致，供部署侧与观测侧引用。 */
    public static final String STATE_NOT_READY = "NOT_READY";

    /** 已就绪字面量。见 {@link #STATE_NOT_READY}。 */
    public static final String STATE_READY = "READY";

    private volatile boolean ready;
    private volatile long readyAtMillis;
    private volatile String failureReason;

    /** 是否已允许接流量。 */
    public boolean isReady() {
        return ready;
    }

    /** 当前状态字面量，用于探针与日志。 */
    public String state() {
        return ready ? STATE_READY : STATE_NOT_READY;
    }

    /**
     * 宣布就绪。只应由 {@code SecurityStateBootstrap} 在 snapshot + replay 完成后调用。
     *
     * @param epochMillis 宣布时刻；同时是 {@code security_plane_ready_at} 指标与
     *                    "启动耗时"日志的来源
     */
    public void markReady(long epochMillis) {
        this.readyAtMillis = epochMillis;
        this.failureReason = null;
        this.ready = true;
    }

    /**
     * 记录一次"未能就绪"。
     *
     * <p>只由 {@code SecurityStateBootstrap} 在启动失败时调用。调用后实例保持
     * {@code NOT_READY}；配合 bootstrap 的 fail-fast 语义，该实例不会接任何流量，
     * 同时 {@code /health/ready} 会把 {@code reason} 报出来，便于运维区分
     * "启动失败" 与 "还在启动中"。</p>
     */
    public void markFailed(String reason) {
        this.ready = false;
        this.failureReason = reason;
    }

    /** 就绪时刻（毫秒）；从未就绪时为 0。 */
    public long readyAtMillis() {
        return readyAtMillis;
    }

    /** 未能就绪的原因；未失败、或已就绪时为 null。 */
    public String failureReason() {
        return failureReason;
    }
}

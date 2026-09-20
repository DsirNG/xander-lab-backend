package com.xander.lab.identity.application;

import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityStateCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.OptionalLong;

/**
 * 把一条账号安全状态变更落到<b>本实例</b>的安全平面。
 *
 * <h3>为什么必须只有一份实现</h3>
 * <p>封禁在本地只有两个动作：写 {@link SecurityStateCache}，以及关闭该账号在本实例
 * 已建立的长连接。这两个动作有<b>两个调用方</b>：</p>
 * <ul>
 *   <li>{@link SecurityStateReconciler} —— 兜底路径，按 {@code change_seq} 增量拉取</li>
 *   <li>{@link SecurityEventOutboxPublisher} —— 快路径，消费 {@code security_event_outbox}</li>
 * </ul>
 * <p>如果两边各写一遍"标记 + closeAll"，把投递换成 Redis PubSub 时就会有人只改
 * 一边：结果是封禁在快路径上能标记但不断连，或者在兜底路径上能断连但漏标记。
 * 两条路径都"看起来在工作"，缺陷只在多实例 + 已建立连接时暴露。</p>
 *
 * <h3>幂等性</h3>
 * <p>本方法幂等，且<b>只在状态真正翻转时才关闭连接</b>：重复投递同一条事件不会重复
 * 关闭（{@code ConnectionRegistry.closeAll} 也会在句柄集合为空时返回 0）。
 * 这是"至少一次投递"能安全成立的前提 —— 见
 * {@link com.xander.lab.identity.infrastructure.SecurityEventOutboxMapper#claimDue}。</p>
 *
 * <h3>版本单调</h3>
 * <p>封禁方向带一道版本闸门：仅当 {@code incoming.securityVersion > cached.securityVersion}
 * 时才应用。它防的是"一条迟到的重放把缓存里的版本号改回旧值" —— 版本号被
 * {@code admin_ban_*} 指标用来按版本下钻，回退会让指标读到过期版本。</p>
 * <p><b>解封方向刻意没有闸门</b>：缓存只为被封禁账号保存版本号，解封即从缓存移除，
 * 所以解封时根本<b>没有</b>可比较的 cached version。给解封也加闸门需要为每个曾经
 * 出现过的账号常驻一个版本号（内存随历史账号数增长），代价与收益不成比例。
 * 解封方向的顺序性由 {@code change_seq} 保证（见
 * {@link SecurityStateBootstrap} 里对"分配顺序即提交顺序"的论证）。</p>
 * <p>⚠️ 已知残留：一条<b>迟到且版本更低的封禁事件</b>，若该账号此刻在缓存里
 * <b>不存在</b>（已被解封），闸门无法拒绝它，账号会被重新封禁。这需要为"已解封账号"
 * 也保留版本号才能修，属独立改动，已登记在「已知缺口」表。</p>
 *
 * <h3>本实例语义</h3>
 * <p>本方法<b>只影响调用方所在实例</b>。跨实例传播不是它的职责：由每个实例
 * 自己的 reconciler 在 1.5 秒内收敛，多实例部署时改由 Redis PubSub
 * 广播。因此本类不持有任何"全局"状态，也不得被赋予广播语义。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityStateApplier {

    /**
     * 关闭长连接时给客户端的关闭原因。
     *
     * <p>是 {@link ConnectionRegistry#REASON_ACCOUNT_DISABLED} 的别名：词汇由共享内核
     * 拥有，这样 automation / studio 的 SSE 宿主不必为了一个字符串常量反向依赖
     * identity。</p>
     */
    public static final String CLOSE_REASON_ACCOUNT_DISABLED =
            ConnectionRegistry.REASON_ACCOUNT_DISABLED;

    private final SecurityStateCache securityStateCache;
    private final ConnectionRegistry connectionRegistry;

    /**
     * 应用一条状态变更。
     *
     * @param accountId       目标账号；不允许为空（调用方来自 {@code NOT NULL} 主键或自产事件）
     * @param disabled        true=封禁，false=解封
     * @param securityVersion SOT 上的安全版本号，仅封禁时被记录
     * @return 本次调用造成的实际变化；调用方据此累计日志与指标
     * @throws IllegalArgumentException accountId 为空
     */
    public Outcome apply(Long accountId, boolean disabled, long securityVersion) {
        return apply(accountId, disabled, securityVersion, 0L);
    }

    /**
     * 应用带 SOT 来源时间的状态变更，用于计算封禁传播与流断开延迟。
     * 来源时间为 0 时保持 focused test / 旧调用方的无时间语义。
     */
    public Outcome apply(Long accountId, boolean disabled, long securityVersion,
                         long sourceEpochMillis) {
        if (accountId == null) {
            // 不能静默当成"无需处理"：那会让一条畸形事件变成永久漏掉的封禁。
            throw new IllegalArgumentException("accountId 不能为空");
        }
        boolean wasDisabled = securityStateCache.isDisabled(accountId);

        if (!disabled) {
            securityStateCache.markEnabled(accountId);
            // 解封不恢复任何已撤销的刷新会话，也不重连任何连接 —— 解封不等于免登录。
            return wasDisabled ? Outcome.NEWLY_ENABLED : Outcome.NONE;
        }

        // 版本闸门：仅当 incoming > cached 时才应用。
        OptionalLong cachedVersion = securityStateCache.versionOf(accountId);
        if (cachedVersion.isPresent() && cachedVersion.getAsLong() >= securityVersion) {
            // 迟到的一次重放：缓存里已经是更新（或同版本）的状态。不改版本号、不重复断连。
            return Outcome.NONE;
        }

        securityStateCache.markDisabled(accountId, securityVersion);
        securityStateCache.recordPropagationLatency(sourceEpochMillis);
        if (wasDisabled) {
            // 已经是封禁态：不重复断连。重复投递到这里必须是无害的。
            return Outcome.NONE;
        }
        int closed = connectionRegistry.closeAll(accountId, CLOSE_REASON_ACCOUNT_DISABLED);
        if (closed > 0) {
            securityStateCache.recordStreamDisconnectLatency(sourceEpochMillis);
            // 手段 A（主要手段）：主动断开已建立的长连接。
            // 手段 B（每次发送前 O(1) 校验）是兜底，见 SSE / WS 的发送边界。
            log.warn("[Security] 封禁生效，关闭已建立连接 account={} closed={}", accountId, closed);
        }
        return new Outcome(true, false, closed);
    }

    /**
     * 一次应用的结果。
     *
     * @param newlyDisabled      本次把账号从"未封禁"翻转为"封禁"
     * @param newlyEnabled       本次把账号从"封禁"翻转为"未封禁"
     * @param closedConnections  本次实际关闭的连接数（仅封禁翻转时可能非 0）
     */
    public record Outcome(boolean newlyDisabled, boolean newlyEnabled, int closedConnections) {

        /** 状态未发生翻转（重复投递、或本来就是目标态）。 */
        static final Outcome NONE = new Outcome(false, false, 0);

        /** 解封翻转；解封不关闭任何连接，所以第三项恒为 0。 */
        static final Outcome NEWLY_ENABLED = new Outcome(false, true, 0);
    }
}

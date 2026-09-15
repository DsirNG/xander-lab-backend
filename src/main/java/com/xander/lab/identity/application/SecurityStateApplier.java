package com.xander.lab.identity.application;

import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityStateCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 把一条账号安全状态变更落到<b>本实例</b>的安全平面（V9 §27 / §28.2 / §48）。
 *
 * <h3>为什么必须只有一份实现</h3>
 * <p>封禁在本地只有两个动作：写 {@link SecurityStateCache}，以及关闭该账号在本实例
 * 已建立的长连接。这两个动作有<b>两个调用方</b>：</p>
 * <ul>
 *   <li>{@link SecurityStateReconciler} —— 兜底路径，按 {@code change_seq} 增量拉取</li>
 *   <li>{@link SecurityEventOutboxPublisher} —— 快路径，消费 {@code security_event_outbox}</li>
 * </ul>
 * <p>如果两边各写一遍"标记 + closeAll"，Wave 1 把投递换成 Redis PubSub 时就会有人只改
 * 一边：结果是封禁在快路径上能标记但不断连，或者在兜底路径上能断连但漏标记。
 * 两条路径都"看起来在工作"，缺陷只在多实例 + 已建立连接时暴露。</p>
 *
 * <h3>幂等性</h3>
 * <p>本方法幂等，且<b>只在状态真正翻转时才关闭连接</b>：重复投递同一条事件不会重复
 * 关闭（{@code ConnectionRegistry.closeAll} 也会在句柄集合为空时返回 0）。
 * 这是"至少一次投递"能安全成立的前提 —— 见
 * {@link com.xander.lab.identity.infrastructure.SecurityEventOutboxMapper#claimDue}。</p>
 *
 * <h3>本实例语义（Wave 0A 的能力边界）</h3>
 * <p>本方法<b>只影响调用方所在实例</b>。跨实例传播不是它的职责：Wave 0A 由每个实例
 * 自己的 reconciler 在 1.5 秒内收敛（V9 §28.3 的 5 秒预算内），Wave 1 起由 Redis PubSub
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
     * identity（V9 §21 共享内核边界）。</p>
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

        securityStateCache.markDisabled(accountId, securityVersion);
        if (wasDisabled) {
            // 已经是封禁态：不重复断连。重复投递到这里必须是无害的。
            return Outcome.NONE;
        }
        int closed = connectionRegistry.closeAll(accountId, CLOSE_REASON_ACCOUNT_DISABLED);
        if (closed > 0) {
            // V9 §28.2 手段 A（主要手段）：主动断开已建立的长连接。
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

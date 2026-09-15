package com.xander.lab.identity.application;

import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityStateCache;
import com.xander.lab.identity.domain.AccountSecurityState;
import com.xander.lab.identity.infrastructure.AccountSecurityStateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 安全状态对账（V9 §27 / §69 / §70 / §52）。
 *
 * <pre>
 * execution_scope:      per_instance
 * singleton_strategy:   none
 * reconcile interval:   1~2 秒
 * </pre>
 *
 * <p><b>禁止使用全局 ShedLock。</b>原因（V9 §69）：全局锁会让 N-1 个实例的本地缓存
 * 永不刷新，而验收指标（"不重复执行"）<b>仍然通过</b> —— 这是一个不会失败的失败。</p>
 *
 * <p>本组件同时是 §52 判定规则的落地：<b>任一当前部署单元，凡其承载的业务域中存在
 * SSE/WS，都必须维护 Security Local Cache 并在 Wave 2 Staging 中验证封禁生效。</b>
 * 当前宿主清单：</p>
 *
 * <pre>
 * Wave 1 : dinqor-gateway, dinqor-core-host
 * Wave 2 : + dinqor-agent, dinqor-content-automation
 * Wave 3+: + dinqor-studio
 * </pre>
 *
 * <p>注意 core-host 必须在清单里：Wave 2 之后、Wave 3 之前，studio 的 Img2Three SSE
 * 仍由 core-host 承载。</p>
 *
 * <h3>5 秒预算（V9 §28.3）</h3>
 * <pre>
 * 事件产生 → 到达宿主服务      预算 &lt;= 2 秒   ← 由本类的 reconcile interval 保证
 * 宿主本地标记 denied          预算 &lt;= 0.1 秒
 * 遍历并关闭该 account 连接    预算 &lt;= 1 秒
 * 余量                          &gt;= 1.9 秒
 * </pre>
 * <p>所以 reconcile interval <b>必须 &lt;= 2 秒</b>；调整它必须同步复核该 SLO。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityStateReconciler {

    /**
     * 关闭长连接时给客户端的关闭原因。
     *
     * <p>是 {@link ConnectionRegistry#REASON_ACCOUNT_DISABLED} 的别名：
     * 词汇由共享内核拥有，这样 automation / studio 的 SSE 宿主不必为了一个字符串
     * 常量反向依赖 identity（§21 共享内核边界）。</p>
     */
    public static final String CLOSE_REASON_ACCOUNT_DISABLED =
            ConnectionRegistry.REASON_ACCOUNT_DISABLED;

    private final AccountSecurityStateMapper securityStateMapper;
    private final SecurityStateCache securityStateCache;
    private final ConnectionRegistry connectionRegistry;
    private final RefreshTokenProperties properties;

    /**
     * 增量拉取安全状态变更并收敛本地缓存。
     *
     * <p>固定延迟调度：上一次执行结束后再等 interval，避免慢查询把调度器压垮。</p>
     */
    @Scheduled(fixedDelayString = "${auth.refresh.security-reconcile-interval-ms:1500}")
    public void reconcile() {
        try {
            long cursor = securityStateCache.lastSeenSeq();
            List<AccountSecurityState> changes = securityStateMapper.pullChangesAfter(
                    cursor, properties.getSecurityReconcileBatchSize());
            if (changes.isEmpty()) {
                securityStateCache.markSynced(System.currentTimeMillis());
                return;
            }

            int newlyDisabled = 0;
            int newlyEnabled = 0;
            long maxSeq = cursor;
            for (AccountSecurityState state : changes) {
                Long accountId = state.getAccountId();
                long version = state.getSecurityVersion() == null ? 0L : state.getSecurityVersion();
                boolean wasDisabled = securityStateCache.isDisabled(accountId);
                if (state.isDisabled()) {
                    securityStateCache.markDisabled(accountId, version);
                    if (!wasDisabled) {
                        newlyDisabled++;
                        // V9 §28.2 手段 A（主要手段）：主动断开已建立的长连接。
                        // 手段 B（每次发送前 O(1) 校验）是兜底，见 SSE / WS 的发送边界。
                        int closed = connectionRegistry.closeAll(
                                accountId, CLOSE_REASON_ACCOUNT_DISABLED);
                        if (closed > 0) {
                            log.warn("[Security] 封禁生效，关闭已建立连接 account={} closed={}",
                                    accountId, closed);
                        }
                    }
                } else if (wasDisabled) {
                    securityStateCache.markEnabled(accountId);
                    newlyEnabled++;
                }
                if (state.getChangeSeq() != null) {
                    maxSeq = Math.max(maxSeq, state.getChangeSeq());
                }
            }
            securityStateCache.advanceCursor(maxSeq);
            securityStateCache.markSynced(System.currentTimeMillis());
            if (newlyDisabled > 0 || newlyEnabled > 0) {
                log.info("[Security] reconcile 收敛：新增封禁={} 解封={} 游标={}",
                        newlyDisabled, newlyEnabled, maxSeq);
            }
        } catch (RuntimeException ex) {
            // 单次失败不能中断调度；下一轮会重试同一游标区间（幂等）。
            log.warn("[Security] reconcile 失败，将在下一轮重试: {}", ex.getMessage());
        }
    }

    /**
     * 全量对账兜底。
     *
     * <p>实例长时间离线后本地游标可能落后于已清理的历史区间，增量拉取会永久漏掉
     * 那一段。低频全量刷新保证最终收敛（V9 §27「每实例 reconcile 最终收敛」）。</p>
     */
    @Scheduled(fixedDelayString = "${auth.refresh.security-full-reconcile-interval-ms:300000}")
    public void fullReconcile() {
        try {
            Map<Long, Long> disabled = new HashMap<>();
            for (AccountSecurityState state : securityStateMapper.listDisabled()) {
                disabled.put(state.getAccountId(),
                        state.getSecurityVersion() == null ? 0L : state.getSecurityVersion());
            }
            securityStateCache.replaceAll(disabled);
            securityStateCache.markSynced(System.currentTimeMillis());
            log.debug("[Security] 全量对账完成，封禁账号数={}", disabled.size());
        } catch (RuntimeException ex) {
            log.warn("[Security] 全量对账失败: {}", ex.getMessage());
        }
    }
}

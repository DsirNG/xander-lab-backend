package com.xander.lab.identity.application;

import com.xander.lab.identity.domain.AccountSecurityState;
import com.xander.lab.identity.domain.AuthRefreshSession;
import com.xander.lab.identity.domain.SecurityEventOutboxEntry;
import com.xander.lab.identity.infrastructure.AccountSecurityStateMapper;
import com.xander.lab.identity.infrastructure.SecurityEventOutboxMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 账号安全状态写入方：管理员封禁/解封的唯一事务边界（V9 §26 / §27 / §48）。
 *
 * <h3>为什么必须是一个事务</h3>
 * <p>V9 §26 要求封禁<b>原子地</b>完成四件事：</p>
 * <ol>
 *   <li>写 {@code sys_user.status}（由调用方在同一个事务里完成）</li>
 *   <li>撤销该账号全部 Refresh Session</li>
 *   <li>提升 {@code account_security_state.security_version} 并推进 {@code change_seq}</li>
 *   <li>写一条安全事件到 {@code security_event_outbox}</li>
 * </ol>
 *
 * <p>第 5、6 件事 —— "把 accountId 推进每个宿主的本地 deny cache" 与
 * "关闭该账号已建立的 SSE / WS" —— <b>不可能</b>在事务内完成，因为它们要触碰
 * 别的进程。所以事件先在事务内落库、提交后再投递，这就是封禁能扛住
 * "提交后立刻崩溃"的原因（V9 §27）。</p>
 *
 * <h3>为什么封禁和解封不对称</h3>
 * <p>封禁要撤销全部刷新会话；解封<b>不恢复</b>任何已撤销的会话 ——
 * 解封不等于免登录，用户仍须重新登录一次。这不是遗漏，是取舍。</p>
 *
 * <h3>幂等与自愈</h3>
 * <p>{@link #applyState} 是幂等的：对同一个目标状态重复调用只会推进
 * {@code security_version} 与 {@code change_seq}，不产生非法中间态。
 * 因此调用方不必先读一遍 {@code account_security_state} —— 历史数据、
 * 上一次崩溃、手工改库造成的 {@code sys_user} 与安全状态不一致，
 * 都会被下一次管理员操作顺手收敛掉。</p>
 */
@Slf4j
@Service
public class AccountSecurityStateService {

    /** {@code reason} 列的宽度是 255，留出前缀与截断余量。 */
    private static final int MAX_REASON_LENGTH = 200;

    private final AccountSecurityStateMapper stateMapper;
    private final SecurityEventOutboxMapper outboxMapper;
    private final RefreshTokenService refreshTokenService;
    private final Clock clock;

    @Autowired
    public AccountSecurityStateService(AccountSecurityStateMapper stateMapper,
                                       SecurityEventOutboxMapper outboxMapper,
                                       RefreshTokenService refreshTokenService) {
        this(stateMapper, outboxMapper, refreshTokenService, Clock.systemDefaultZone());
    }

    /** 供测试注入固定时钟，让 {@code disabled_at} 可被确定性地断言。 */
    AccountSecurityStateService(AccountSecurityStateMapper stateMapper,
                                SecurityEventOutboxMapper outboxMapper,
                                RefreshTokenService refreshTokenService,
                                Clock clock) {
        this.stateMapper = stateMapper;
        this.outboxMapper = outboxMapper;
        this.refreshTokenService = refreshTokenService;
        this.clock = clock;
    }

    /**
     * 把账号安全状态收敛到目标值，并在同一事务内撤销会话、写安全事件。
     *
     * @param accountId 目标账号
     * @param disabled  true=封禁，false=解封
     * @param reason    管理员操作说明；会写进 SOT 与事件负载，供审计与指标下钻
     * @return 写入后的安全状态（含权威的 {@code securityVersion} 与 {@code changeSeq}）
     */
    @Transactional
    public AccountSecurityState applyState(Long accountId, boolean disabled, String reason) {
        if (accountId == null) {
            throw new IllegalArgumentException("accountId 不能为空");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        String trimmedReason = truncate(reason);

        // 先分配游标再写业务行：两个语句在同一事务、同一连接上执行，
        // LAST_INSERT_ID() 才能取回刚分配的值。
        stateMapper.allocateChangeSeq();
        Long changeSeq = stateMapper.currentChangeSeq();
        if (changeSeq == null) {
            // 分配不到游标就不能继续：没有游标的行会被 reconciler 永久漏掉。
            throw new IllegalStateException("未能分配 security change_seq，拒绝写入不一致的安全状态");
        }

        stateMapper.upsertState(accountId, disabled ? 1 : 0, changeSeq, now, trimmedReason);

        int revoked = 0;
        if (disabled) {
            // V9 §26 第 2 条：封禁必须同时撤销全部刷新会话。
            // 这是按索引的一条 UPDATE，不像遗留 Redis 实现那样随时间单调恶化（V9 §56）。
            revoked = refreshTokenService.revokeAllForAccount(
                    accountId, AuthRefreshSession.REASON_ADMIN_DISABLE);
        }

        // 回读权威值：security_version 由 SQL 侧 +1 得出，Java 侧不知道新值，
        // 而事件负载必须带上它（§87 的 admin_ban_* 指标要按版本下钻）。
        AccountSecurityState state = stateMapper.selectById(accountId);
        if (state == null) {
            // 刚 upsert 过却读不到，说明事务或权限有问题，必须炸掉而不是静默继续。
            throw new IllegalStateException("account_security_state 写入后回读失败 account=" + accountId);
        }

        writeOutboxEvent(state, disabled, trimmedReason, now);

        if (disabled) {
            log.warn("[Security] 账号封禁已落库 account={} version={} changeSeq={} 撤销刷新会话={} reason={}",
                    accountId, state.getSecurityVersion(), changeSeq, revoked, trimmedReason);
        } else {
            log.info("[Security] 账号解封已落库 account={} version={} changeSeq={} reason={}",
                    accountId, state.getSecurityVersion(), changeSeq, trimmedReason);
        }
        return state;
    }

    /**
     * 写安全事件（V9 §27 的事务性发件箱）。
     *
     * <p>事件只负责"通知"，不负责"状态"：投递失败不会让封禁失效，
     * 因为 {@code account_security_state} 已经是 SOT，而每个实例还有
     * 按 {@code change_seq} 的增量对账兜底。</p>
     */
    private void writeOutboxEvent(AccountSecurityState state, boolean disabled,
                                  String reason, LocalDateTime now) {
        SecurityEventOutboxEntry entry = new SecurityEventOutboxEntry();
        entry.setAggregateType("ACCOUNT");
        entry.setAggregateId(String.valueOf(state.getAccountId()));
        entry.setEventType(disabled
                ? SecurityEventOutboxEntry.TYPE_ACCOUNT_DISABLED
                : SecurityEventOutboxEntry.TYPE_ACCOUNT_ENABLED);
        entry.setPayload("{\"accountId\":" + state.getAccountId()
                + ",\"securityVersion\":" + state.getSecurityVersion()
                + ",\"changeSeq\":" + state.getChangeSeq()
                + ",\"reason\":" + jsonString(reason)
                + ",\"occurredAt\":\"" + now + "\"}");
        entry.setStatus(SecurityEventOutboxEntry.STATUS_PENDING);
        entry.setAttempts(0);
        entry.setNextAttemptAt(now);
        outboxMapper.insert(entry);
    }

    private String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_REASON_LENGTH
                ? trimmed.substring(0, MAX_REASON_LENGTH) : trimmed;
    }

    /**
     * 最小 JSON 字符串转义。
     *
     * <p>这里刻意不引入 ObjectMapper：payload 的字段集合是固定的，
     * 而 reason 来自管理员输入 —— 只要保证引号与反斜杠被转义，
     * 就不会因为一个引号让整条事件变成非法 JSON。</p>
     */
    private String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }
}

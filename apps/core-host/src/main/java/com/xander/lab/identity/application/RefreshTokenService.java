package com.xander.lab.identity.application;

import com.xander.lab.identity.domain.AccountSecurityState;
import com.xander.lab.identity.domain.AuthRefreshSession;
import com.xander.lab.identity.domain.AuthRefreshTokenHistory;
import com.xander.lab.identity.domain.SecurityEventOutboxEntry;
import com.xander.lab.identity.domain.User;
import com.xander.lab.identity.infrastructure.AuthRefreshSessionMapper;
import com.xander.lab.identity.infrastructure.AuthRefreshTokenHistoryMapper;
import com.xander.lab.identity.infrastructure.SecurityEventOutboxMapper;
import com.xander.lab.identity.infrastructure.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 新 Refresh 协议。
 *
 * <p>目标模型：<b>短 Access JWT + MySQL Refresh Session + Refresh Rotation +
 * 幂等 Grace + 有界 Reuse Detection</b>。</p>
 *
 * <h3>为什么不能沿用旧模型</h3>
 * <p>旧的 refresh 是纯无状态 JWT（{@code AuthService.refresh} 只验签名 +
 * {@code type} + 用户 status，不查任何存储），logout 只删 access 键，
 * 所以<b>登出后 30 天内仍可用 refresh token 换出全新令牌对</b>。</p>
 *
 * <h3>Reuse Detection 四分支（缺一不可）</h3>
 * <table>
 *   <tr><th>#</th><th>条件</th><th>判定</th><th>动作</th></tr>
 *   <tr><td>1</td><td>tokenId == current 且 hash 匹配</td><td>正常轮换</td>
 *       <td>生成 RT(n+1)，写 history，返回新对</td></tr>
 *   <tr><td>2</td><td>history hash 匹配 且 now &lt;= grace</td><td>幂等重放</td>
 *       <td>返回<b>同一个</b> successor，<b>不</b> revoke family</td></tr>
 *   <tr><td>3</td><td>history hash 匹配 且 now &gt; grace 且仍在 retained_until 内</td>
 *       <td>真正的 Reuse</td><td>revoke family + security event + 401</td></tr>
 *   <tr><td>4</td><td>不在 current，也不在 history</td><td>无效 token</td>
 *       <td>401，<b>不</b> revoke family</td></tr>
 * </table>
 *
 * <p>第 4 行是<b>安全设计，不是可选优化</b>：{@code sid} 位于明文前缀，
 * 知道 sid 不是假设。若按"校验失败即可疑"处理，攻击者构造
 * {@code rt_<已知sid>.<随机>.<随机>} 反复提交就能让该会话族被反复撤销、
 * 用户被反复强制登出。</p>
 */
@Slf4j
@Service
public class RefreshTokenService {

    /** 轮换结果类别。 */
    public enum Outcome {
        /** 分支 1：正常轮换，返回全新的 refresh token */
        ROTATED,
        /** 分支 2：grace 内幂等重放，返回与首次相同的 successor */
        REPLAYED,
        /** 分支 3：真正的 Reuse，会话族已撤销 */
        REUSE_DETECTED,
        /** 分支 4 / 会话不可用：无效 token，未撤销任何东西 */
        INVALID,
        /** 会话已被撤销或过期 */
        SESSION_REVOKED,
        /** 账号被封禁，会话不可恢复 */
        ACCOUNT_DISABLED
    }

    /**
     * 轮换结果。
     *
     * @param outcome      结果类别
     * @param refreshToken 新的 refresh token 明文；仅 ROTATED / REPLAYED 非空
     * @param sid          会话标识
     * @param accountId    账号 ID
     * @param detail       便于日志与排查的说明，不对外暴露
     */
    public record Rotation(Outcome outcome, String refreshToken, String sid,
                           Long accountId, String detail) {

        public boolean isSuccess() {
            return outcome == Outcome.ROTATED || outcome == Outcome.REPLAYED;
        }

        /** 客户端是否应当清除本地凭据并跳登录页。 */
        public boolean isUnrecoverable() {
            return outcome == Outcome.REUSE_DETECTED
                    || outcome == Outcome.ACCOUNT_DISABLED
                    || outcome == Outcome.SESSION_REVOKED;
        }
    }

    /** 新建会话的产物。 */
    public record IssuedSession(String sid, String familyId, String refreshToken,
                                LocalDateTime expiresAt) {
    }

    private final AuthRefreshSessionMapper sessionMapper;
    private final AuthRefreshTokenHistoryMapper historyMapper;
    private final SecurityEventOutboxMapper outboxMapper;
    private final UserMapper userMapper;
    private final RefreshTokenProperties properties;
    private final SuccessorTokenCipher cipher;
    private final RefreshMetrics metrics;
    private final Clock clock;

    @Autowired
    public RefreshTokenService(AuthRefreshSessionMapper sessionMapper,
                               AuthRefreshTokenHistoryMapper historyMapper,
                               SecurityEventOutboxMapper outboxMapper,
                               UserMapper userMapper,
                               RefreshTokenProperties properties,
                               SuccessorTokenCipher cipher,
                               RefreshMetrics metrics) {
        this(sessionMapper, historyMapper, outboxMapper, userMapper, properties, cipher,
                metrics, Clock.systemDefaultZone());
    }

    /** 供测试注入固定时钟，让 grace / retention 的边界可被确定性地断言。 */
    RefreshTokenService(AuthRefreshSessionMapper sessionMapper,
                        AuthRefreshTokenHistoryMapper historyMapper,
                        SecurityEventOutboxMapper outboxMapper,
                        UserMapper userMapper,
                        RefreshTokenProperties properties,
                        SuccessorTokenCipher cipher,
                        Clock clock) {
        this(sessionMapper, historyMapper, outboxMapper, userMapper, properties, cipher,
                null, clock);
    }

    RefreshTokenService(AuthRefreshSessionMapper sessionMapper,
                        AuthRefreshTokenHistoryMapper historyMapper,
                        SecurityEventOutboxMapper outboxMapper,
                        UserMapper userMapper,
                        RefreshTokenProperties properties,
                        SuccessorTokenCipher cipher,
                        RefreshMetrics metrics,
                        Clock clock) {
        this.sessionMapper = sessionMapper;
        this.historyMapper = historyMapper;
        this.outboxMapper = outboxMapper;
        this.userMapper = userMapper;
        this.properties = properties;
        this.cipher = cipher;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** 特性是否开启。关闭时调用方必须继续走旧的无状态 refresh 路径。 */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    // -----------------------------------------------------------------------
    // 签发
    // -----------------------------------------------------------------------

    /**
     * 登录时新建一个刷新会话。
     *
     * <p>一个登录 = 一个 sid = 一个 family。轮换原地修改这一行，
     * 所以 family 与 session 目前是 1:1；保留 {@code family_id} 是为了将来
     * 「同设备重新登录继承同一族」这类分组语义。</p>
     */
    @Transactional
    public IssuedSession issue(Long accountId) {
        LocalDateTime now = now();
        String sid = RefreshTokenFormat.newId();
        String familyId = RefreshTokenFormat.newId();
        String tokenId = RefreshTokenFormat.newId();
        String secret = RefreshTokenFormat.newSecret();

        AuthRefreshSession session = new AuthRefreshSession();
        session.setSid(sid);
        session.setAccountId(accountId);
        session.setFamilyId(familyId);
        session.setCurrentTokenId(tokenId);
        session.setCurrentTokenHash(RefreshTokenFormat.hash(tokenId, secret));
        session.setGeneration(0);
        session.setStatus(AuthRefreshSession.STATUS_ACTIVE);
        session.setIssuedAt(now);
        session.setExpiresAt(now.plusSeconds(properties.getSessionTtlSeconds()));
        sessionMapper.insert(session);

        log.info("[Refresh] 新建刷新会话 sid={} account={}", sid, accountId);
        return new IssuedSession(sid, familyId,
                RefreshTokenFormat.format(sid, tokenId, secret), session.getExpiresAt());
    }

    // -----------------------------------------------------------------------
    // 轮换 / 重放 / 盗用判定
    // -----------------------------------------------------------------------

    /**
     * 消费一个 refresh token。
     *
     * <p>整体在一个事务里执行；会话行用 {@code SELECT ... FOR UPDATE} 锁住，
     * 避免多标签页并发刷新各自生成一个 successor（那会把正常使用误判成 theft）。</p>
     */
    @Transactional
    public Rotation rotate(String rawRefreshToken) {
        long startedAt = System.nanoTime();
        try {
            Rotation rotation = rotateInternal(rawRefreshToken);
            if (metrics != null) {
                metrics.recordRotation(rotation.outcome(), System.nanoTime() - startedAt);
            }
            return rotation;
        } catch (DataAccessException ex) {
            if (metrics != null) {
                metrics.recordDatabaseError(System.nanoTime() - startedAt);
            }
            throw ex;
        }
    }

    private Rotation rotateInternal(String rawRefreshToken) {
        LocalDateTime now = now();

        Optional<RefreshTokenFormat.Parsed> parsed = RefreshTokenFormat.parse(rawRefreshToken);
        if (parsed.isEmpty()) {
            // 形状不符：分支 4 的极端情况，绝不 revoke
            return new Rotation(Outcome.INVALID, null, null, null, "malformed token");
        }
        RefreshTokenFormat.Parsed token = parsed.get();

        AuthRefreshSession session = sessionMapper.selectForUpdate(token.sid());
        if (session == null) {
            // 未知 sid：分支 4
            return new Rotation(Outcome.INVALID, null, token.sid(), null, "unknown sid");
        }

        User user = userMapper.selectById(session.getAccountId());
        if (user == null) {
            return new Rotation(Outcome.INVALID, null, session.getSid(), session.getAccountId(),
                    "account missing");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            // 403 ACCOUNT_DISABLED → 会话不可恢复 → forceLoggedOut
            return new Rotation(Outcome.ACCOUNT_DISABLED, null, session.getSid(),
                    session.getAccountId(), "account disabled");
        }
        if (!session.isRotatable(now)) {
            return new Rotation(Outcome.SESSION_REVOKED, null, session.getSid(),
                    session.getAccountId(),
                    "session " + session.getStatus());
        }

        String presentedHash = RefreshTokenFormat.hash(token.tokenId(), token.secret());

        // ---------- 分支 1：当前 token ----------
        if (token.tokenId().equals(session.getCurrentTokenId())) {
            if (!RefreshTokenFormat.hashMatches(session.getCurrentTokenHash(), presentedHash)) {
                // tokenId 对但 secret 错：按垃圾 token 处理，不 revoke（防 DoS）
                return new Rotation(Outcome.INVALID, null, session.getSid(),
                        session.getAccountId(), "secret mismatch on current token");
            }
            return doRotate(session, token, now);
        }

        // ---------- 不在 current，查 history ----------
        AuthRefreshTokenHistory history = historyMapper.selectById(token.tokenId());
        if (history == null || !session.getSid().equals(history.getSid())) {
            // 分支 4：不在 current 也不在 history（随机垃圾 token）
            // ⇒ 401 INVALID_REFRESH_TOKEN，且**不 revoke family**
            return new Rotation(Outcome.INVALID, null, session.getSid(),
                    session.getAccountId(), "token not in current nor history");
        }
        if (!RefreshTokenFormat.hashMatches(history.getTokenHash(), presentedHash)) {
            return new Rotation(Outcome.INVALID, null, session.getSid(),
                    session.getAccountId(), "secret mismatch on historical token");
        }

        // ---------- 分支 2：grace 内幂等重放 ----------
        if (history.isWithinGrace(now)) {
            if (!history.isCiphertextReadable(now)) {
                // 逻辑上已过期或密文缺失：无法返回同一个 successor。
                // 仍属"不可恢复"之外的情况 —— 保守地按无效处理，不 revoke。
                log.warn("[Refresh] grace 内重放但 successor 密文不可用 sid={} tokenId={}",
                        session.getSid(), token.tokenId());
                return new Rotation(Outcome.INVALID, null, session.getSid(),
                        session.getAccountId(), "grace replay without readable successor");
            }
            String successor = cipher.decrypt(history.getSuccessorTokenCiphertext());
            if (successor == null) {
                return new Rotation(Outcome.INVALID, null, session.getSid(),
                        session.getAccountId(), "successor ciphertext unreadable");
            }
            log.debug("[Refresh] grace 内幂等重放 sid={} tokenId={}", session.getSid(), token.tokenId());
            return new Rotation(Outcome.REPLAYED, successor, session.getSid(),
                    session.getAccountId(), "grace replay");
        }

        // ---------- 分支 3：真正的 Reuse ----------
        if (history.isWithinRetention(now)) {
            int revoked = sessionMapper.revokeFamily(session.getFamilyId(),
                    AuthRefreshSession.REASON_REUSE_DETECTED, now);
            historyMapper.markReuseDetected(token.tokenId(), now);
            writeReuseSecurityEvent(session, token.tokenId());
            log.warn("[Refresh] 检测到 refresh token 重用：sid={} account={} tokenId={} 撤销会话数={}",
                    session.getSid(), session.getAccountId(), token.tokenId(), revoked);
            return new Rotation(Outcome.REUSE_DETECTED, null, session.getSid(),
                    session.getAccountId(), "reuse detected");
        }

        // ---------- 超过 retention：仍无效，但不再报 theft ----------
        // 更老的 token 不触发 family theft alarm，避免把陈旧客户端
        // 的正常重试升级成安全事件。
        return new Rotation(Outcome.INVALID, null, session.getSid(),
                session.getAccountId(), "beyond retention window");
    }

    /** 分支 1：正常轮换。 */
    private Rotation doRotate(AuthRefreshSession session, RefreshTokenFormat.Parsed token,
                              LocalDateTime now) {
        String newTokenId = RefreshTokenFormat.newId();
        String newSecret = RefreshTokenFormat.newSecret();
        String newRawToken = RefreshTokenFormat.format(session.getSid(), newTokenId, newSecret);
        LocalDateTime graceExpiresAt = now.plusSeconds(properties.getGraceSeconds());

        AuthRefreshTokenHistory history = new AuthRefreshTokenHistory();
        history.setTokenId(token.tokenId());
        history.setSid(session.getSid());
        history.setTokenHash(RefreshTokenFormat.hash(token.tokenId(), token.secret()));
        history.setGeneration(session.getGeneration());
        history.setUsedAt(now);
        history.setSuccessorTokenId(newTokenId);
        history.setGraceExpiresAt(graceExpiresAt);
        // retained_until 必须在写 history 行时显式写入 now()+retention，
        // 不得留空、不得依赖后续任务回填。
        history.setRetainedUntil(now.plusHours(properties.getRetentionHours()));
        if (cipher.isActive()) {
            history.setSuccessorTokenCiphertext(cipher.encrypt(newRawToken));
            // 密文在逻辑 grace 窗口内必须保持可恢复，所以窗口不短于 grace。
            // 物理清空由清理任务在 grace 之后异步执行，两者解耦。
            history.setSuccessorCiphertextExpiresAt(graceExpiresAt);
        }
        historyMapper.insert(history);

        AuthRefreshSession update = new AuthRefreshSession();
        update.setSid(session.getSid());
        update.setCurrentTokenId(newTokenId);
        update.setCurrentTokenHash(RefreshTokenFormat.hash(newTokenId, newSecret));
        update.setGeneration(session.getGeneration() + 1);
        update.setLastRotatedAt(now);
        sessionMapper.updateById(update);

        // 时间上界之外还要有每 sid 条数上界
        historyMapper.pruneGenerationsBeyondBound(session.getSid(),
                properties.getMaxGenerationsPerSid(), now);

        return new Rotation(Outcome.ROTATED, newRawToken, session.getSid(),
                session.getAccountId(), "rotated");
    }

    // -----------------------------------------------------------------------
    // 撤销
    // -----------------------------------------------------------------------

    /**
     * 普通 logout。
     *
     * <p>撤销当前 Refresh Session；客户端删掉本地 Access / Refresh。
     * 当前 Access 默认可存活到 exp，所以普通用户 logout 的最大 Access 窗口
     * 是 {@code <= accessTtl} —— 这是明确取舍，不是缺陷。</p>
     *
     * <p>管理员封禁<b>不能</b>套用这个语义，必须走 deny cache + 长连接关闭。</p>
     */
    @Transactional
    public boolean logout(String rawRefreshToken) {
        Optional<RefreshTokenFormat.Parsed> parsed = RefreshTokenFormat.parse(rawRefreshToken);
        if (parsed.isEmpty()) {
            return false;
        }
        return logoutBySid(parsed.get().sid());
    }

    /** 按 Access JWT 的 sid 撤销当前 Refresh Session。 */
    @Transactional
    public boolean logoutBySid(String sid) {
        if (sid == null || sid.isBlank()) {
            return false;
        }
        int affected = sessionMapper.revokeSession(sid,
                AuthRefreshSession.REASON_LOGOUT, now());
        if (metrics != null) {
            metrics.recordLogout(affected > 0);
        }
        return affected > 0;
    }

    /** 登出全部设备。 */
    @Transactional
    public int logoutAll(Long accountId) {
        return sessionMapper.revokeByAccount(accountId,
                AuthRefreshSession.REASON_LOGOUT_ALL, now());
    }

    /**
     * 管理员封禁 / 改密时撤销该账号全部刷新会话。
     *
     * <p>相比旧的 {@code revokeSessions}（遍历无 TTL 的 Redis Set 逐个 delete），
     * 这是一条按索引更新的语句 —— 旧实现的封禁延迟随时间单调恶化。</p>
     */
    @Transactional
    public int revokeAllForAccount(Long accountId, String reason) {
        return sessionMapper.revokeByAccount(accountId, reason, now());
    }

    /** 写一条 RefreshTokenReuse 安全事件。 */
    private void writeReuseSecurityEvent(AuthRefreshSession session, String tokenId) {
        SecurityEventOutboxEntry entry = new SecurityEventOutboxEntry();
        entry.setAggregateType("ACCOUNT");
        entry.setAggregateId(String.valueOf(session.getAccountId()));
        entry.setEventType(SecurityEventOutboxEntry.TYPE_REFRESH_TOKEN_REUSE);
        entry.setPayload("{\"accountId\":" + session.getAccountId()
                + ",\"sid\":\"" + session.getSid()
                + "\",\"tokenId\":\"" + tokenId + "\"}");
        entry.setStatus(SecurityEventOutboxEntry.STATUS_PENDING);
        entry.setAttempts(0);
        outboxMapper.insertPending(entry);
    }
}

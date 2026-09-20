package com.xander.lab.identity.application;

import com.xander.lab.common.security.SecurityPlaneReadiness;
import com.xander.lab.common.security.SecurityStateCache;
import com.xander.lab.identity.domain.AccountSecurityState;
import com.xander.lab.identity.infrastructure.AccountSecurityStateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 实例启动 Bootstrap（硬性要求）。
 *
 * <p>启动切成九步，本类逐条实现：</p>
 *
 * <pre>
 * 1. readiness = NOT_READY              {@link SecurityPlaneReadiness} 的初始态
 * 2. 读取 high-watermark = E0           见下方「水位为什么从 snapshot 里取」
 * 3. 加载 account_security_state snapshot  {@link AccountSecurityStateMapper#listDisabled()}
 * 4. 建立 LocalSecurityStateCache        {@link SecurityStateCache#replaceAll}
 * 5. replay 事件（游标 &gt; E0）           {@link #replayAfter}
 * 6. 仅当 incoming.version &gt; cached 才应用  {@link SecurityStateApplier#apply} 内的版本闸门
 * 7. cursor 推进到已处理的最大位置        {@link SecurityStateCache#advanceCursor}
 * 8. readiness = READY                  {@link SecurityPlaneReadiness#markReady}
 * 9. 才允许进入服务发现 / 负载均衡        由 {@code GET /health/ready} 承载，见下
 * </pre>
 *
 * <h3>为什么本类必须存在</h3>
 * <p>没有它，实例启动后 {@link SecurityStateCache} 是<b>空的</b>：一个已被封禁的账号
 * 拿着一张仍然有效的 JWT 就能被放行，直到 {@link SecurityStateReconciler} 在 1.5 秒后
 * 第一次收敛。这 1.5 秒里封禁是失效的，而且失败得很安静 —— 这正是风险项
 * （"实例可能在加载封禁状态前接流量"）描述的场景，也是必须禁止的。</p>
 *
 * <h3>⚠️ 第 9 步的强制力来自探针，不来自端口</h3>
 * <p>本类实现为 {@link ApplicationRunner}，它在 Spring 容器 refresh 完成之后运行；
 * 而内嵌 Servlet 容器<b>在 refresh 期间就已经开始监听端口</b>。也就是说，即使有了本类，
 * 在 bootstrap 跑完之前 HTTP 端口照样能建立 TCP 连接 —— "不接流量"只能由
 * <b>负载均衡/服务发现侧的就绪探针</b>来保证。本类提供两样东西让那条路成立：
 * {@link SecurityPlaneReadiness} 这个可查询的状态，以及 {@code GET /health/ready}
 * 这个探针端点（{@code HealthController}）。</p>
 * <p>因此本类<b>不</b>把 {@code /health} 改成就绪语义：{@code /health} 是存活探针
 * （docker-compose 的 healthcheck 正在用它），把它改成"未就绪即 503"会让容器在
 * 启动窗口里被判为不健康并反复重启。</p>
 *
 * <h3>⚠️ 失败即拒绝启动（fail-fast），不是"起来但没就绪"</h3>
 * <p>若 snapshot 读不到（数据库不可用、权限缺失、表结构不对），本类<b>抛出异常</b>，
 * 让 {@code SpringApplication.run} 失败、进程退出。理由：一个 deny cache 读不出来的
 * 实例，"不接流量"必须成立；而当前部署里还没有任何就绪探针接线（见第 9 步），
 * "起来但没就绪"实际上等于"起来就接流量"。等就绪探针接上之后，这条取舍可以再评估。</p>
 *
 * <h3>水位（E0）为什么从 snapshot 的行里取，而不是另发一条查询</h3>
 * <p>snapshot 与 watermark 必须取自<b>同一个一致性点</b>，否则副本延迟或两次
 * 读之间的新封禁会被永久漏掉。本类的做法是：<b>只发一条查询</b>
 * （{@code listDisabled()}），然后把 E0 取为这些行里最大的 {@code change_seq}。
 * 这样"同一一致性点"不是靠事务隔离级别来保证的，而是靠<b>构造</b>保证的 ——
 * 单条 SELECT 本身就是一个一致性点，READ COMMITTED / REPEATABLE READ / 从副本读
 * 都一样成立。</p>
 *
 * <p>为什么这样取出来的 E0 是<b>安全</b>的（可能偏小，但绝不会偏大）：
 * {@code account_security_state} 每个账号只有一行（主键 {@code account_id}），存的是
 * <b>当前状态</b>而不是历史。设某次变更在 snapshot 读之后提交、其 {@code change_seq = c}。
 * 由于 {@code security_state_sequence} 的分配语句是
 * {@code UPDATE ... SET seq = LAST_INSERT_ID(seq + 1)}，它对该单行加<b>排他锁并持有到事务提交</b>，
 * 所以 <b>{@code change_seq} 的分配顺序与提交顺序一致</b>：snapshot 里能看见的每一行
 * 都满足 {@code change_seq < c}。于是
 * {@code E0 = max(可见行的 change_seq) < c}，即那条变更一定落在 {@code change_seq > E0}
 * 的 replay 区间里。<b>偏小只会多 replay 几行（幂等，无害），偏大才会漏封禁。</b></p>
 *
 * <p>代价：如果历史上封禁过很多账号（表里 {@code disabled = 0} 的行不计入 E0），
 * 启动时会多扫一遍这张表。这张表是"每个被处理过的账号一行"的量级，一次启动扫一遍
 * 是可接受的；若将来它涨到需要更紧的水位，正确的改法是<b>在一个显式
 * REPEATABLE READ 事务里</b>同时读 watermark 与 snapshot，而不是退回到两次独立读。</p>
 *
 * <h3>⚠️ 为什么 replay 的数据源是 account_security_state，而不是 security_event_outbox</h3>
 * <p>设计文档声称有一张 {@code security_event} 表作为"Bootstrap 与 reconcile
 * 的取数来源"，但正文里<b>没有</b>这一节，仓库里也没有这张表。现有的
 * {@code security_event_outbox} <b>不能</b>当 replay 源：它是投递机制而不是只追加的事件日志 ——
 * 行会被标成 {@code PUBLISHED}、会退避、会判 {@code DEAD}，而且它由
 * {@code global_once} 的单消费者领取。真正的 SOT 是 {@code account_security_state}
 * 本身（其 {@code change_seq} 就是全局单调变更序号），所以设计文档里的 "eventId" 在本仓库里
 * <b>映射为 {@code change_seq}</b>，replay 就是按 {@code change_seq} 的增量拉取。
 * 该映射与那张表的缺失已登记在「已知缺口」表。</p>
 *
 * <h3>为什么 replay 不会把新状态覆盖回旧状态</h3>
 * <p>版本闸门实现在 {@link SecurityStateApplier#apply}（两条路径唯一的
 * 收口点），本类不重复实现。此外 replay 的查询本身是 {@code ORDER BY change_seq}，
 * 且由上面的提交顺序性质保证有序，所以"顺序乱了"这一前提在本仓库里也不会出现 ——
 * 版本闸门是纵深防御，而不是唯一防线。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityStateBootstrap implements ApplicationRunner {

    private final AccountSecurityStateMapper securityStateMapper;
    private final SecurityStateApplier securityStateApplier;
    private final SecurityStateCache securityStateCache;
    private final SecurityPlaneReadiness readiness;
    private final RefreshTokenProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        long startedAt = System.currentTimeMillis();
        try {
            BootstrapResult result = loadSnapshotAndReplay();
            long now = System.currentTimeMillis();
            // markSynced 不是可选项：ageMillis() 在 lastSuccessfulSyncAt == 0 时返回
            // Long.MAX_VALUE，"stale 即 fail-closed"会把刚启动的实例直接判成
            // 陈旧并拒绝一切请求 —— 一个启动即自我封锁的失败。
            securityStateCache.markSynced(now);
            readiness.markReady(now);
            log.info("[Security] 安全平面 Bootstrap 完成：封禁账号={} 快照水位={} "
                            + "replay={} 条 游标={} 耗时={} ms",
                    securityStateCache.size(), result.watermark(), result.replayed(),
                    securityStateCache.lastSeenSeq(), now - startedAt);
        } catch (RuntimeException ex) {
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            readiness.markFailed(reason);
            log.error("[Security] 安全平面 Bootstrap 失败，拒绝以 NOT_READY 状态提供服务: {}",
                    reason, ex);
            throw new IllegalStateException(
                    "安全平面 Bootstrap 失败，本实例不得进入负载均衡: " + reason, ex);
        }
    }

    /** 一次 Bootstrap 的可观测结果，仅用于完成日志。 */
    private record BootstrapResult(long watermark, long replayed) {
    }

    /**
     * 第 2 / 3 / 4 / 5 / 6 / 7 步。
     *
     * @return 快照水位 E0（replay 的下界）与 replay 行数
     */
    private BootstrapResult loadSnapshotAndReplay() {
        // 第 2 + 3 步：一条 SELECT 同时给出 snapshot 与 watermark。
        Map<Long, Long> snapshot = new HashMap<>();
        long watermark = 0L;
        for (AccountSecurityState state : securityStateMapper.listDisabled()) {
            snapshot.put(state.getAccountId(), versionOf(state));
            if (state.getChangeSeq() != null) {
                watermark = Math.max(watermark, state.getChangeSeq());
            }
        }

        // 第 4 步：建立本地缓存。replaceAll 而不是逐条 markDisabled —— 启动时缓存里
        // 不该有残留，整体替换同时把"上一条 SQL 与这一条 SQL 之间的中间态"排除掉。
        securityStateCache.replaceAll(snapshot);

        // 第 7 步的前半：先把游标推到水位，这样 replay 区间就是 (E0, ...]。
        securityStateCache.advanceCursor(watermark);

        // 第 5 + 6 + 7 步：replay 期间新提交的变更。
        return new BootstrapResult(watermark, replayAfter(watermark));
    }

    /**
     * 按 {@code change_seq} 增量 replay 到当前最新（第 5 / 6 / 7 步）。
     *
     * <p>循环有两个终止条件，各自都成立：</p>
     * <ul>
     *   <li><b>批没满</b> —— {@code LIMIT} 没被用满，说明没有更多匹配行，可以直接结束。
     *       这是常见的结束路径。</li>
     *   <li><b>空批</b> —— 覆盖"最后一批正好装满"的情形。满批只说明"可能还有"，
     *       不说明"没有了"，所以必须再拉一次确认。这是循环真正的兜底终止条件。</li>
     * </ul>
     *
     * <p>⚠️ 还有一个<b>必须</b>存在的护栏：如果某一批回来了行、但没有任何一行的
     * {@code change_seq} 能把游标往前推，循环就永远前进不了。这在当前 DDL 下不可能
     * （{@code change_seq BIGINT NOT NULL}），但它是一个"不会报错、只会挂死启动"的
     * 失败模式，所以这里显式抛异常：拉取契约被破坏时，无法证明缓存是完整的，
     * 就不能提供服务。</p>
     *
     * @param watermark 快照水位，即 replay 的下界
     * @return 实际应用的行数
     */
    private long replayAfter(long watermark) {
        int batchSize = properties.getSecurityReconcileBatchSize();
        long cursor = watermark;
        long applied = 0L;
        while (true) {
            List<AccountSecurityState> batch = securityStateMapper.pullChangesAfter(cursor, batchSize);
            if (batch.isEmpty()) {
                return applied;
            }

            long maxSeq = cursor;
            for (AccountSecurityState state : batch) {
                // 第 6 步的版本闸门在 SecurityStateApplier 内部（两条传播路径的唯一收口点），
                // 这里刻意不重复判断，否则两条路径的语义会各自漂移。
                securityStateApplier.apply(state.getAccountId(), state.isDisabled(), versionOf(state),
                        epochMillis(state.getDisabledAt()));
                applied++;
                if (state.getChangeSeq() != null) {
                    maxSeq = Math.max(maxSeq, state.getChangeSeq());
                }
            }

            if (maxSeq <= cursor) {
                throw new IllegalStateException(
                        "replay 拉回 " + batch.size() + " 行但 change_seq 无法推进游标（cursor=" + cursor
                                + "），拒绝以无法证明完整的安全平面启动");
            }
            securityStateCache.advanceCursor(maxSeq);
            cursor = maxSeq;

            if (batch.size() < batchSize) {
                return applied;
            }
        }
    }

    private static long versionOf(AccountSecurityState state) {
        return state.getSecurityVersion() == null ? 0L : state.getSecurityVersion();
    }

    private static long epochMillis(java.time.LocalDateTime value) {
        return value == null ? 0L : value.atZone(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli();
    }
}

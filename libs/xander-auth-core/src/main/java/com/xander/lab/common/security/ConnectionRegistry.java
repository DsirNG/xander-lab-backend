package com.xander.lab.common.security;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 每实例的活跃长连接注册表。
 *
 * <p>结构就是 {@code ConcurrentMap<accountId, Set<ConnectionHandle>>}，
 * 只存在于本机内存。封禁事件到达后：标记 denied → 遍历该账号在本实例的连接
 * → 关闭它们。</p>
 *
 * <h3>为什么不做中心化连接表</h3>
 * <ul>
 *   <li>连接句柄不可跨进程迁移</li>
 *   <li>中心表会引入新的单点与一致性成本</li>
 *   <li>每实例本地注册表 + 事件广播 + reconcile 已足够收敛</li>
 * </ul>
 *
 * <h3>为什么不能靠"每次请求校验"</h3>
 * <p>现状是长连接只在建连时鉴权一次：SSE 用 {@code new SseEmitter(0L)} 永不超时，
 * 之后的整个流式过程不再校验任何身份或状态；WS 在
 * {@code afterConnectionEstablished} 取一次 userId 存进局部变量，
  * 之后唯一的身份判断是数据归属校验而不是账号状态校验。
 * 所以封禁必须能<b>主动终止已建立的流</b>，而不是只挡新请求。</p>
 */
public class ConnectionRegistry {

    /** 连接类型标签，用于 {@code active_sse} / {@code active_ws} 指标。 */
    public static final String KIND_SSE = "sse";
    public static final String KIND_WS = "ws";

    /**
     * 账号封禁导致关闭连接时的标准 reason。
     *
     * <p>放在共享内核而不是某个业务域：SSE / WS 的每个宿主都要用同一个词汇，
     * 否则 automation 的 SSE 得反向依赖 identity 才能拿到这个字符串常量，
     * 而业务域之间不允许这种"为了一个常量"的依赖。</p>
     */
    public static final String REASON_ACCOUNT_DISABLED = "ACCOUNT_DISABLED";

    /**
     * 账号封禁时 WS 使用的应用层 Close Code。
     *
     * <p>落在 RFC 6455 的「应用私有」区间（3000–4999），客户端可以把它与网络故障
     * 区分开，从而「停止重连 + 提示已封禁」，而不是陷入重连风暴。
     * 该值是对外契约，登记为
     * {@code ws-account-disabled-close}。</p>
     */
    public static final int WS_CLOSE_CODE_ACCOUNT_DISABLED = 4403;

    /**
     * 一个可被强制关闭的长连接。
     *
     * <p>实现方负责把 {@link #close(String)} 映射到具体传输：
     * SSE 用 {@code completeWithError} / {@code complete}，
     * WS 用 {@code close(WS_CLOSE_CODE_ACCOUNT_DISABLED, REASON_ACCOUNT_DISABLED)}。</p>
     */
    public interface ConnectionHandle {
        /** 连接归属的账号。 */
        Long accountId();

        /** 连接类型：sse / ws。 */
        String kind();

        /**
         * 关闭连接。
         *
         * @param reason 关闭原因，会写进 WS close reason 或 SSE 的 error
         */
        void close(String reason);
    }

    private final Map<Long, Set<ConnectionHandle>> byAccount = new ConcurrentHashMap<>();

    private final AtomicLong totalClosed = new AtomicLong(0L);

    /** 注册一条连接。 */
    public void register(ConnectionHandle handle) {
        if (handle == null || handle.accountId() == null) {
            return;
        }
        byAccount.computeIfAbsent(handle.accountId(),
                key -> ConcurrentHashMap.newKeySet()).add(handle);
    }

    /** 连接自然结束（正常完成、客户端断开、心跳失败）时移除。 */
    public void unregister(ConnectionHandle handle) {
        if (handle == null || handle.accountId() == null) {
            return;
        }
        Set<ConnectionHandle> handles = byAccount.get(handle.accountId());
        if (handles == null) {
            return;
        }
        handles.remove(handle);
        if (handles.isEmpty()) {
            // 只在仍为空时移除，避免把并发注册进来的连接一起丢掉
            byAccount.remove(handle.accountId(), handles);
        }
    }

    /**
     * 关闭某账号在本实例上的全部连接。
     *
     * @return 实际关闭的连接数
     */
    public int closeAll(Long accountId, String reason) {
        Set<ConnectionHandle> handles = byAccount.remove(accountId);
        if (handles == null || handles.isEmpty()) {
            return 0;
        }
        int closed = 0;
        for (ConnectionHandle handle : handles) {
            try {
                handle.close(reason);
                closed++;
            } catch (RuntimeException ignored) {
                // 关闭失败不能影响其他连接；连接侧的超时/心跳会兜底回收
            }
        }
        totalClosed.addAndGet(closed);
        return closed;
    }

    /** 某账号当前活跃连接数。 */
    public int activeCount(Long accountId) {
        Set<ConnectionHandle> handles = byAccount.get(accountId);
        return handles == null ? 0 : handles.size();
    }

    /** 全实例活跃连接总数。多实例下必须逐实例读，不得用聚合值代替。 */
    public int activeCount() {
        int total = 0;
        for (Set<ConnectionHandle> handles : byAccount.values()) {
            total += handles.size();
        }
        return total;
    }

    /** 按类型统计，供 {@code active_sse} / {@code active_ws} 指标。 */
    public int activeCountByKind(String kind) {
        int total = 0;
        for (Set<ConnectionHandle> handles : byAccount.values()) {
            for (ConnectionHandle handle : handles) {
                if (kind.equals(handle.kind())) {
                    total++;
                }
            }
        }
        return total;
    }

    public long totalClosed() {
        return totalClosed.get();
    }

    /** 仅用于测试与诊断。 */
    public Map<Long, Integer> snapshot() {
        Map<Long, Integer> copy = new ConcurrentHashMap<>();
        byAccount.forEach((accountId, handles) -> copy.put(accountId, handles.size()));
        return Collections.unmodifiableMap(copy);
    }
}

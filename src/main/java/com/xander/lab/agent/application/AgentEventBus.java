package com.xander.lab.agent.application;

import com.xander.lab.agent.domain.AgentEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 会话级进程内事件总线：事件落库后即时直推本实例的在线订阅者，
 * 让 WebSocket 推送不必等订阅方轮询数据库。
 *
 * <p>它只负责"把已经在内存里的事件送到订阅者"，不承担落库职责；调用方决定推送与落库的先后。
 * 普通事件走"先落库、后广播"（{@code AgentEventService#storeForRun}）；对流式增量这种
 * 延迟敏感、且可由完整正文兜底的场景，走 {@code AgentEventProjector#appendLive} 的
 * "先推送、后静默落库"，落库卡住也不会拖慢用户看到正文。</p>
 *
 * <p><b>多实例部署前置条件（重要）</b>：本总线只在单个 JVM 内生效。
 * 当前服务为单实例部署，触发执行的 HTTP 请求与本总线的订阅者必然同进程；
 * 若未来水平扩容为多实例，「POST 触发执行」与「WS 握手订阅」可能落在不同节点，
 * 订阅者将收不到其他实例产生的事件。届时必须先引入跨实例广播
 * （如 Redis pub/sub 转发 publish，或复用既有 Redis 基础设施），
 * 并保留数据库重放作为跨实例兜底，再启用多实例。</p>
 */
@Component
public class AgentEventBus {

    private final Map<Long, List<Consumer<AgentEvent>>> subscribers = new ConcurrentHashMap<>();

    /** 订阅某会话的事件；返回的句柄用于取消订阅（连接关闭时必须调用，防止泄漏）。 */
    public Subscription subscribe(Long conversationId, Consumer<AgentEvent> sink) {
        List<Consumer<AgentEvent>> list = subscribers.computeIfAbsent(conversationId,
                key -> new CopyOnWriteArrayList<>());
        list.add(sink);
        return () -> list.remove(sink);
    }

    /** 向该会话的全部本实例订阅者广播事件；单个订阅者异常不影响其他订阅者。 */
    public void publish(Long conversationId, AgentEvent event) {
        List<Consumer<AgentEvent>> list = subscribers.get(conversationId);
        if (list == null || list.isEmpty()) return;
        for (Consumer<AgentEvent> sink : list.toArray(Consumer[]::new)) {
            try {
                sink.accept(event);
            } catch (RuntimeException ignored) {
                // 订阅者发送失败由其自身链路收尾；不能阻断其他订阅者或落库主流程。
            }
        }
    }

    /** 订阅句柄：close 后不再接收该会话事件。 */
    @FunctionalInterface
    public interface Subscription {
        void close();
    }
}

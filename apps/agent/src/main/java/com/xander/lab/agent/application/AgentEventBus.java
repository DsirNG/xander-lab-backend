package com.xander.lab.agent.application;

import com.xander.lab.agent.domain.AgentEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 * <p>本总线默认仍只在当前 JVM 内直推；开启
 * {@code agent.event-bus.pubsub-enabled} 后，带持久化 id 的事件还会通过 Redis Pub/Sub
 * 扇出到其他实例。流式增量在「先推送、后落库」阶段 id 为空，刻意不跨实例广播，
 * 避免收到无 id 的实时副本后又收到落库副本而重复渲染；落库副本由订阅端重放兜底。</p>
 */
@Component
@Slf4j
public class AgentEventBus {

    private final Map<Long, List<Consumer<AgentEvent>>> subscribers = new ConcurrentHashMap<>();
    private final ObjectProvider<AgentEventBroadcaster> broadcasterProvider;

    /** 保留无参构造，测试与单实例模式不依赖 Redis。 */
    public AgentEventBus() {
        this(null);
    }

    @Autowired
    public AgentEventBus(ObjectProvider<AgentEventBroadcaster> broadcasterProvider) {
        this.broadcasterProvider = broadcasterProvider;
    }

    /** 订阅某会话的事件；返回的句柄用于取消订阅（连接关闭时必须调用，防止泄漏）。 */
    public Subscription subscribe(Long conversationId, Consumer<AgentEvent> sink) {
        List<Consumer<AgentEvent>> list = subscribers.computeIfAbsent(conversationId,
                key -> new CopyOnWriteArrayList<>());
        list.add(sink);
        return () -> list.remove(sink);
    }

    /** 向该会话的全部本实例订阅者广播事件；单个订阅者异常不影响其他订阅者。 */
    public void publish(Long conversationId, AgentEvent event) {
        publishLocally(conversationId, event);
        if (event == null || event.getId() == null || broadcasterProvider == null) return;

        Runnable broadcast = () -> {
            AgentEventBroadcaster broadcaster = broadcasterProvider.getIfAvailable();
            if (broadcaster == null) return;
            try {
                broadcaster.publish(event);
            } catch (RuntimeException exception) {
                // agent_event 已落库，跨实例快路径失败不能回滚业务事务；订阅端重放会兜底。
                log.warn("agent cross-instance event broadcast failed conversation={} event={} type={}",
                        conversationId, event.getId(), event.getEventType(), exception);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    broadcast.run();
                }
            });
        } else {
            broadcast.run();
        }
    }

    /** 仅投递当前 JVM，不再进入 Redis，供订阅者避免广播回环。 */
    void publishLocally(Long conversationId, AgentEvent event) {
        if (event == null) return;
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

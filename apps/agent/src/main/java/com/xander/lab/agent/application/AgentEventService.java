package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.domain.AgentEvent;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.infrastructure.AgentEventMapper;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Consumer;

/** Durable, replayable event stream of an agent conversation. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentEventService {
    private static final int REPLAY_BATCH_SIZE = 200;

    private final AgentEventMapper eventMapper;
    private final AgentConversationMapper conversationMapper;
    private final ObjectMapper objectMapper;
    private final AgentEventBus eventBus;

    /** Persists an event for the active generation and renews its lease. */
    @Transactional
    public AgentEvent storeActive(Long conversationId, Long userId, long runVersion,
                                  String eventType, Object data) {
        renewActiveLease(conversationId, userId, runVersion);
        return storeForRun(conversationId, userId, runVersion, eventType, data, true);
    }

    /**
     * 与 {@link #storeActive} 落库内容完全一致，但<b>不广播</b>。
     *
     * <p>供 {@link AgentEventProjector#appendLive} 的"先推送、再落库"路径使用：那条路径已经
     * 用内存事件把内容推给订阅者了，如果这里再广播一次，同一条增量会因为「无 id 的那份不被
     * 客户端去重丢弃」而渲染两遍。事件落库只为断线重放留一份可读副本。</p>
     */
    @Transactional
    public AgentEvent persistActiveSilently(Long conversationId, Long userId, long runVersion,
                                            String eventType, Object data) {
        renewActiveLease(conversationId, userId, runVersion);
        return storeForRun(conversationId, userId, runVersion, eventType, data, true, false);
    }

    /** Stores an event for a generation and broadcasts it to this instance's live subscribers. */
    public AgentEvent storeForRun(Long conversationId, Long userId, long runVersion,
                                  String eventType, Object data, boolean requireRunning) {
        return storeForRun(conversationId, userId, runVersion, eventType, data, requireRunning, true);
    }

    private AgentEvent storeForRun(Long conversationId, Long userId, long runVersion,
                                   String eventType, Object data, boolean requireRunning,
                                   boolean broadcast) {
        AgentEvent event = new AgentEvent();
        event.setConversationId(conversationId);
        event.setRunVersion(runVersion);
        event.setEventType(eventType);
        event.setDataContent(serialize(data));
        int inserted = eventMapper.insertForRun(event, userId, requireRunning);
        if (inserted != 1 || event.getId() == null) {
            if (inserted == 0) throw new AgentRunSupersededException(conversationId, runVersion);
            throw new IllegalStateException("智能体事件持久化后未返回事件游标");
        }
        // 落库成功即广播：在线订阅者（WS）即时收到，无需自己去查库。
        // 注意这里是"先落库、后广播"，且整个方法在事务内——订阅者收到的事件可能与最终提交
        // 状态不一致（脏读），并且推送会被这次数据库往返阻塞。对延迟敏感、且可由完整正文
        // 兜底的流式增量，应走 AgentEventProjector#appendLive 的"先推送、再落库"路径
        // （它落库时走 broadcast = false，避免同一条增量被推送两次）。
        if (broadcast) eventBus.publish(conversationId, event);
        return event;
    }

    /** 续租当前执行的 running 租约；touched 不为 1 说明本轮已被新代次取代。 */
    private void renewActiveLease(Long conversationId, Long userId, long runVersion) {
        int touched = conversationMapper.update(null, new LambdaUpdateWrapper<com.xander.lab.agent.domain.AgentConversation>()
                .eq(com.xander.lab.agent.domain.AgentConversation::getId, conversationId)
                .eq(com.xander.lab.agent.domain.AgentConversation::getUserId, userId)
                .eq(com.xander.lab.agent.domain.AgentConversation::getStatus, "running")
                .eq(com.xander.lab.agent.domain.AgentConversation::getRunVersion, runVersion)
                .setSql("updated_at = CURRENT_TIMESTAMP"));
        if (touched != 1) throw new AgentRunSupersededException(conversationId, runVersion);
    }

    /** Delivers an already-stored event; subscriber disconnects never affect durable execution. */
    public void deliver(AgentEvent event, Consumer<AgentEvent> onStoredEvent) {
        try {
            onStoredEvent.accept(event);
        } catch (RuntimeException exception) {
            log.debug("agent live event delivery failed for conversation {} and event {}",
                    event.getConversationId(), event.getEventType());
        }
    }

    /**
     * 组装一条尚未落库的内存事件，供流式增量"先推送"使用。
     *
     * <p>它的 id 为空：订阅端的 id 单调去重会跳过它（见 AgentStreamWebSocketHandler 的推送出口），
     * 因此它不占用游标、也不会把随后落库的那条挤掉。相应地，落库那一份<b>必须静默</b>
     * （见 {@link #persistActiveSilently}），否则同一条增量会因为无 id 副本不被去重而被送达两次。</p>
     */
    public AgentEvent liveEvent(Long conversationId, long runVersion, String eventType, Object data) {
        AgentEvent event = new AgentEvent();
        event.setConversationId(conversationId);
        event.setRunVersion(runVersion);
        event.setEventType(eventType);
        event.setDataContent(serialize(data));
        return event;
    }

    public List<AgentEvent> listAfter(Long conversationId, long runVersion, long afterEventId) {
        return eventMapper.selectList(new LambdaQueryWrapper<AgentEvent>()
                .eq(AgentEvent::getConversationId, conversationId)
                .eq(AgentEvent::getRunVersion, runVersion)
                .gt(AgentEvent::getId, afterEventId)
                .orderByAsc(AgentEvent::getId)
                .last("LIMIT " + REPLAY_BATCH_SIZE));
    }

    /** Returns the latest persisted terminal event of the current retained turn, if one exists. */
    public AgentEvent latestTerminal(Long conversationId, long runVersion) {
        List<AgentEvent> events = eventMapper.selectList(new LambdaQueryWrapper<AgentEvent>()
                .eq(AgentEvent::getConversationId, conversationId)
                .eq(AgentEvent::getRunVersion, runVersion)
                .in(AgentEvent::getEventType, List.of("complete", "error"))
                .orderByDesc(AgentEvent::getId)
                .last("LIMIT 1"));
        return events.isEmpty() ? null : events.get(0);
    }

    /** 该轮是否仍在运行（同用户、同代次、running 状态）；供订阅端做取代/收尾的防御性检查。 */
    public boolean isRunActive(Long conversationId, Long userId, long runVersion) {
        AgentConversation snapshot = conversationMapper.selectById(conversationId);
        return snapshot != null
                && Long.valueOf(runVersion).equals(snapshot.getRunVersion())
                && userId != null && userId.equals(snapshot.getUserId())
                && "running".equals(snapshot.getStatus());
    }

    /**
     * 从游标开始跟随一轮执行：重放已持久化事件，无新事件时轮询等待，
     * 直到该轮出现终态（complete/error）或会话被新一轮次取代为止。
     * SSE 与 WebSocket 订阅共用此逻辑；订阅端断开只影响本次跟随，不打断持久化执行。
     */
    public void follow(Long conversationId, Long userId, long runVersion, long initialCursor,
                       Consumer<AgentEvent> sink) throws InterruptedException {
        long cursor = initialCursor;
        while (true) {
            List<AgentEvent> events = listAfter(conversationId, runVersion, cursor);
            if (events.isEmpty()) {
                AgentConversation snapshot = conversationMapper.selectById(conversationId);
                boolean sameRun = snapshot != null
                        && Long.valueOf(runVersion).equals(snapshot.getRunVersion())
                        && userId != null && userId.equals(snapshot.getUserId());
                if (!sameRun || !"running".equals(snapshot.getStatus())) {
                    AgentEvent terminal = latestTerminal(conversationId, runVersion);
                    if (terminal != null && terminal.getId() != null && terminal.getId() > cursor) {
                        sink.accept(terminal);
                    }
                    return;
                }
                Thread.sleep(500);
                continue;
            }
            for (AgentEvent event : events) {
                if (event.getId() != null) cursor = event.getId();
                sink.accept(event);
                if ("complete".equals(event.getEventType())
                        || "error".equals(event.getEventType())) {
                    return;
                }
            }
        }
    }

    private String serialize(Object data) {
        if (data instanceof String text) return text;
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("智能体事件序列化失败", exception);
        }
    }
}

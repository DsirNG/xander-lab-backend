package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentEvent;
import com.xander.lab.agent.domain.AgentMessage;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.agent.infrastructure.AgentMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Consumer;

/** Projects active-run messages and legacy replay events from execution facts. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentEventProjector {
    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentEventService eventService;
    private final TransactionTemplate transactionTemplate;

    void saveActiveMessage(Long conversationId, Long userId, long runVersion,
                           String role, String kind, String toolName, String content) {
        insertActiveMessage(conversationId, userId, runVersion, role, kind, toolName, null, content);
    }

    /**
     * 工具消息多一个字段：这次调用的持久化身份。
     *
     * <p>刻意不并进 {@link #saveActiveMessage}：那个方法已经有七个 String 参数，再插一个
     * 同类型参数，调用点写反了编译器也不会响。工具消息必须显式回答"这是哪一次调用"，
     * 所以给它一条自己的入口——身份漏了是编译不过的，而不是悄悄少一个字段。</p>
     */
    void saveActiveToolMessage(Long conversationId, Long userId, long runVersion,
                               String kind, String toolName, String invocationId, String content) {
        insertActiveMessage(conversationId, userId, runVersion, "tool", kind, toolName, invocationId, content);
    }

    private void insertActiveMessage(Long conversationId, Long userId, long runVersion, String role,
                                     String kind, String toolName, String invocationId, String content) {
        AgentMessage message = new AgentMessage();
        message.setConversationId(conversationId);
        message.setRunVersion(runVersion);
        message.setRole(role);
        message.setKind(kind);
        message.setToolName(toolName);
        message.setInvocationId(invocationId);
        message.setContent(content);
        transactionTemplate.executeWithoutResult(status -> {
            int touched = renewActiveRun(conversationId, userId, runVersion);
            if (touched != 1 || messageMapper.insertForActiveRun(message, userId, runVersion) != 1) {
                throw new AgentRunSupersededException(conversationId, runVersion);
            }
        });
    }

    /** One user turn owns one plan card; later plan updates replace that card's contents. */
    void saveOrUpdateActivePlanMessage(Long conversationId, Long userId, long runVersion, String content) {
        transactionTemplate.executeWithoutResult(status -> {
            if (renewActiveRun(conversationId, userId, runVersion) != 1) {
                throw new AgentRunSupersededException(conversationId, runVersion);
            }
            int updated = messageMapper.update(null, new LambdaUpdateWrapper<AgentMessage>()
                    .eq(AgentMessage::getConversationId, conversationId)
                    .eq(AgentMessage::getRunVersion, runVersion)
                    .eq(AgentMessage::getKind, "plan")
                    .set(AgentMessage::getContent, content));
            if (updated > 0) return;

            AgentMessage message = new AgentMessage();
            message.setConversationId(conversationId);
            message.setRunVersion(runVersion);
            message.setRole("assistant");
            message.setKind("plan");
            message.setContent(content);
            if (messageMapper.insertForActiveRun(message, userId, runVersion) != 1) {
                throw new AgentRunSupersededException(conversationId, runVersion);
            }
        });
    }

    AgentEvent appendActive(Long conversationId, Long userId, long runVersion,
                            String eventType, Object data, Consumer<AgentEvent> listener) {
        AgentEvent event = eventService.storeActive(conversationId, userId, runVersion, eventType, data);
        eventService.deliver(event, listener);
        return event;
    }

    /**
     * 流式增量的专用路径：<b>先推送，再落库</b>。
     *
     * <p>为什么不能沿用 {@link #appendActive} 的"先落库再推送"：那条路径把一次数据库往返
     * 串在用户看到文字之前，落库慢或卡住时，用户就迟迟收不到（甚至永远收不到）已经生成好的正文。
     * 这恰恰是流式回答最不该发生的事——正文其实早就在内存里了。</p>
     *
     * <p>这样做的前提是"增量不必逐条持久化"：收口时的 answer 事件带完整正文，
     * 并且还会落成一条持久化消息（见 AgentConversationService 的收口分支）。
     * 所以中间某几个增量没来得及落库，断线重连读到的文本依然完整，不会缺内容。</p>
     *
     * <p>落库必须走 {@code persistActiveSilently} 而不是 {@code storeActive}：后者落库成功后会
     * 再广播一次，而订阅端的"按 id 单调去重"挡不住本条无 id 的内存事件，同一条增量会渲染两遍。
     * 本方法的推送就是唯一的送达路径，落库只为断线重放留副本。</p>
     *
     * <p>落库失败只降级为"这段增量没有可重放副本"，不影响本轮已经送达的正文；
     * 抛出去会打断整轮执行，反而把已经生成的内容也一并废掉，所以这里吞掉并记一条日志。
     * 被新代次取代（{@link AgentRunSupersededException}）不走这里——那不是"落库故障"，
     * 而是本轮已经作废，必须照常向上抛出让执行停止。</p>
     */
    void appendLive(Long conversationId, Long userId, long runVersion,
                    String eventType, Object data, Consumer<AgentEvent> listener) {
        AgentEvent event = eventService.liveEvent(conversationId, runVersion, eventType, data);
        // 第一步：先让用户看到。这一步不依赖任何数据库往返，落库卡死也挡不住它。
        eventService.deliver(event, listener);
        // 第二步：再落库留副本，供断线重放补齐；静默落库，避免同一条增量被推送两次。
        try {
            AgentEvent stored = eventService.persistActiveSilently(conversationId, userId, runVersion, eventType, data);
            event.setId(stored.getId());
        } catch (AgentRunSupersededException superseded) {
            throw superseded;
        } catch (RuntimeException persistenceFailure) {
            log.warn("agent live delta not persisted conversation={} run={} event={} exceptionType={}",
                    conversationId, runVersion, eventType, persistenceFailure.getClass().getSimpleName());
        }
    }

    /**
     * 工具进度事件的载荷。
     *
     * <p>{@code invocationId} 可能为空（审批恢复等没有持久化身份的路径），此时前端按工具名
     * 配对——与改动前逐字节一致。非空时它是唯一的配对键，同名工具也不会串卡。</p>
     */
    JsonNode progressPayload(String tool, String invocationId, String message) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("tool", tool);
        if (invocationId != null) {
            payload.put("invocationId", invocationId);
        }
        String raw = message == null ? "" : message;
        int separator = raw.indexOf('|');
        if (separator > 0) {
            payload.put("stage", raw.substring(0, separator));
            payload.put("message", raw.substring(separator + 1));
        } else {
            payload.put("message", raw);
        }
        return payload;
    }

    private int renewActiveRun(Long conversationId, Long userId, long runVersion) {
        // A message projection also proves that this worker still owns the durable lease.
        return conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getId, conversationId)
                .eq(AgentConversation::getUserId, userId)
                .eq(AgentConversation::getStatus, "running")
                .eq(AgentConversation::getRunVersion, runVersion)
                .setSql("updated_at = CURRENT_TIMESTAMP"));
    }
}

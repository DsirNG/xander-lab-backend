package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.agent.application.AgentTool.ProgressListener;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentMessage;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.agent.infrastructure.AgentConversationProperties;
import com.xander.lab.agent.infrastructure.AgentMessageMapper;
import com.xander.lab.agent.infrastructure.AgentModelClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Orchestrates an agent conversation: the model decides each next step
 * (reply or tool call), tool outcomes are fed back as observations, and the
 * loop continues until the model concludes or step budgets are exhausted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentConversationService {

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentModelClient modelClient;
    private final AgentToolRegistry toolRegistry;
    private final AgentConversationProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final AgentEventService eventService;

    @Transactional
    public AgentConversation create(Long userId, String message) {
        AgentConversation conversation = new AgentConversation();
        conversation.setUserId(userId);
        conversation.setTitle(excerpt(defaultText(message, "新对话"), 40));
        conversation.setStatus("ready");
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationMapper.insert(conversation);
        saveMessage(conversation.getId(), "user", "message", null, message);
        return conversation;
    }

    /** Runs one full turn: user message → agent loop → final reply. */
    public AgentConversation executeTurn(Long conversationId, Long userId, String message,
                                         BiConsumer<String, String> onEvent) {
        transactionTemplate.executeWithoutResult(status -> claimConversation(conversationId, userId));
        try {
            if (StringUtils.hasText(message)) {
                saveMessage(conversationId, "user", "message", null, message.trim());
            }
            return runLoop(conversationId, userId, onEvent);
        } catch (RuntimeException e) {
            transactionTemplate.executeWithoutResult(status -> markFailed(conversationId, userId, e.getMessage()));
            throw e;
        }
    }

    /** Converts an SSE writer into an event listener that replays stored events. */
    private Consumer<com.xander.lab.agent.domain.AgentEvent> live(BiConsumer<String, String> onEvent) {
        return event -> onEvent.accept(event.getEventType(), event.getDataContent());
    }

    private AgentConversation runLoop(Long conversationId, Long userId, BiConsumer<String, String> onEvent) {
        Consumer<com.xander.lab.agent.domain.AgentEvent> listener = live(onEvent);
        int maxSteps = Math.max(1, properties.getMaxSteps());
        int failures = 0;
        JsonNode lastError = null;

        for (int step = 0; step < maxSteps; step++) {
            String context = buildContext(conversationId, userId, lastError);
            JsonNode decision = modelClient.decideNextStep(context, toolRegistry.describeTools());

            String thought = decision.path("thought").asText("");
            if (StringUtils.hasText(thought)) {
                saveMessage(conversationId, "assistant", "thought", null, thought);
                eventService.append(conversationId, "thought", thought, listener);
            }

            if (decision.path("done").asBoolean(false)) {
                String reply = defaultText(decision.path("reply").asText(), "已完成你的请求。");
                saveMessage(conversationId, "assistant", "answer", null, reply);
                eventService.append(conversationId, "answer", reply, listener);
                return finalizeReady(conversationId, userId, onEvent);
            }

            JsonNode action = decision.path("action");
            String toolName = action.path("tool").asText("");
            AgentTool tool = StringUtils.hasText(toolName) ? toolRegistry.get(toolName) : null;
            if (tool == null) {
                lastError = JsonNodeFactory.instance.objectNode()
                        .put("error", "未知工具 " + toolName + "，请从可用工具中选择")
                        .put("availableTools", String.join(", ", toolRegistry.names()));
                continue;
            }

            JsonNode args = action.path("args");
            ObjectNode call = JsonNodeFactory.instance.objectNode();
            call.put("tool", toolName);
            call.set("args", args);
            saveMessage(conversationId, "tool", "tool_call", toolName, call.toString());
            eventService.append(conversationId, "tool_start", call, listener);

            try {
                ProgressListener progress = message ->
                        eventService.append(conversationId, "tool_progress",
                                progressPayload(toolName, message), listener);
                JsonNode result = tool.execute(userId, args, progress);
                saveMessage(conversationId, "tool", "tool_result", toolName, result.toString());
                eventService.append(conversationId, "tool_end",
                        JsonNodeFactory.instance.objectNode().put("tool", toolName).set("result", result),
                        listener);
                failures = 0;
                lastError = null;
            } catch (Exception e) {
                failures++;
                ObjectNode errorNode = JsonNodeFactory.instance.objectNode();
                errorNode.put("tool", toolName);
                errorNode.put("error", safeMessage(e));
                saveMessage(conversationId, "tool", "tool_result", toolName, errorNode.toString());
                eventService.append(conversationId, "tool_error", errorNode, listener);
                if (failures >= Math.max(1, properties.getMaxToolFailures())) {
                    String reply = "很抱歉，连续多次尝试后仍然失败：" + safeMessage(e)
                            + "。请换个说法重试，或调整你的需求。";
                    saveMessage(conversationId, "assistant", "answer", null, reply);
                    eventService.append(conversationId, "answer", reply, listener);
                    return finalizeReady(conversationId, userId, onEvent);
                }
                lastError = errorNode;
            }
        }

        String exhausted = "这一步的推理步数已达上限，为避免资源消耗我先在这里暂停。"
                + "你可以继续补充信息或换一个更具体的需求。";
        saveMessage(conversationId, "assistant", "answer", null, exhausted);
        eventService.append(conversationId, "answer", exhausted, listener);
        return finalizeReady(conversationId, userId, onEvent);
    }

    private JsonNode progressPayload(String tool, String message) {
        return JsonNodeFactory.instance.objectNode()
                .put("tool", tool)
                .put("message", message);
    }

    private AgentConversation finalizeReady(Long conversationId, Long userId,
                                            BiConsumer<String, String> onEvent) {
        AgentConversation conversation = transactionTemplate.execute(status -> {
            AgentConversation current = requireOwned(conversationId, userId);
            current.setStatus("ready");
            current.setErrorMessage(null);
            current.setUpdatedAt(LocalDateTime.now());
            conversationMapper.updateById(current);
            return current;
        });
        eventService.append(conversationId, "complete", conversation, live(onEvent));
        return conversation;
    }

    private void claimConversation(Long conversationId, Long userId) {
        int updated = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getId, conversationId)
                .eq(AgentConversation::getUserId, userId)
                .eq(AgentConversation::getStatus, "ready")
                .set(AgentConversation::getStatus, "running")
                .set(AgentConversation::getErrorMessage, null)
                .set(AgentConversation::getUpdatedAt, LocalDateTime.now()));
        if (updated == 1) {
            eventService.clearConversationEvents(conversationId);
            return;
        }
        AgentConversation conversation = requireOwned(conversationId, userId);
        if ("running".equals(conversation.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "智能体正在执行中，请稍候");
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "当前会话状态不能继续对话");
    }

    private void markFailed(Long conversationId, Long userId, String errorMessage) {
        AgentConversation conversation = requireOwned(conversationId, userId);
        conversation.setStatus("failed");
        conversation.setErrorMessage(limit(errorMessage, 1000));
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conversation);
    }

    /** 中断(超过 stale 分钟仍 running)的会话恢复为 ready，允许继续对话。 */
    public AgentConversation resolveStale(AgentConversation conversation, Long userId) {
        if (!"running".equals(conversation.getStatus()) || conversation.getUpdatedAt() == null) {
            return conversation;
        }
        int staleMinutes = Math.max(1, properties.getStaleConversationMinutes());
        if (conversation.getUpdatedAt().isAfter(LocalDateTime.now().minusMinutes(staleMinutes))) {
            return conversation;
        }
        int updated = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getId, conversation.getId())
                .eq(AgentConversation::getUserId, userId)
                .eq(AgentConversation::getStatus, "running")
                .le(AgentConversation::getUpdatedAt, LocalDateTime.now().minusMinutes(staleMinutes))
                .set(AgentConversation::getStatus, "ready")
                .set(AgentConversation::getErrorMessage, "上一轮执行中断，可继续对话")
                .set(AgentConversation::getUpdatedAt, LocalDateTime.now()));
        return updated == 1 ? requireOwned(conversation.getId(), userId) : conversation;
    }

    public AgentConversation get(Long conversationId, Long userId) {
        return resolveStale(requireOwned(conversationId, userId), userId);
    }

    public List<AgentMessage> listMessages(Long conversationId, Long userId) {
        requireOwned(conversationId, userId);
        return messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationId, conversationId)
                .orderByAsc(AgentMessage::getId));
    }

    public List<AgentConversation> listSessions(Long userId) {
        return conversationMapper.selectList(new LambdaQueryWrapper<AgentConversation>()
                .eq(AgentConversation::getUserId, userId)
                .orderByDesc(AgentConversation::getUpdatedAt)
                .last("LIMIT 50"));
    }

    private void saveMessage(Long conversationId, String role, String kind,
                             String toolName, String content) {
        AgentMessage message = new AgentMessage();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setKind(kind);
        message.setToolName(toolName);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);
    }

    /** Builds the model context from the recent message history, dropping tool payload noise. */
    private String buildContext(Long conversationId, Long userId, JsonNode lastError) {
        List<AgentMessage> messages = listMessages(conversationId, userId);
        int limit = Math.max(5, properties.getContextMessageLimit());
        List<AgentMessage> recent = messages.size() <= limit
                ? messages
                : messages.subList(messages.size() - limit, messages.size());

        StringBuilder builder = new StringBuilder("以下是智能体与用户的对话记录：\n\n");
        for (AgentMessage message : recent) {
            String role = switch (message.getRole()) {
                case "user" -> "用户";
                case "tool" -> "工具结果";
                default -> "智能体";
            };
            String content = switch (message.getKind()) {
                case "thought" -> "（思考：）" + message.getContent();
                case "tool_call" -> "（调用了工具）" + message.getContent();
                case "tool_result" -> compactJson(message.getContent(), 800);
                case "answer" -> "（回复：）" + message.getContent();
                default -> message.getContent();
            };
            builder.append("【").append(role).append("】").append(content).append("\n\n");
        }
        if (lastError != null) {
            builder.append("【最近一次工具失败】").append(lastError.toString()).append("\n\n");
        }
        builder.append("请根据以上对话继续：如需执行操作则返回 action，否则返回 done=true 的最终回复。");
        return builder.toString();
    }

    private String compactJson(String content, int maxLength) {
        if (!StringUtils.hasText(content)) return "";
        return content.length() <= maxLength ? content : content.substring(0, maxLength) + "…";
    }

    private AgentConversation requireOwned(Long conversationId, Long userId) {
        AgentConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || userId == null || !userId.equals(conversation.getUserId())) {
            throw new IllegalArgumentException("智能体会话不存在");
        }
        return conversation;
    }

    private String safeMessage(Exception e) {
        if (e instanceof ResponseStatusException response && response.getReason() != null) {
            return response.getReason();
        }
        String message = e.getMessage();
        return StringUtils.hasText(message) ? message : e.getClass().getSimpleName();
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String excerpt(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length - 1) + "…";
    }

    private String limit(String value, int maxLength) {
        String safe = value == null ? "" : value;
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
    }
}
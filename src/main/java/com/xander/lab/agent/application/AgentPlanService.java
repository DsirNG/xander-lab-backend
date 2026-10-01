package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentPlanItem;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 读写智能体自主维护的任务计划。
 *
 * <p>计划存在 agent_conversation.plan_json，被当作会话状态处理：每一步推理前重新注入上下文，
 * 因此智能体能看到自己上一步的安排。计划由模型通过 {@code plan_tasks} 工具改写，
 * 服务层只负责规范化、限幅和持久化，不替模型决定内容。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentPlanService {
    /** 单个会话计划的步骤上限；超出会截断，避免模型把计划当草稿纸无限膨胀。 */
    static final int MAX_ITEMS = 20;
    private static final int MAX_TITLE_LENGTH = 120;
    private static final int MAX_NOTE_LENGTH = 300;

    private final AgentConversationMapper conversationMapper;
    private final ObjectMapper objectMapper;

    /** 读取当前计划；解析失败时按空计划处理而不是让整轮对话失败。 */
    public List<AgentPlanItem> load(AgentConversation conversation) {
        String raw = conversation == null ? null : conversation.getPlanJson();
        if (raw == null || raw.isBlank()) return List.of();
        try {
            List<AgentPlanItem> items = objectMapper.readValue(raw, new TypeReference<>() {
            });
            return items == null ? List.of() : List.copyOf(items);
        } catch (Exception error) {
            log.warn("agent plan json unreadable, conversation={}", conversation.getId(), error);
            return List.of();
        }
    }

    /**
     * 用模型给出的新计划整体替换旧计划。
     *
     * <p>整体替换而不是增量补丁：模型每次都要重述完整计划，这样"删掉一步"和"改顺序"
     * 不需要额外协议，也避免局部更新导致的状态漂移。</p>
     */
    public List<AgentPlanItem> replace(Long conversationId, Long userId, long runVersion, JsonNode itemsNode) {
        List<AgentPlanItem> normalized = normalize(itemsNode);
        String planJson = serialize(normalized);
        int updated = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getId, conversationId)
                .eq(AgentConversation::getUserId, userId)
                .eq(AgentConversation::getRunVersion, runVersion)
                .set(AgentConversation::getPlanJson, planJson)
                );
        if (updated != 1) {
            // 代次已变化说明本轮已被新一轮取代，丢弃迟到写入而不是覆盖新状态。
            throw new AgentRunSupersededException(conversationId, runVersion);
        }
        return normalized;
    }

    /** 新一轮用户输入开始时清空上一轮遗留计划，避免旧步骤污染新诉求。 */
    public void clear(Long conversationId, Long userId) {
        conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getId, conversationId)
                .eq(AgentConversation::getUserId, userId)
                .set(AgentConversation::getPlanJson, null));
    }

    /** 渲染成注入上下文的紧凑文本；空计划返回空串由调用方决定是否写入。 */
    public String render(List<AgentPlanItem> items) {
        if (items.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < items.size(); index++) {
            AgentPlanItem item = items.get(index);
            builder.append(index + 1).append(". [").append(item.status()).append("] ").append(item.title());
            if (!item.note().isEmpty()) builder.append("（").append(item.note()).append("）");
            if (index < items.size() - 1) builder.append('\n');
        }
        return builder.toString();
    }

    /** 计划中尚未收口的步骤，{@code plan_tasks} 用它给模型"下一步做哪个"的提示。 */
    public List<AgentPlanItem> unfinished(List<AgentPlanItem> items) {
        return items.stream().filter(item -> !item.settled()).toList();
    }

    private List<AgentPlanItem> normalize(JsonNode itemsNode) {
        if (itemsNode == null || !itemsNode.isArray() || itemsNode.isEmpty()) {
            throw new IllegalArgumentException("plan_tasks 需要非空的 items 数组");
        }
        List<AgentPlanItem> normalized = new ArrayList<>();
        boolean inProgressTaken = false;
        for (JsonNode node : itemsNode) {
            String title = limit(node.path("title").asText(""), MAX_TITLE_LENGTH);
            if (title.isBlank()) continue;
            AgentPlanItem.Status status = AgentPlanItem.Status.fromNullable(node.path("status").asText(""));
            // 同一时刻只允许一个步骤在进行中；多余的降级为待办，保证状态可读。
            if (status == AgentPlanItem.Status.IN_PROGRESS) {
                if (inProgressTaken) status = AgentPlanItem.Status.PENDING;
                else inProgressTaken = true;
            }
            normalized.add(new AgentPlanItem(title, status.name(),
                    limit(node.path("note").asText(""), MAX_NOTE_LENGTH)));
            if (normalized.size() >= MAX_ITEMS) break;
        }
        if (normalized.isEmpty()) throw new IllegalArgumentException("plan_tasks 的每个步骤都需要 title");
        return List.copyOf(normalized);
    }

    private String serialize(List<AgentPlanItem> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (Exception error) {
            throw new IllegalStateException("任务计划序列化失败", error);
        }
    }

    private String limit(String value, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}

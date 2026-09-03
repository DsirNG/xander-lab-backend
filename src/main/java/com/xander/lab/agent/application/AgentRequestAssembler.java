package com.xander.lab.agent.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.agent.domain.AgentAttachment;
import com.xander.lab.agent.domain.AgentMessage;
import com.xander.lab.agent.domain.AgentPlanItem;
import com.xander.lab.agent.infrastructure.AgentConversationProperties;
import com.xander.lab.agent.infrastructure.AgentModelProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Assembles the model-visible request and its privacy-preserving durable reference snapshot. */
@Service
@RequiredArgsConstructor
public class AgentRequestAssembler {
    static final String INSTRUCTION_SET_VERSION = "conversation-v2-language-policy";
    private static final int TOOL_RESULT_HISTORY_CHARS = 800;
    private static final int TOOL_RESULT_LATEST_CHARS = 4000;
    private static final String RESPONSE_LANGUAGE_INSTRUCTION = """
            输出语言规则：使用用户当前诉求所使用的自然语言回复。不要因为系统说明或工具结果是中文而改变回复语言。
            若当前诉求没有足以判断语言的自然语言文本，或内容仅由专有名称、产品名、代码、文件名、URL、数字或缩写组成，默认使用中文。
            不翻译代码、命令、文件路径、API 名称、参数名和其他标识符；必要的解释文字仍使用确定的回复语言。
            """;

    private final AgentToolRegistry toolRegistry;
    private final AgentConversationProperties conversationProperties;
    private final AgentModelProperties modelProperties;
    private final AgentPlanService planService;
    private final AgentQuizService quizService;
    private final ObjectMapper objectMapper;

    /** Compatibility factory for focused legacy loop tests that do not boot configuration binding. */
    static AgentRequestAssembler legacy(AgentToolRegistry toolRegistry,
                                        AgentConversationProperties conversationProperties,
                                        AgentPlanService planService, AgentQuizService quizService,
                                        ObjectMapper objectMapper) {
        return new AgentRequestAssembler(toolRegistry, conversationProperties,
                new AgentModelProperties(), planService, quizService, objectMapper);
    }

    /** Produces one request from the exact messages and session state visible to this step. */
    public AgentStepRequest assemble(AgentRequestAssemblyInput input) {
        List<AgentMessage> recent = recentWindow(input.messages());
        List<AgentAttachment> attachments = recentAttachments(recent);
        List<String> toolNames = input.allowedTools().stream().sorted().toList();
        String toolDescriptions = toolRegistry.describeToolSurface(input.allowedTools());
        String context = renderContext(recent, input.plan(), input.memoryContext(),
                input.lastError(), input.critique(), input.skillSelection());
        String schemaHash = sha256(toolDescriptions);
        String modelRoute = "agent-config:" + defaultText(modelProperties.getModel(), "unconfigured");
        List<Long> messageIds = recent.stream().map(AgentMessage::getId)
                .filter(java.util.Objects::nonNull).toList();
        String snapshot = snapshot(input, context, attachments, messageIds, toolNames, schemaHash, modelRoute);
        return new AgentStepRequest(input.conversationId(), input.userId(), input.runVersion(), input.stepNo(),
                context, toolDescriptions, attachments, messageIds, toolNames, schemaHash, modelRoute,
                INSTRUCTION_SET_VERSION, input.remainingSteps(), input.deepThinking(), snapshot);
    }

    private String renderContext(List<AgentMessage> recent, List<AgentPlanItem> plan,
                                 String memoryContext, JsonNode lastError, String critique,
                                 AgentSkillSelection skillSelection) {
        int lastToolResultIndex = lastToolResultIndex(recent);
        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(memoryContext)) {
            builder.append("以下是你对这位用户的长期记忆（跨会话有效，可直接使用；与用户当前说法冲突时以用户当前说法为准）：\n")
                    .append(memoryContext).append("\n\n");
        }
        String renderedPlan = planService.render(plan);
        if (StringUtils.hasText(renderedPlan)) {
            builder.append("以下是你自己写下的任务计划（会话状态，用 plan_tasks 改写它）：\n")
                    .append(renderedPlan).append("\n\n");
        }
        builder.append("以下是智能体与用户的对话记录：\n\n");
        for (int index = 0; index < recent.size(); index++) {
            AgentMessage message = recent.get(index);
            String role = switch (message.getRole()) {
                case "user" -> "用户";
                case "tool" -> "工具结果";
                default -> "智能体";
            };
            String content = switch (message.getKind()) {
                case "thought" -> "（思考：）" + message.getContent();
                case "reflection" -> "（自检：）" + message.getContent();
                case "tool_call" -> "（调用了工具）" + message.getContent();
                case "tool_result" -> compactJson(message.getContent(), index == lastToolResultIndex
                        ? TOOL_RESULT_LATEST_CHARS : TOOL_RESULT_HISTORY_CHARS);
                case "answer" -> "（回复：）" + message.getContent();
                case "quiz" -> "（已用答题卡出题：）" + quizService.renderQuiz(message.getContent());
                case "quiz_answer" -> "（在答题卡上作答：）" + message.getContent();
                case "artifact" -> "（已用交付卡把代码交给用户：）" + renderArtifact(message.getContent());
                default -> message.getContent();
            };
            builder.append("【").append(role).append("】").append(content).append("\n\n");
            if (message.getAttachments() != null && !message.getAttachments().isEmpty()) {
                builder.append("【附件】")
                        .append(message.getAttachments().stream().map(AgentAttachment::name).toList())
                        .append("\n\n");
            }
        }
        if (lastError != null) {
            builder.append("【最近一次工具失败】").append(lastError).append("\n\n");
        }
        if (StringUtils.hasText(critique)) {
            builder.append("【自检结果】").append(critique).append("\n\n");
        }
        if (skillSelection != null && StringUtils.hasText(skillSelection.instructions())) {
            builder.append("当前激活的 Skill 规则：\n")
                    .append(skillSelection.instructions()).append("\n\n");
        }
        builder.append(RESPONSE_LANGUAGE_INSTRUCTION).append("\n");
        builder.append("请根据以上信息决定下一步：需要动手就返回 action，已经真正做完就返回 done=true 的最终回复。");
        return builder.toString();
    }

    private String snapshot(AgentRequestAssemblyInput input, String context,
                            List<AgentAttachment> attachments, List<Long> messageIds,
                            List<String> toolNames, String schemaHash, String modelRoute) {
        ObjectNode snapshot = JsonNodeFactory.instance.objectNode();
        snapshot.put("schemaVersion", 2);
        snapshot.put("step", input.stepNo());
        snapshot.put("contextChars", context.length());
        snapshot.put("instructionSetVersion", INSTRUCTION_SET_VERSION);
        snapshot.put("responseLanguagePolicy", "current-user-language-or-zh-when-ambiguous");
        snapshot.put("modelRoute", modelRoute);
        snapshot.put("toolSchemaSetHash", schemaHash);
        snapshot.put("remainingSteps", input.remainingSteps());
        snapshot.put("deepThinking", input.deepThinking());
        snapshot.put("planItemCount", input.plan().size());
        if (input.skillSelection() != null) {
            snapshot.put("skillConfidence", input.skillSelection().confidence());
            snapshot.put("skillReason", input.skillSelection().reason());
            snapshot.putArray("skillIds").addAll(input.skillSelection().skillIds().stream()
                    .map(JsonNodeFactory.instance::textNode).toList());
            snapshot.putArray("skillVersions").addAll(input.skillSelection().skills().stream()
                    .map(skill -> JsonNodeFactory.instance.textNode(skill.id() + "@" + skill.version()))
                    .toList());
        }
        ArrayNode messageNodes = snapshot.putArray("contextMessageIds");
        messageIds.forEach(messageNodes::add);
        ArrayNode toolNodes = snapshot.putArray("toolNames");
        toolNames.forEach(toolNodes::add);
        ArrayNode attachmentNodes = snapshot.putArray("attachments");
        attachments.forEach(attachment -> attachmentNodes.addObject()
                .put("url", attachment.url())
                .put("name", attachment.name())
                .put("contentType", attachment.contentType())
                .put("size", attachment.size()));
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("智能体步骤请求快照序列化失败", exception);
        }
    }

    /** Text and multimodal history use one window so a visible image always has visible text context. */
    private List<AgentMessage> recentWindow(List<AgentMessage> messages) {
        int limit = Math.max(5, conversationProperties.getContextMessageLimit());
        return messages.size() <= limit ? List.copyOf(messages)
                : List.copyOf(messages.subList(messages.size() - limit, messages.size()));
    }

    private List<AgentAttachment> recentAttachments(List<AgentMessage> messages) {
        LinkedHashMap<String, AgentAttachment> recent = new LinkedHashMap<>();
        for (int index = messages.size() - 1; index >= 0; index--) {
            AgentMessage message = messages.get(index);
            if (!"user".equals(message.getRole()) || message.getAttachments() == null) continue;
            for (AgentAttachment attachment : message.getAttachments()) {
                recent.putIfAbsent(attachment.url(), attachment);
                if (recent.size() >= 5) return reverseAttachments(recent);
            }
        }
        return reverseAttachments(recent);
    }

    private List<AgentAttachment> reverseAttachments(LinkedHashMap<String, AgentAttachment> attachments) {
        List<AgentAttachment> ordered = new ArrayList<>(attachments.values());
        java.util.Collections.reverse(ordered);
        return List.copyOf(ordered);
    }

    private int lastToolResultIndex(List<AgentMessage> recent) {
        for (int index = recent.size() - 1; index >= 0; index--) {
            if ("tool_result".equals(recent.get(index).getKind())) return index;
        }
        return -1;
    }

    private String renderArtifact(String content) {
        if (!StringUtils.hasText(content)) return "";
        try {
            JsonNode artifact = objectMapper.readTree(content);
            StringBuilder builder = new StringBuilder();
            String name = artifact.path("name").asText("");
            if (StringUtils.hasText(name)) builder.append(name).append(" ");
            JsonNode files = artifact.path("files");
            builder.append("共 ").append(files.size()).append(" 个文件：");
            for (int index = 0; index < files.size(); index++) {
                if (index > 0) builder.append("、");
                builder.append(files.get(index).path("path").asText());
            }
            String entry = artifact.path("entry").asText("");
            if (StringUtils.hasText(entry)) builder.append("；入口 ").append(entry);
            return builder.toString();
        } catch (Exception ignored) {
            return compactJson(content, TOOL_RESULT_HISTORY_CHARS);
        }
    }

    private String compactJson(String content, int maxLength) {
        if (!StringUtils.hasText(content)) return "";
        return content.length() <= maxLength ? content : content.substring(0, maxLength) + "…";
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) hex.append(String.format("%02x", item));
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}

/** Package-local input state for one request assembly; user text remains in referenced messages. */
record AgentRequestAssemblyInput(
        Long conversationId,
        Long userId,
        long runVersion,
        int stepNo,
        List<AgentMessage> messages,
        List<AgentPlanItem> plan,
        String memoryContext,
        JsonNode lastError,
        String critique,
        Set<String> allowedTools,
        int remainingSteps,
        boolean deepThinking,
        AgentSkillSelection skillSelection) {

    AgentRequestAssemblyInput {
        messages = List.copyOf(messages);
        plan = List.copyOf(plan);
        allowedTools = Set.copyOf(allowedTools);
    }

    /** Compatibility constructor for callers created before skill selection became explicit. */
    AgentRequestAssemblyInput(Long conversationId, Long userId, long runVersion, int stepNo,
                              List<AgentMessage> messages, List<AgentPlanItem> plan, String memoryContext,
                              JsonNode lastError, String critique, Set<String> allowedTools,
                              int remainingSteps, boolean deepThinking) {
        this(conversationId, userId, runVersion, stepNo, messages, plan, memoryContext, lastError,
                critique, allowedTools, remainingSteps, deepThinking, null);
    }
}

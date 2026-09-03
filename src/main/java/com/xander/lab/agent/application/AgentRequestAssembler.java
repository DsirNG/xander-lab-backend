package com.xander.lab.agent.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.agent.domain.AgentAttachment;
import com.xander.lab.agent.domain.AgentInputTurn;
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
        List<AgentInputTurn> turns = renderTurns(recent, input.plan(), input.memoryContext(),
                input.lastError(), input.critique(), input.skillSelection());
        String schemaHash = sha256(toolDescriptions);
        String modelRoute = "agent-config:" + defaultText(modelProperties.getModel(), "unconfigured");
        List<Long> messageIds = recent.stream().map(AgentMessage::getId)
                .filter(java.util.Objects::nonNull).toList();
        String snapshot = snapshot(input, turns, attachments, messageIds, toolNames, schemaHash, modelRoute);
        return new AgentStepRequest(input.conversationId(), input.userId(), input.runVersion(), input.stepNo(),
                turns, toolDescriptions, attachments, messageIds, toolNames, schemaHash, modelRoute,
                INSTRUCTION_SET_VERSION, input.remainingSteps(), input.deepThinking(), snapshot);
    }

    /**
     * 渲染这一步交给模型的输入轮次，顺序刻意是"稳定的在前、会变的在后"。
     *
     * <p>前缀缓存按字节前缀命中：从第一个不同的字节开始，后面全部要重新计算。
     * 对话记录是只追加的，所以把它放在最前面，同一轮里第 N 步的输入
     * 就是第 N-1 步的输入加上新追加的几条——前面那一大段能整段命中缓存。</p>
     *
     * <p>反过来的写法（早先就是这样）把长期记忆和任务计划放在最前面：
     * 模型中途调一次 plan_tasks 改了计划，整段对话记录的字节位置就全部后移，
     * 这一轮剩下每一步的提示词都得从头算。所以计划、失败回灌、自检批评、Skill 规则
     * 这些一轮之内会变的东西，全部收在最后那一轮 user 里。</p>
     *
     * <p>剩下的一处不稳定是有意留的：最新一条工具结果给 4000 字符预算、更早的给 800，
     * 于是新结果到达时上一条会被重新截短。它发生在对话记录的尾部，
     * 失效的只是尾巴而不是整段；相比让每条工具结果都占满预算，这样更省上下文。</p>
     */
    private List<AgentInputTurn> renderTurns(List<AgentMessage> recent, List<AgentPlanItem> plan,
                                             String memoryContext, JsonNode lastError, String critique,
                                             AgentSkillSelection skillSelection) {
        int lastToolResultIndex = lastToolResultIndex(recent);
        List<AgentInputTurn> turns = new ArrayList<>();
        for (int index = 0; index < recent.size(); index++) {
            AgentMessage message = recent.get(index);
            turns.add(renderMessage(message, index == lastToolResultIndex));
        }
        turns.add(AgentInputTurn.user(renderVolatileTail(plan, memoryContext, lastError, critique, skillSelection)));
        return AgentInputTurn.merged(turns);
    }

    /**
     * 一条历史消息渲染成一轮。role 由谁说的决定，正文里的【】只用来标出这句话是什么东西。
     *
     * <p>工具结果没有原生 tool role 可用，作为观察挂在 user 轮里；
     * 智能体自己的思考、工具调用、回复、卡片都归 assistant，模型因此能认出哪些是它自己干的。</p>
     */
    private AgentInputTurn renderMessage(AgentMessage message, boolean latestToolResult) {
        String content = switch (message.getKind()) {
            case "thought" -> "（思考：）" + message.getContent();
            case "reflection" -> "（自检：）" + message.getContent();
            case "tool_call" -> "（调用了工具）" + message.getContent();
            case "tool_result" -> "【工具结果】" + compactJson(message.getContent(),
                    latestToolResult ? TOOL_RESULT_LATEST_CHARS : TOOL_RESULT_HISTORY_CHARS);
            case "quiz" -> "（已用答题卡出题：）" + quizService.renderQuiz(message.getContent());
            case "quiz_answer" -> "（在答题卡上作答：）" + message.getContent();
            case "artifact" -> "（已用交付卡把代码交给用户：）" + renderArtifact(message.getContent());
            default -> message.getContent();
        };
        String attachments = renderAttachmentNames(message);
        String text = StringUtils.hasText(attachments) ? content + "\n\n" + attachments : content;
        return "assistant".equals(message.getRole()) ? AgentInputTurn.assistant(text) : AgentInputTurn.user(text);
    }

    private String renderAttachmentNames(AgentMessage message) {
        if (message.getAttachments() == null || message.getAttachments().isEmpty()) return "";
        return "【附件】" + message.getAttachments().stream().map(AgentAttachment::name).toList();
    }

    /** 一轮之内会变的会话状态，全部收在最后一轮 user 里，前面的对话记录因此保持字节稳定。 */
    private String renderVolatileTail(List<AgentPlanItem> plan, String memoryContext,
                                      JsonNode lastError, String critique,
                                      AgentSkillSelection skillSelection) {
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

    private String snapshot(AgentRequestAssemblyInput input, List<AgentInputTurn> turns,
                            List<AgentAttachment> attachments, List<Long> messageIds,
                            List<String> toolNames, String schemaHash, String modelRoute) {
        ObjectNode snapshot = JsonNodeFactory.instance.objectNode();
        snapshot.put("schemaVersion", 2);
        snapshot.put("step", input.stepNo());
        snapshot.put("contextChars", turns.stream().mapToInt(turn -> turn.text().length()).sum());
        snapshot.put("inputTurns", turns.size());
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

    /**
     * 这一步可见的那段对话记录。
     *
     * <p>地板是最近一个压缩检查点：它之前的消息已经被那份检查点代表了，
     * 再送一遍就等于压缩没发生。每一步都在这里重新裁，
     * 所以重启续跑、审批恢复这些不经过压缩调用的路径也照同一条地板走。</p>
     *
     * <p>条数上限只是兜底，正常路径上压缩会先把头部换掉、把条数压到上限之内。
     * 真的溢出时优先扔掉检查点后面那些最旧的消息：检查点代表的是一整段历史，
     * 丢它的代价远大于丢一条紧随其后的旧消息。文本与多模态共用这一个窗口，
     * 所以可见的图片总有可见的文字上下文。</p>
     */
    private List<AgentMessage> recentWindow(List<AgentMessage> messages) {
        List<AgentMessage> since = AgentCompactionService.afterLatestCheckpoint(messages);
        int limit = Math.max(5, conversationProperties.getContextMessageLimit());
        if (since.size() <= limit) return since;
        if (!AgentCompactionService.CHECKPOINT_KIND.equals(since.get(0).getKind())) {
            return List.copyOf(since.subList(since.size() - limit, since.size()));
        }
        List<AgentMessage> window = new ArrayList<>();
        window.add(since.get(0));
        window.addAll(since.subList(since.size() - (limit - 1), since.size()));
        return List.copyOf(window);
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

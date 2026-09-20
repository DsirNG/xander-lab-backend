package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xander.lab.agent.domain.AgentAttachment;
import com.xander.lab.agent.domain.AgentMemory;
import com.xander.lab.agent.domain.AgentMessage;
import com.xander.lab.agent.infrastructure.AgentMessageMapper;
import com.xander.lab.content.api.MediaAssetPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Memory 写入端：让智能体主动记住跨会话仍然成立的事。
 *
 * <p>只记"下次对话还用得上"的信息。会话内的临时细节靠对话历史即可，不该进长期记忆。</p>
 */
@Component
public class RememberTool implements AgentTool {

    private final AgentMemoryService memoryService;
    private final AgentMessageMapper messageMapper;
    private final ObjectMapper objectMapper;
    private final MediaAssetPort mediaAssetPort;

    @Autowired
    public RememberTool(AgentMemoryService memoryService, AgentMessageMapper messageMapper,
                        ObjectMapper objectMapper, MediaAssetPort mediaAssetPort) {
        this.memoryService = memoryService;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
        this.mediaAssetPort = mediaAssetPort;
    }

    /** Compatibility constructor for focused memory tests without conversation persistence. */
    public RememberTool(AgentMemoryService memoryService) {
        this(memoryService, null, null, null);
    }

    @Override
    public String name() {
        return "remember";
    }

    @Override
    public String description() {
        return "把跨会话长期有效的信息写入你自己的长期记忆，或删除已经失效的记忆。"
                + "该记的例子：用户的身份与职业、稳定偏好（回复语言、写作风格、常用平台）、"
                + "正在推进的长期目标、用户明确要求你记住的事。"
                + "不该记的例子：本轮对话的临时细节、可以从知识库或博客数据里查到的内容、一次性的问答。"
                + "memoryKey 是这条记忆的稳定标识（英文短横线命名，如 writing-style）；"
                + "复用同一个 memoryKey 就是更新，不要为同一件事新建多条。"
                + "用户表示不用再记、或信息已经失效时，用 action=delete 删除。"
                + "记忆会在后续每一轮自动注入上下文，不需要为了读取而调用工具。";
    }

    @Override
    public JsonNode parametersSchema() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode action = properties.putObject("action");
        action.put("type", "string");
        action.set("enum", factory.arrayNode().add("upsert").add("delete"));
        action.put("description", "upsert 写入或更新，delete 删除；缺省为 upsert");
        properties.putObject("memoryKey")
                .put("type", "string")
                .put("description", "记忆稳定标识，英文短横线命名，最多120个字符");
        ObjectNode kind = properties.putObject("kind");
        kind.put("type", "string");
        kind.set("enum", factory.arrayNode()
                .add("PROFILE").add("PREFERENCE").add("FACT").add("PROJECT"));
        kind.put("description", "PROFILE 用户画像、PREFERENCE 偏好、FACT 长期事实、PROJECT 在办事项；缺省 FACT");
        properties.putObject("content")
                .put("type", "string")
                .put("description", "记忆正文，一条只描述一件事，最多2000个字符；action=delete 时不需要");
        properties.putObject("mediaAssetId")
                .put("type", "integer")
                .put("description", "如果用户要求记住当前图片，填写图片素材 ID；未填写时自动绑定当前会话最近上传的图片");
        schema.putArray("required").add("memoryKey");
        return schema;
    }

    @Override
    public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
        return execute(AgentToolContext.ofUser(userId), args, onProgress);
    }

    @Override
    public JsonNode execute(AgentToolContext context, JsonNode args, ProgressListener onProgress) {
        onProgress.checkCancellation();
        String action = args.path("action").asText("upsert").trim().toLowerCase(java.util.Locale.ROOT);
        String memoryKey = args.path("memoryKey").asText("");
        ObjectNode outcome = JsonNodeFactory.instance.objectNode();

        if ("delete".equals(action)) {
            boolean removed = memoryService.forget(context.userId(), memoryKey);
            outcome.put("ok", true);
            outcome.put("action", "delete");
            outcome.put("removed", removed);
            outcome.put("hint", removed
                    ? "记忆已删除。"
                    : "没有找到这个 memoryKey，不要向用户声称删除成功。");
            return outcome;
        }

        Long mediaAssetId = args.hasNonNull("mediaAssetId")
                ? Long.valueOf(args.path("mediaAssetId").asLong(0)) : latestImageAssetId(context);
        if (mediaAssetId != null && mediaAssetId > 0 && mediaAssetPort != null
                && mediaAssetPort.findOwned(context.userId(), mediaAssetId) == null) {
            throw new IllegalArgumentException("图片素材不属于当前用户，无法写入记忆");
        }
        AgentMemory saved = mediaAssetId != null && mediaAssetId > 0
                ? memoryService.remember(context.userId(), memoryKey,
                args.path("kind").asText(""), args.path("content").asText(""),
                context.conversationId(), mediaAssetId)
                : memoryService.remember(context.userId(), memoryKey,
                args.path("kind").asText(""), args.path("content").asText(""),
                context.conversationId());
        outcome.put("ok", true);
        outcome.put("action", "upsert");
        outcome.put("memoryKey", saved.getMemoryKey());
        outcome.put("kind", saved.getKind());
        if (saved.getMediaAssetId() != null) outcome.put("mediaAssetId", saved.getMediaAssetId());
        outcome.put("hint", "已记住。后续每一轮会自动注入，不要重复写入同一件事。");
        return outcome;
    }

    private Long latestImageAssetId(AgentToolContext context) {
        if (!context.hasConversation() || messageMapper == null || objectMapper == null) return null;
        List<AgentMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationId, context.conversationId())
                .eq(AgentMessage::getRole, "user")
                .isNotNull(AgentMessage::getAttachmentsJson)
                .orderByDesc(AgentMessage::getId)
                .last("LIMIT 20"));
        for (AgentMessage message : messages) {
            try {
                if ("tool_result".equals(message.getKind())) {
                    JsonNode result = objectMapper.readTree(message.getContent());
                    if (result.path("assetId").canConvertToLong()
                            && result.path("url").asText("").startsWith("https://")) {
                        return result.path("assetId").asLong();
                    }
                }
                if (message.getAttachmentsJson() == null) continue;
                List<AgentAttachment> attachments = objectMapper.readValue(message.getAttachmentsJson(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, AgentAttachment.class));
                for (AgentAttachment attachment : attachments) {
                    if (attachment.image() && attachment.mediaAssetId() != null) return attachment.mediaAssetId();
                }
            } catch (Exception ignored) {
                // Ignore malformed legacy snapshots and keep searching older turns.
            }
        }
        return null;
    }
}

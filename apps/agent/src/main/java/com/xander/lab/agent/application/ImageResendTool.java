package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.agent.domain.AgentAttachment;
import com.xander.lab.agent.domain.AgentMessage;
import com.xander.lab.agent.infrastructure.AgentMessageMapper;
import com.xander.lab.content.api.MediaAssetPort;
import com.xander.lab.content.api.MediaAssetRef;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/** Reuses an existing user image instead of invoking an image generation model. */
@Component
public class ImageResendTool implements AgentTool {
    private final AgentMessageMapper messageMapper;
    private final MediaAssetPort mediaAssetPort;
    private final ObjectMapper objectMapper;

    public ImageResendTool(AgentMessageMapper messageMapper, MediaAssetPort mediaAssetPort,
                           ObjectMapper objectMapper) {
        this.messageMapper = messageMapper;
        this.mediaAssetPort = mediaAssetPort;
        this.objectMapper = objectMapper;
    }

    @Override public String name() { return "image_resend"; }

    @Override
    public String description() {
        return "把已有图片原样重新发送给用户，不生成新图片。用户说重新发、再发一次、把刚才那张图发给我时使用；"
                + "当前会话最近上传的图片使用 source=latest_uploaded；历史消息或长期记忆中的图片使用 source=asset_id 并传入 assetId。";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode source = properties.putObject("source");
        source.put("type", "string");
        ArrayNode values = source.putArray("enum");
        values.add("latest_uploaded").add("asset_id");
        properties.putObject("assetId").put("type", "integer");
        schema.putArray("required").add("source");
        return schema;
    }

    @Override
    public JsonNode execute(AgentToolContext context, JsonNode args, ProgressListener onProgress) {
        MediaAssetRef generated = null;
        AgentAttachment attachment;
        String source = args.path("source").asText("");
        if ("asset_id".equals(source)) {
            generated = mediaAssetPort.findOwned(context.userId(), args.path("assetId").asLong(0));
            if (generated == null) throw new IllegalArgumentException("找不到属于当前用户的图片素材");
            attachment = new AgentAttachment(generated.url(), generated.originalName(),
                    generated.contentType(), generated.size() == null ? 0L : generated.size());
        } else if ("latest_uploaded".equals(source)) {
            attachment = latestUploaded(context);
            if (attachment == null) throw new IllegalArgumentException("当前会话没有可重新发送的用户图片");
        } else {
            throw new IllegalArgumentException("image_resend 的 source 参数无效");
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("ok", true);
        result.put("tool", name());
        result.put("url", attachment.url());
        result.put("title", attachment.name());
        result.put("contentType", attachment.contentType());
        result.put("name", attachment.name());
        if (generated != null) result.put("assetId", generated.id());
        return result;
    }

    @Override
    public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
        return execute(AgentToolContext.ofUser(userId), args, onProgress);
    }

    private AgentAttachment latestUploaded(AgentToolContext context) {
        if (!context.hasConversation()) return null;
        List<AgentMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationId, context.conversationId())
                .eq(AgentMessage::getRole, "user")
                .isNotNull(AgentMessage::getAttachmentsJson)
                .orderByDesc(AgentMessage::getId)
                .last("LIMIT 20"));
        for (AgentMessage message : messages) {
            if (!StringUtils.hasText(message.getAttachmentsJson())) continue;
            try {
                List<AgentAttachment> attachments = objectMapper.readValue(message.getAttachmentsJson(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, AgentAttachment.class));
                for (AgentAttachment item : attachments) if (item.image()) return item;
            } catch (Exception ignored) {
                // Ignore malformed legacy snapshots and continue scanning older turns.
            }
        }
        return null;
    }
}

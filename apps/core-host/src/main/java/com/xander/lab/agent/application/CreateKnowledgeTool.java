package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.recitation.api.CreateRecitationMaterialRequest;
import com.xander.lab.recitation.api.RecitationMaterialView;
import com.xander.lab.recitation.application.RecitationService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Adds user-confirmed content to the personal knowledge mirror. */
@Component
public class CreateKnowledgeTool implements AgentTool {

    private final RecitationService recitationService;

    public CreateKnowledgeTool(RecitationService recitationService) {
        this.recitationService = recitationService;
    }

    @Override
    public String name() {
        return "create_knowledge";
    }

    @Override
    public String description() {
        return "把用户明确要求保存的内容加入其个人知识库，并建立初始掌握记录。"
                + "只有用户表达了保存、加入知识库、需要掌握或需要背诵的意图时才调用；不要把普通聊天内容擅自保存。"
                + "knowledgeType 可选 RECITATION（背诵原文）、CONCEPT（概念问答）、MATH（数学练习），"
                + "测试方式会按类型自动选择。返回 knowledgeUrl，可引导用户进入详情页测试。";
    }

    @Override
    public JsonNode parametersSchema() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("title")
                .put("type", "string")
                .put("description", "知识标题，最多100个字符");
        properties.putObject("content")
                .put("type", "string")
                .put("description", "用户确认要掌握的完整原文或知识内容，最多10000个字符");
        ObjectNode knowledgeType = properties.putObject("knowledgeType");
        knowledgeType.put("type", "string");
        knowledgeType.set("enum", factory.arrayNode().add("RECITATION").add("CONCEPT").add("MATH"));
        knowledgeType.put("description", "知识类型；无法判断时使用 CONCEPT");
        schema.putArray("required").add("title").add("content").add("knowledgeType");
        return schema;
    }

    @Override
    public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
        onProgress.checkCancellation();
        String title = args.path("title").asText("");
        String content = args.path("content").asText("");
        String knowledgeType = args.path("knowledgeType").asText("CONCEPT");
        if (!StringUtils.hasText(title) || !StringUtils.hasText(content)) {
            throw new IllegalArgumentException("创建知识需要 title 和 content");
        }

        RecitationMaterialView created = recitationService.createMaterial(userId,
                new CreateRecitationMaterialRequest(title, content, knowledgeType, null));
        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("ok", true);
        outcome.put("id", created.id());
        outcome.put("title", created.title());
        outcome.put("knowledgeType", created.knowledgeType());
        outcome.put("testMode", created.testMode());
        outcome.put("masteryScore", created.masteryScore());
        outcome.put("masteryLevel", created.masteryLevel());
        outcome.put("knowledgeUrl", "/workspace/knowledge/" + created.id());
        outcome.put("hint", "知识已加入个人知识镜像；请向用户说明当前掌握度为新知识，并提供详情页入口进行测试。");
        return outcome;
    }
}

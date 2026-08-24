package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.recitation.api.RecitationMaterialView;
import com.xander.lab.recitation.application.RecitationService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/** Reads the current user's personal knowledge and mastery overview. */
@Component
public class QueryKnowledgeTool implements AgentTool {

    private final RecitationService recitationService;

    public QueryKnowledgeTool(RecitationService recitationService) {
        this.recitationService = recitationService;
    }

    @Override
    public String name() {
        return "query_knowledge";
    }

    @Override
    public String description() {
        return "查询当前用户个人知识库中的知识点和真实掌握画像。"
                + "可回答‘我掌握了什么’、‘有哪些需要学习或复习’、‘某个知识点掌握度多少’。"
                + "返回总量、已掌握数、学习中数量、到期复习数，以及每项知识的掌握分、测试方式和详情页入口。";
    }

    @Override
    public JsonNode parametersSchema() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("search")
                .put("type", "string")
                .put("description", "按标题或原文内容搜索，可选");
        ObjectNode level = properties.putObject("masteryLevel");
        level.put("type", "string");
        level.set("enum", factory.arrayNode()
                .add("NEW").add("FAMILIAR").add("RECALLING").add("APPLYING").add("MASTERED"));
        level.put("description", "掌握等级过滤，可选");
        properties.putObject("dueOnly")
                .put("type", "boolean")
                .put("description", "true 时只返回已到复习时间的知识");
        properties.putObject("limit")
                .put("type", "integer")
                .put("description", "最多返回多少项，默认10，最大30");
        return schema;
    }

    @Override
    public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
        onProgress.checkCancellation();
        List<RecitationMaterialView> all = recitationService.listMaterials(userId);
        LocalDateTime now = LocalDateTime.now();
        String search = args.path("search").asText("").trim().toLowerCase(Locale.ROOT);
        String level = args.path("masteryLevel").asText("").trim().toUpperCase(Locale.ROOT);
        boolean dueOnly = args.path("dueOnly").asBoolean(false);
        int limit = Math.min(Math.max(args.path("limit").asInt(10), 1), 30);

        List<RecitationMaterialView> matched = all.stream()
                .filter(item -> !StringUtils.hasText(search) || contains(item, search))
                .filter(item -> !StringUtils.hasText(level) || level.equals(item.masteryLevel()))
                .filter(item -> !dueOnly || isDue(item, now))
                .limit(limit)
                .toList();

        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("ok", true);
        outcome.put("total", all.size());
        outcome.put("mastered", all.stream().filter(item -> "MASTERED".equals(item.masteryLevel())).count());
        outcome.put("learning", all.stream().filter(item -> !"MASTERED".equals(item.masteryLevel())).count());
        outcome.put("dueForReview", all.stream().filter(item -> isDue(item, now)).count());
        outcome.put("matched", matched.size());
        outcome.put("knowledgeHomeUrl", "/workspace/knowledge");
        ArrayNode records = outcome.putArray("items");
        matched.forEach(item -> appendItem(records, item, now));
        outcome.put("hint", matched.isEmpty()
                ? "没有匹配的个人知识记录；不要虚构知识点。可以询问用户是否要把当前内容加入知识库。"
                : "以上数据来自当前用户的个人知识镜像，可按掌握度总结并引导用户进入对应详情页测试。");
        return outcome;
    }

    private boolean contains(RecitationMaterialView item, String search) {
        return item.title().toLowerCase(Locale.ROOT).contains(search)
                || item.content().toLowerCase(Locale.ROOT).contains(search);
    }

    private boolean isDue(RecitationMaterialView item, LocalDateTime now) {
        return item.nextReviewAt() != null && !item.nextReviewAt().isAfter(now);
    }

    private void appendItem(ArrayNode records, RecitationMaterialView item, LocalDateTime now) {
        ObjectNode record = records.addObject();
        record.put("id", item.id());
        record.put("title", item.title());
        record.put("knowledgeType", item.knowledgeType());
        record.put("testMode", item.testMode());
        record.put("masteryScore", item.masteryScore());
        record.put("masteryLevel", item.masteryLevel());
        record.put("reviewCount", item.reviewCount());
        record.put("dueForReview", isDue(item, now));
        record.put("nextReviewAt", item.nextReviewAt() == null ? "" : item.nextReviewAt().toString());
        record.put("knowledgeUrl", "/workspace/knowledge/" + item.id());
    }
}

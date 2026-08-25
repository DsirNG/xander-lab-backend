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

    /** 默认摘录长度：够智能体判断"这条讲的是什么"，又不至于把上下文挤满。 */
    private static final int EXCERPT_CHARS = 300;
    /** fullContent=true 时的单条正文上限。 */
    private static final int FULL_CONTENT_CHARS = 2000;
    /** 取全文时收窄条数，避免一次把多条长正文全塞进上下文。 */
    private static final int FULL_CONTENT_ITEM_LIMIT = 3;

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
        return "查询当前用户个人知识库中的知识点、真实掌握画像和知识正文。"
                + "可回答‘我掌握了什么’、‘有哪些需要学习或复习’、‘某个知识点掌握度多少’，"
                + "也可以用它把用户自己记下的内容取出来作为回答依据——回答涉及用户已存知识时必须先查本工具，不要凭印象作答。"
                + "默认每条返回一段正文摘录；需要完整原文时传 fullContent=true（会收窄返回条数）。";
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
        properties.putObject("fullContent")
                .put("type", "boolean")
                .put("description", "true 时返回更完整的正文（每条最多 " + FULL_CONTENT_CHARS
                        + " 字符，最多 " + FULL_CONTENT_ITEM_LIMIT + " 条），用于据实引用用户原文");
        properties.putObject("limit")
                .put("type", "integer")
                .put("description", "最多返回多少项，默认10，最大30");
        return schema;
    }

    @Override
    public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
        onProgress.checkCancellation();
        List<RecitationMaterialView> all = recitationService.listMaterials(userId);
        long total = recitationService.countMaterials(userId);
        LocalDateTime now = LocalDateTime.now();
        String search = args.path("search").asText("").trim().toLowerCase(Locale.ROOT);
        String level = args.path("masteryLevel").asText("").trim().toUpperCase(Locale.ROOT);
        boolean dueOnly = args.path("dueOnly").asBoolean(false);
        boolean fullContent = args.path("fullContent").asBoolean(false);
        int limit = Math.min(Math.max(args.path("limit").asInt(10), 1), 30);
        if (fullContent) limit = Math.min(limit, FULL_CONTENT_ITEM_LIMIT);
        int excerptChars = fullContent ? FULL_CONTENT_CHARS : EXCERPT_CHARS;

        List<RecitationMaterialView> matched = all.stream()
                .filter(item -> !StringUtils.hasText(search) || contains(item, search))
                .filter(item -> !StringUtils.hasText(level) || level.equals(item.masteryLevel()))
                .filter(item -> !dueOnly || isDue(item, now))
                .limit(limit)
                .toList();

        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("ok", true);
        outcome.put("total", total);
        outcome.put("mastered", all.stream().filter(item -> "MASTERED".equals(item.masteryLevel())).count());
        outcome.put("learning", all.stream().filter(item -> !"MASTERED".equals(item.masteryLevel())).count());
        outcome.put("dueForReview", all.stream().filter(item -> isDue(item, now)).count());
        outcome.put("matched", matched.size());
        outcome.put("knowledgeHomeUrl", "/workspace/knowledge");
        if (total > all.size()) {
            // 统计口径来自全量计数，明细只取最近一批；说清楚才不会让智能体把两个数字混为一谈。
            outcome.put("statsScope", "mastered/learning/dueForReview 仅统计最近 " + all.size()
                    + " 条知识；total 是全部条数");
        }
        ArrayNode records = outcome.putArray("items");
        matched.forEach(item -> appendItem(records, item, now, search, excerptChars));
        outcome.put("hint", matched.isEmpty()
                ? "没有匹配的个人知识记录；不要虚构知识点。可以询问用户是否要把当前内容加入知识库。"
                : "items 里的 contentExcerpt 是用户自己存下的原文，可直接作为回答依据；"
                + "正文被截断时（truncated=true）如需完整内容，用同样的 search 加 fullContent=true 再查一次。");
        return outcome;
    }

    private boolean contains(RecitationMaterialView item, String search) {
        return item.title().toLowerCase(Locale.ROOT).contains(search)
                || item.content().toLowerCase(Locale.ROOT).contains(search);
    }

    private boolean isDue(RecitationMaterialView item, LocalDateTime now) {
        return item.nextReviewAt() != null && !item.nextReviewAt().isAfter(now);
    }

    private void appendItem(ArrayNode records, RecitationMaterialView item, LocalDateTime now,
                            String search, int excerptChars) {
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
        String content = item.content() == null ? "" : item.content();
        record.put("contentLength", content.length());
        record.put("contentExcerpt", excerpt(content, search, excerptChars));
        record.put("truncated", content.length() > excerptChars);
    }

    /**
     * 截取正文摘录。命中搜索词时以命中位置为中心开窗，否则从开头截取。
     *
     * <p>以命中处开窗而不是永远截开头：用户问的往往正是正文中段的某句话，
     * 只给开头等于让智能体拿着看不到答案的材料去回答。</p>
     */
    private String excerpt(String content, String search, int maxChars) {
        if (content.isEmpty()) return "";
        if (content.length() <= maxChars) return content;
        int matchIndex = StringUtils.hasText(search)
                ? content.toLowerCase(Locale.ROOT).indexOf(search) : -1;
        if (matchIndex < 0) return content.substring(0, maxChars) + "…";
        int start = Math.max(0, matchIndex - maxChars / 3);
        int end = Math.min(content.length(), start + maxChars);
        String window = content.substring(start, end);
        return (start > 0 ? "…" : "") + window + (end < content.length() ? "…" : "");
    }
}

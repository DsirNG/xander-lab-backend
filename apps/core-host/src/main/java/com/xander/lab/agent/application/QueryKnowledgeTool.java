package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.recitation.spi.MaterialFilter;
import com.xander.lab.recitation.spi.MaterialView;
import com.xander.lab.recitation.spi.RecitationMaterialPort;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reads the current user's personal knowledge and mastery overview. */
@Component
public class QueryKnowledgeTool implements AgentTool {

    /** 默认摘录长度：够智能体判断"这条讲的是什么"，又不至于把上下文挤满。 */
    private static final int EXCERPT_CHARS = 300;
    /** fullContent=true 时的单条正文上限。 */
    private static final int FULL_CONTENT_CHARS = 2000;
    /** 取全文时收窄条数，避免一次把多条长正文全塞进上下文。 */
    private static final int FULL_CONTENT_ITEM_LIMIT = 3;
    /** 合法掌握等级；模型给了别的值就当没传过滤条件，而不是拿它去查出空结果。 */
    private static final Set<String> LEVELS =
            Set.of("NEW", "FAMILIAR", "RECALLING", "APPLYING", "MASTERED");
    private static final MaterialFilter MASTERED_FILTER =
            new MaterialFilter(null, "MASTERED", false, 1);
    private static final MaterialFilter DUE_FILTER =
            new MaterialFilter(null, null, true, 1);

    private final RecitationMaterialPort materialPort;

    public QueryKnowledgeTool(RecitationMaterialPort materialPort) {
        this.materialPort = materialPort;
    }

    @Override
    public String name() {
        return "query_knowledge";
    }

    /** 纯查询：不写任何状态，可与同批其它只读工具并发执行。 */
    @Override
    public boolean readOnly() {
        return true;
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
        LocalDateTime now = LocalDateTime.now();
        String search = args.path("search").asText("").trim();
        String level = normalizedLevel(args.path("masteryLevel").asText(""));
        boolean dueOnly = args.path("dueOnly").asBoolean(false);
        boolean fullContent = args.path("fullContent").asBoolean(false);
        int limit = Math.min(Math.max(args.path("limit").asInt(10), 1), 30);
        if (fullContent) limit = Math.min(limit, FULL_CONTENT_ITEM_LIMIT);
        int excerptChars = fullContent ? FULL_CONTENT_CHARS : EXCERPT_CHARS;

        // 过滤条件下推到 SQL。曾经的做法是取"最近 100 条"再在内存里筛，
        // 知识超过 100 条之后智能体是查不到也不报错，只会退回凭印象作答。
        MaterialFilter filter = new MaterialFilter(search, level, dueOnly, limit);
        List<MaterialView> matched = materialPort.searchMaterials(userId, filter);
        long matchedTotal = materialPort.countMaterials(userId, filter);
        long total = materialPort.countMaterials(userId, MaterialFilter.all());
        long mastered = materialPort.countMaterials(userId, MASTERED_FILTER);
        long due = materialPort.countMaterials(userId, DUE_FILTER);

        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("ok", true);
        outcome.put("total", total);
        outcome.put("mastered", mastered);
        outcome.put("learning", Math.max(0L, total - mastered));
        outcome.put("dueForReview", due);
        outcome.put("matched", matched.size());
        outcome.put("matchedTotal", matchedTotal);
        outcome.put("knowledgeHomeUrl", "/workspace/knowledge");
        if (matchedTotal > matched.size()) {
            // 说清楚"还有多少条没返回"，智能体才不会把一页明细当成全部证据。
            outcome.put("moreMatches", "还有 " + (matchedTotal - matched.size())
                    + " 条符合条件的知识没有返回，可调大 limit 或把 search 写得更具体");
        }
        ArrayNode records = outcome.putArray("items");
        matched.forEach(item -> appendItem(records, item, now, search, excerptChars));
        outcome.put("hint", matched.isEmpty()
                ? "没有匹配的个人知识记录；不要虚构知识点。可以询问用户是否要把当前内容加入知识库。"
                : "items 里的 contentExcerpt 是用户自己存下的原文，可直接作为回答依据；"
                + "正文被截断时（truncated=true）如需完整内容，用同样的 search 加 fullContent=true 再查一次。");
        return outcome;
    }

    private String normalizedLevel(String level) {
        String normalized = level == null ? "" : level.trim().toUpperCase(Locale.ROOT);
        return LEVELS.contains(normalized) ? normalized : "";
    }

    private boolean isDue(MaterialView item, LocalDateTime now) {
        return item.nextReviewAt() != null && !item.nextReviewAt().isAfter(now);
    }

    private void appendItem(ArrayNode records, MaterialView item, LocalDateTime now,
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
                ? content.toLowerCase(Locale.ROOT).indexOf(search.toLowerCase(Locale.ROOT)) : -1;
        if (matchIndex < 0) return content.substring(0, maxChars) + "…";
        int start = Math.max(0, matchIndex - maxChars / 3);
        int end = Math.min(content.length(), start + maxChars);
        String window = content.substring(start, end);
        return (start > 0 ? "…" : "") + window + (end < content.length() ? "…" : "");
    }
}

package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.recitation.spi.QuizAttemptView;
import com.xander.lab.recitation.spi.QuizSessionView;
import com.xander.lab.recitation.spi.RecitationQuizPort;
import org.springframework.stereotype.Component;

/**
 * 开一场知识测验：取回出题需要的原文、掌握画像和历史错处。
 *
 * <p>这里没有另建一个"出题模型"。智能体本人就是出题者：它已经拿着知识原文和整段对话，
 * 比任何独立的出题服务都更清楚该考什么、上次错在哪。工具只负责把材料摆到它面前，
 * 出题、追问、判分都发生在它自己的推理里。</p>
 *
 * <p>出好的题目交给 {@link EmitQuizTool} 发成答题卡，收口动作是 {@link GradeAnswerTool}：
 * 不调用它，这场测验就只是聊天，掌握度不会动。</p>
 */
@Component
public class QuizKnowledgeTool implements AgentTool {

    public static final String NAME = "quiz_knowledge";

    private final RecitationQuizPort quizPort;
    private final ObjectMapper objectMapper;

    public QuizKnowledgeTool(RecitationQuizPort quizPort, ObjectMapper objectMapper) {
        this.quizPort = quizPort;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "对用户个人知识库里的某一条知识开一场测验，取回它的完整原文、当前掌握度和最近几次测验记录。"
                + "使用时机：用户说“考考我”“测一下我掌握没有”，或者你在复习建议里发现某条知识已到期需要检验。"
                + "不知道 materialId 就先用 query_knowledge 找到它。"
                + "拿到结果后由你自己出题：3 到 5 题，覆盖原文的关键点，"
                + "recentQuizzes 里上次答错的地方要优先再考一遍；"
                + "题目不要写在回复正文里，紧接着调用 " + EmitQuizTool.NAME + " 把它们发成一张答题卡，"
                + "并把返回的 quizSessionId 原样交给它；发完卡就以 done=true 结束这一轮等用户作答。"
                + "用户在答题卡上提交后，必须把同一个 quizSessionId 交给 grade_answer 记录评分，"
                + "否则这次测验不计入掌握度。";
    }

    @Override
    public JsonNode parametersSchema() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("materialId")
                .put("type", "integer")
                .put("description", "要测验的知识 id，来自 query_knowledge 返回的 items[].id");
        schema.putArray("required").add("materialId");
        return schema;
    }

    @Override
    public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
        return execute(AgentToolContext.ofUser(userId), args, onProgress);
    }

    @Override
    public JsonNode execute(AgentToolContext context, JsonNode args, ProgressListener onProgress) {
        onProgress.checkCancellation();
        Long materialId = args.path("materialId").isNumber() ? args.path("materialId").asLong() : null;
        QuizSessionView session = quizPort.openQuiz(
                context.userId(), materialId, context.conversationId());

        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("ok", true);
        outcome.put("quizSessionId", session.quizSessionId());
        outcome.put("materialId", session.materialId());
        outcome.put("title", session.title());
        outcome.put("knowledgeType", session.knowledgeType());
        outcome.put("testMode", session.testMode());
        outcome.put("masteryScore", session.masteryScore());
        outcome.put("masteryLevel", session.masteryLevel());
        outcome.put("reviewCount", session.reviewCount());
        outcome.put("dueForReview", session.dueForReview());
        outcome.put("lastReviewAt", text(session.lastReviewAt()));
        outcome.put("nextReviewAt", text(session.nextReviewAt()));
        outcome.put("knowledgeUrl", "/workspace/knowledge/" + session.materialId());
        outcome.put("contentLength", session.contentLength());
        outcome.put("content", session.content());
        outcome.put("contentTruncated", session.contentTruncated());
        appendHistory(outcome.putArray("recentQuizzes"), session);
        outcome.put("hint", hint(session));
        return outcome;
    }

    private void appendHistory(ArrayNode history, QuizSessionView session) {
        for (QuizAttemptView quiz : session.recentQuizzes()) {
            ObjectNode record = history.addObject();
            record.put("score", quiz.score());
            record.put("questionCount", quiz.questionCount());
            record.put("correctCount", quiz.correctCount());
            record.put("verdict", quiz.verdict() == null ? "" : quiz.verdict());
            record.put("createdAt", text(quiz.createdAt()));
            // 逐题证据原样带回：智能体据此知道上次问过什么、用户在哪一题上栽了。
            record.set("items", parseItems(quiz.itemsJson()));
        }
    }

    /**
     * 把历史证据的 JSON 文本解析成节点。
     *
     * <p>契约侧只给文本（域侧那个字段的类型是 {@code Object}，没有形状可描述），
     * 所以解析与容错都在这一侧。三条分支合起来与旧行为逐字等价：</p>
     * <ul>
     *   <li>空 / {@code null} ⇒ 空数组。旧路径里 {@code items} 是 {@code null}，
     *       {@code valueToTree(null)} 也产出空数组。</li>
     *   <li>非法 JSON ⇒ 空数组。旧路径里域侧反序列化失败会吞掉异常留 {@code null}，
     *       结果同样是空数组。</li>
     *   <li>合法 JSON ⇒ 原样解析。**注意必须走 {@code readTree} 而不是任何 toString** ——
     *       智能体拿到的必须是真的 JSON，不是某个对象的字符串形式。</li>
     * </ul>
     */
    private JsonNode parseItems(String itemsJson) {
        if (itemsJson == null || itemsJson.isBlank()) return JsonNodeFactory.instance.arrayNode();
        try {
            return objectMapper.readTree(itemsJson);
        } catch (Exception error) {
            return JsonNodeFactory.instance.arrayNode();
        }
    }

    private String hint(QuizSessionView session) {
        StringBuilder hint = new StringBuilder();
        hint.append("content 是用户自己存下的原文，出题和判分都以它为准，不要考原文里没有的内容。");
        if (session.contentTruncated()) {
            hint.append("正文超长已被截断，只考你看到的部分。");
        }
        if (session.recentQuizzes().isEmpty()) {
            hint.append("这条知识还没有测验记录，按原文的关键点出题。");
        } else {
            hint.append("recentQuizzes 是历史评分，别原样重复上次的题目，但要覆盖上次答错的点。");
        }
        hint.append("现在把题目交给 " + EmitQuizTool.NAME + " 发成一张答题卡（quizSessionId="
                + session.quizSessionId() + "），题面里先别给答案，回复正文也不要重复题面；"
                + "发完卡就以 done=true 结束这一轮让用户作答。"
                + "用户在答题卡上提交后你会收到逐题作答，那一轮再把同一个 quizSessionId 交给 grade_answer 逐题判分。"
                + "本轮不要为了等用户作答而继续输出没有工具的决策。");
        return hint.toString();
    }

    private String text(java.time.LocalDateTime value) {
        return value == null ? "" : value.toString();
    }
}

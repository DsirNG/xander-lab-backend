package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.recitation.api.KnowledgeQuizAttemptView;
import com.xander.lab.recitation.application.KnowledgeQuizService;
import org.springframework.stereotype.Component;

/**
 * 开一场知识测验：取回出题需要的原文、掌握画像和历史错处。
 *
 * <p>这里没有另建一个"出题模型"。智能体本人就是出题者：它已经拿着知识原文和整段对话，
 * 比任何独立的出题服务都更清楚该考什么、上次错在哪。工具只负责把材料摆到它面前，
 * 出题、追问、判分都发生在它自己的推理里。</p>
 *
 * <p>与之配套的收口动作是 {@link GradeAnswerTool}：不调用它，这场测验就只是聊天，
 * 掌握度不会动。</p>
 */
@Component
public class QuizKnowledgeTool implements AgentTool {

    public static final String NAME = "quiz_knowledge";

    private final KnowledgeQuizService quizService;
    private final ObjectMapper objectMapper;

    public QuizKnowledgeTool(KnowledgeQuizService quizService, ObjectMapper objectMapper) {
        this.quizService = quizService;
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
                + "题目写在你的回复里让用户作答，同一条回复里不要把答案写出来。"
                + "用户答完后必须调用 grade_answer 记录评分，否则这次测验不计入掌握度。";
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
        onProgress.checkCancellation();
        Long materialId = args.path("materialId").isNumber() ? args.path("materialId").asLong() : null;
        KnowledgeQuizService.QuizSession session = quizService.openQuiz(userId, materialId);

        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("ok", true);
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

    private void appendHistory(ArrayNode history, KnowledgeQuizService.QuizSession session) {
        for (KnowledgeQuizAttemptView quiz : session.recentQuizzes()) {
            ObjectNode record = history.addObject();
            record.put("score", quiz.score());
            record.put("questionCount", quiz.questionCount());
            record.put("correctCount", quiz.correctCount());
            record.put("verdict", quiz.verdict() == null ? "" : quiz.verdict());
            record.put("createdAt", text(quiz.createdAt()));
            // 逐题证据原样带回：智能体据此知道上次问过什么、用户在哪一题上栽了。
            record.set("items", quiz.items() == null
                    ? JsonNodeFactory.instance.arrayNode() : objectMapper.valueToTree(quiz.items()));
        }
    }

    private String hint(KnowledgeQuizService.QuizSession session) {
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
        hint.append("现在把题目写进你的回复让用户作答（先别给答案），等用户答完再调用 grade_answer 逐题判分。");
        return hint.toString();
    }

    private String text(java.time.LocalDateTime value) {
        return value == null ? "" : value.toString();
    }
}

package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * 把一场测验的题目发成一张答题卡，交给前端渲染成可点选、可翻页的界面。
 *
 * <p>为什么不让模型把题目写在回复正文里：正文是 Markdown，用户只能照着手打答案，
 * 判分时模型还得从一段自由文本里猜哪句对应第几题。答题卡把题目变成结构化负载，
 * 用户点选后原样回传每一题的作答，判分证据因此是确定的。</p>
 *
 * <p>上游是 {@link QuizKnowledgeTool}（拿到原文和 quizSessionId），下游是
 * {@link GradeAnswerTool}（用同一个 quizSessionId 逐题判分）。发卡本身不动掌握度。</p>
 */
@Component
public class EmitQuizTool implements AgentTool {

    /** 编排循环据此把工具结果提升成 kind=quiz 的消息和 quiz 事件。 */
    public static final String NAME = "emit_quiz";
    /** 结果里承载答题卡负载的字段名，编排循环按它取出要下发的内容。 */
    public static final String QUIZ_FIELD = "quiz";

    /** 单张卡的题量上限：一次考太多题用户答不完，掌握度也失真。 */
    static final int MAX_QUESTIONS = 10;
    /** 单题选项上限；超出的选项直接丢弃。 */
    static final int MAX_OPTIONS = 8;
    /** 选择题至少要有的选项数，不够就退化成主观题，免得出现只有一个答案可选的卡。 */
    static final int MIN_OPTIONS = 2;
    static final int MAX_TITLE_CHARS = 60;
    static final int MAX_PROMPT_CHARS = 500;
    static final int MAX_OPTION_CHARS = 200;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "把你出好的题目发成一张答题卡，用户在卡片上逐题作答后一次性提交。"
                + "使用时机：调用 quiz_knowledge 拿到知识原文之后，紧接着调用本工具下发题目；"
                + "题目数量 3 到 5 题最合适，最多 " + MAX_QUESTIONS + " 题。"
                + "quizSessionId 必须原样使用 quiz_knowledge 返回的那一个，否则这场测验无法判分。"
                + "题面里不要写答案，也不要在回复正文里把题目重复一遍——用户已经在卡片上看到了。"
                + "发完卡就以 done=true 结束这一轮等用户作答；"
                + "用户提交后你会收到逐题作答，那一轮再把同一个 quizSessionId 交给 grade_answer。";
    }

    @Override
    public JsonNode parametersSchema() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("quizSessionId")
                .put("type", "integer")
                .put("description", "quiz_knowledge 返回的测验会话 id，必须原样传回；答题卡靠它绑定到这场测验");
        properties.putObject("title")
                .put("type", "string")
                .put("description", "答题卡标题，通常就是这条知识的标题，最多 " + MAX_TITLE_CHARS + " 个字符，可选");
        ObjectNode questions = properties.putObject("questions");
        questions.put("type", "array");
        questions.put("description", "题目列表，3 到 5 题最合适，最多 " + MAX_QUESTIONS + " 题");
        ObjectNode question = questions.putObject("items");
        question.put("type", "object");
        ObjectNode questionProperties = question.putObject("properties");
        questionProperties.putObject("prompt")
                .put("type", "string")
                .put("description", "题面，最多 " + MAX_PROMPT_CHARS + " 个字符；不要把答案写进题面");
        ObjectNode type = questionProperties.putObject("type");
        type.put("type", "string");
        type.set("enum", factory.arrayNode().add("single").add("multiple").add("text"));
        type.put("description", "题型：single 单选、multiple 多选、text 主观题（用户自己填写，不给选项）");
        ObjectNode options = questionProperties.putObject("options");
        options.put("type", "array");
        options.putObject("items").put("type", "string");
        options.put("description", "选项原文，single/multiple 需要 " + MIN_OPTIONS + " 到 " + MAX_OPTIONS
                + " 项且其中必须有正确答案；text 题留空。用户提交回来的就是选项原文");
        question.putArray("required").add("prompt").add("type");
        schema.putArray("required").add("quizSessionId").add("questions");
        return schema;
    }

    @Override
    public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
        throw new IllegalStateException("emit_quiz 必须在会话上下文中执行");
    }

    @Override
    public JsonNode execute(AgentToolContext context, JsonNode args, ProgressListener onProgress) {
        onProgress.checkCancellation();
        // 答题卡是发给某一个会话的界面，没有会话就没有下发目标，不如直接报错让模型改用正文出题。
        if (!context.hasConversation()) {
            throw new IllegalStateException("emit_quiz 必须在会话上下文中执行");
        }
        long quizSessionId = args.path("quizSessionId").isNumber() ? args.path("quizSessionId").asLong() : 0L;
        if (quizSessionId <= 0) {
            throw new IllegalArgumentException("emit_quiz 需要 quiz_knowledge 返回的 quizSessionId");
        }
        ObjectNode quiz = JsonNodeFactory.instance.objectNode();
        quiz.put("type", AgentQuizService.PAYLOAD_TYPE);
        // 卡片 id 直接用测验会话 id：用户提交时原样带回，判分不必再做一次映射。
        quiz.put("id", String.valueOf(quizSessionId));
        quiz.put("title", limit(args.path("title").asText(""), MAX_TITLE_CHARS));
        int count = appendQuestions(quiz.putArray("questions"), args.path("questions"));

        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("ok", true);
        outcome.put("quizSessionId", quizSessionId);
        outcome.put("questionCount", count);
        outcome.set(QUIZ_FIELD, quiz);
        outcome.put("hint", "答题卡已经发给用户了，回复正文里不要重复题面，只用一句话请他在卡片上作答。"
                + "现在就以 done=true 结束这一轮；用户提交后你会收到逐题作答，"
                + "那一轮把 quizSessionId=" + quizSessionId + " 交给 grade_answer 判分，否则这场测验不计入掌握度。");
        return outcome;
    }

    private int appendQuestions(ArrayNode target, JsonNode questions) {
        if (!questions.isArray() || questions.isEmpty()) {
            throw new IllegalArgumentException("emit_quiz 需要非空的 questions 数组");
        }
        for (JsonNode question : questions) {
            String prompt = limit(question.path("prompt").asText(""), MAX_PROMPT_CHARS);
            if (prompt.isBlank()) continue;
            ObjectNode normalized = target.addObject();
            // 题号由服务端按顺序生成：用户提交时按它回指，模型不必自己维护一套稳定 id。
            normalized.put("id", "q" + target.size());
            normalized.put("prompt", prompt);
            ArrayNode options = normalized.putArray("options");
            appendOptions(options, question.path("options"));
            String type = question.path("type").asText("single");
            // 选项不够的选择题退化成主观题：前端没有选项就渲染成输入框，用户仍能作答。
            boolean choice = ("single".equals(type) || "multiple".equals(type)) && options.size() >= MIN_OPTIONS;
            normalized.put("type", choice ? type : "text");
            if (!choice) options.removeAll();
            if (target.size() >= MAX_QUESTIONS) break;
        }
        if (target.isEmpty()) {
            throw new IllegalArgumentException("emit_quiz 的每道题都需要 prompt");
        }
        return target.size();
    }

    private void appendOptions(ArrayNode target, JsonNode options) {
        if (!options.isArray()) return;
        for (JsonNode option : options) {
            String text = limit(option.isObject() ? option.path("label").asText("") : option.asText(""),
                    MAX_OPTION_CHARS);
            if (text.isBlank()) continue;
            target.add(text);
            if (target.size() >= MAX_OPTIONS) return;
        }
    }

    private String limit(String value, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}

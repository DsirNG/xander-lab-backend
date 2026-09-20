package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.recitation.spi.GradedItemDraft;
import com.xander.lab.recitation.spi.QuizOutcomeView;
import com.xander.lab.recitation.spi.RecitationQuizPort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Evaluator：把一场测验的逐题判分落库，并据此推进掌握度与复习时间。
 *
 * <p>刻意不接受"总分"参数。总分是可以随口给的，而逐题判分必须逐题给出题目和用户的原话，
 * 分数由服务端从每题得分算出来——想虚高就得先编出一整套证据，代价大得多。
 * 这也让掌握度这个数字有了出处：知识页能把当时的题目和作答摊开给用户看。</p>
 */
@Component
public class GradeAnswerTool implements AgentTool {

    public static final String NAME = "grade_answer";

    private final RecitationQuizPort quizPort;

    public GradeAnswerTool(RecitationQuizPort quizPort) {
        this.quizPort = quizPort;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "记录一次测验的逐题判分，并按得分更新这条知识的掌握等级和下次复习时间。"
                + "使用时机：用户答完你用 quiz_knowledge 出的题之后的那一轮，立刻调用一次"
                + "——不调用这次测验就白考了；同一轮里用户还没作答时不要调用，先收口等用户回答。"
                + "必须原样使用 quiz_knowledge 返回的 quizSessionId；它把评分绑定到真实出题记录，并让重复调用安全复用原结果。"
                + "总分由服务端按每题 credit 自动算出，你不要自己报总分。"
                + "判分要严格：答不出、答错就给 0，只说对一半给 0.5，完整正确才给 1；"
                + "userAnswer 要填用户的原话而不是你的转述。"
                + "虚高的分数会把没掌握的知识排到 30 天后才复习，是在害用户。";
    }

    @Override
    public Optional<AgentPromptSection> promptSection() {
        return Optional.of(AgentPromptSection.assessment(
                "用户提交答题卡后，先调用 " + NAME + " 完成逐题判分，"
                        + "再以 done=true 给出正确/错误、正确答案和错因的讲评；"
                        + "不要在同一轮直接下发纠错练习。讲评结束后，等用户确认或提出继续练习，再出更聚焦的题。"));
    }

    @Override
    public JsonNode parametersSchema() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("quizSessionId")
                .put("type", "integer")
                .put("description", "quiz_knowledge 返回的测验会话 id，必须原样传回");
        properties.putObject("verdict")
                .put("type", "string")
                .put("description", "面向用户的总体讲评，最多 " + RecitationQuizPort.MAX_VERDICT_CHARS + " 个字符，可选");
        ObjectNode items = properties.putObject("items");
        items.put("type", "array");
        items.put("description", "逐题判分，最多 " + RecitationQuizPort.MAX_ITEMS + " 题；每题都必须给 credit");
        ObjectNode item = items.putObject("items");
        item.put("type", "object");
        ObjectNode itemProperties = item.putObject("properties");
        itemProperties.putObject("question")
                .put("type", "string")
                .put("description", "你实际问出的题目原文");
        itemProperties.putObject("userAnswer")
                .put("type", "string")
                .put("description", "用户的原话作答；没答就留空");
        itemProperties.putObject("credit")
                .put("type", "number")
                .put("description", "本题得分比例：0 完全不对、0.5 答对一半、1 完全正确");
        itemProperties.putObject("comment")
                .put("type", "string")
                .put("description", "本题点评：错在哪、正确答案是什么，可选");
        item.putArray("required").add("question").add("credit");
        schema.putArray("required").add("quizSessionId").add("items");
        return schema;
    }

    @Override
    public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
        return grade(userId, null, args, onProgress);
    }

    @Override
    public JsonNode execute(AgentToolContext context, JsonNode args, ProgressListener onProgress) {
        // 会话 id 只作为评分出处留档，缺了也能判分，所以不像 plan_tasks 那样强制要求会话。
        return grade(context.userId(), context.conversationId(), args, onProgress);
    }

    private JsonNode grade(Long userId, Long conversationId, JsonNode args, ProgressListener onProgress) {
        onProgress.checkCancellation();
        Long quizSessionId = args.path("quizSessionId").isNumber() ? args.path("quizSessionId").asLong() : null;
        QuizOutcomeView result = quizPort.recordQuiz(userId, quizSessionId, conversationId,
                args.path("verdict").asText(""), readItems(args.path("items")));

        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("ok", true);
        outcome.put("attemptId", result.attemptId());
        outcome.put("materialId", result.materialId());
        outcome.put("title", result.title());
        outcome.put("score", result.score());
        outcome.put("questionCount", result.questionCount());
        outcome.put("correctCount", result.correctCount());
        outcome.put("masteryLevel", result.masteryLevel());
        outcome.put("previousMasteryLevel", result.previousMasteryLevel());
        outcome.put("masteryChanged", !result.masteryLevel().equals(result.previousMasteryLevel()));
        outcome.put("reviewCount", result.reviewCount());
        outcome.put("nextReviewAt", result.nextReviewAt() == null ? "" : result.nextReviewAt().toString());
        outcome.put("knowledgeUrl", "/workspace/knowledge/" + result.materialId());
        outcome.put("hint", "掌握度已经落库，score 是服务端按逐题得分算出的，不要在回复里改写它。"
                + "把得分、错在哪、建议的下次复习时间告诉用户；"
                + "分数不理想时直接就着答错的点讲清楚，别只报个分数。");
        // 判分结果需要先完成用户可见讲评；否则模型容易跳过反馈，直接再次发题。
        outcome.put("hint", outcome.path("hint").asText()
                + "本轮判分后必须先以 done=true 把逐题正误、正确答案和错因讲评给用户；"
                + "不要在同一轮调用 emit_quiz 下发纠错练习，等用户读完讲评后再按其意愿继续。");
        return outcome;
    }

    /** 读取逐题证据。缺 credit 的题保持为 null 交给服务端拒绝，这里不替智能体补 0 分。 */
    private List<GradedItemDraft> readItems(JsonNode items) {
        List<GradedItemDraft> parsed = new ArrayList<>();
        if (!items.isArray()) return parsed;
        for (JsonNode item : items) {
            if (!item.isObject()) continue;
            parsed.add(new GradedItemDraft(
                    item.path("question").asText(""),
                    item.path("userAnswer").asText(""),
                    item.path("comment").asText(""),
                    credit(item)));
        }
        return parsed;
    }

    /**
     * 解析本题得分。
     *
     * <p>除了 credit 也接受布尔 correct：模型经常按"对/错"两分法作答，
     * 硬要它改用小数只会换来一次被拒绝的重试。</p>
     */
    private BigDecimal credit(JsonNode item) {
        JsonNode credit = item.path("credit");
        if (credit.isNumber()) return credit.decimalValue();
        if (credit.isTextual() && !credit.asText().isBlank()) {
            try {
                return new BigDecimal(credit.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        JsonNode correct = item.path("correct");
        if (correct.isBoolean()) return correct.asBoolean() ? BigDecimal.ONE : BigDecimal.ZERO;
        return null;
    }
}

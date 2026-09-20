package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.domain.AgentMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 答题卡两个方向的翻译：出题负载怎么读、答卷负载怎么读。
 *
 * <p>答题卡是唯一一种"消息正文是给前端看的 JSON"的消息类型（kind=quiz）。模型不认识 JSON 里的
 * 渲染细节，前端不认识模型的对话协议，所以两边的格式在这里收口：出题时把负载渲染成题面清单
 * 注入上下文，作答时把前端提交的负载翻译成逐题作答记录再落库。</p>
 *
 * <p>作答一律在入口翻译成可读文本而不是原样存 JSON：历史、会话标题、分享页、上下文窗口
 * 都只需要认识纯文本，不必各自再解一遍这个内部格式。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentQuizService {

    /** 出题负载的判别值；前端据此把消息渲染成答题卡而不是正文。 */
    public static final String PAYLOAD_TYPE = "quiz";
    /** 答卷负载的判别值；前端提交答案时用它包裹。 */
    public static final String SUBMIT_TYPE = "submit_quiz";

    /** 单题作答的截断长度：作答要原样交给模型判分，但不能让一题撑爆上下文窗口。 */
    private static final int MAX_ANSWER_CHARS = 1000;
    private static final String UNANSWERED = "（未作答）";

    private final ObjectMapper objectMapper;

    /**
     * 把 kind=quiz 的消息正文渲染成模型可读的题面清单。
     *
     * <p>带上测验会话 id：模型下一轮要靠它调 grade_answer，而这个 id 只存在于负载里。
     * 解析失败时原样退回正文，宁可让模型看到一段 JSON 也不要让整轮对话失败。</p>
     */
    public String renderQuiz(String quizJson) {
        JsonNode payload = parse(quizJson);
        if (payload == null || !payload.path("questions").isArray()) {
            return quizJson == null ? "" : quizJson;
        }
        JsonNode questions = payload.path("questions");
        StringBuilder builder = new StringBuilder();
        builder.append("测验会话 id=").append(payload.path("id").asText(""));
        String title = payload.path("title").asText("");
        if (StringUtils.hasText(title)) builder.append("，《").append(title).append("》");
        builder.append("，共 ").append(questions.size()).append(" 题");
        for (int index = 0; index < questions.size(); index++) {
            JsonNode question = questions.get(index);
            builder.append('\n').append(index + 1).append(". [")
                    .append(typeLabel(question.path("type").asText("")))
                    .append("] ").append(question.path("prompt").asText(""));
            String options = joinOptions(question.path("options"));
            if (!options.isEmpty()) builder.append("（选项：").append(options).append("）");
        }
        return builder.toString();
    }

    /**
     * 正文是否一份答卷负载。
     *
     * <p>只做语法判别、不查库：调用方（记忆召回）只需要知道"这一轮不是新诉求"，
     * 不需要题面。</p>
     */
    public boolean isSubmission(String content) {
        if (!looksLikeSubmission(content)) return false;
        JsonNode payload = parse(content);
        return payload != null && SUBMIT_TYPE.equals(payload.path("type").asText(""));
    }

    /**
     * 把前端提交的答卷负载翻译成逐题作答记录；不是答卷就返回空由调用方按普通消息处理。
     *
     * <p>必须在本会话里找到 quiz_id 对应的那张答题卡才认这次提交：一方面题面只存在那条消息里，
     * 另一方面这让客户端无法凭一段自造的 JSON 把任意会话 id 塞进模型上下文。</p>
     *
     * <p>历史用 {@code Supplier} 传入，只有正文确实像答卷时才去查库。</p>
     *
     * @param content 用户消息原文
     * @param history 本会话的历史消息，按 id 升序
     */
    public Optional<String> renderSubmission(String content, Supplier<List<AgentMessage>> history) {
        if (!isSubmission(content)) return Optional.empty();
        JsonNode payload = parse(content);
        String quizId = firstText(payload, "quiz_id", "quizId");
        if (!StringUtils.hasText(quizId)) return Optional.empty();
        JsonNode quiz = findQuiz(history.get(), quizId);
        if (quiz == null) {
            log.warn("agent quiz submission references an unknown card, quizId={}", quizId);
            return Optional.empty();
        }
        return Optional.of(render(quizId, quiz, indexAnswers(payload.path("answers"))));
    }

    /** 正文是否值得一试：答卷一定是个 JSON 对象且带判别值，据此跳过绝大多数普通消息。 */
    private boolean looksLikeSubmission(String content) {
        if (!StringUtils.hasText(content)) return false;
        String trimmed = content.trim();
        return trimmed.startsWith("{") && trimmed.contains(SUBMIT_TYPE);
    }

    private String render(String quizId, JsonNode quiz, Map<String, String> answers) {
        JsonNode questions = quiz.path("questions");
        StringBuilder builder = new StringBuilder();
        builder.append("测验会话 id=").append(quizId)
                .append("，用户在答题卡上提交了 ").append(questions.size()).append(" 题的作答");
        for (int index = 0; index < questions.size(); index++) {
            JsonNode question = questions.get(index);
            String questionId = question.path("id").asText("");
            // 按答题卡的题序遍历而不是按提交的顺序：模型总能看到每一题，
            // 提交里多出来或漏掉的条目都不会改变题面清单。
            builder.append('\n').append(index + 1).append(". ")
                    .append(question.path("prompt").asText(questionId))
                    .append("\n   作答：")
                    .append(answers.getOrDefault(questionId, UNANSWERED));
        }
        return builder.toString();
    }

    /** 题号到作答文本的映射；多选拼成一行，缺答统一落到"未作答"。 */
    private Map<String, String> indexAnswers(JsonNode answers) {
        Map<String, String> indexed = new LinkedHashMap<>();
        if (!answers.isArray()) return indexed;
        for (JsonNode entry : answers) {
            String questionId = firstText(entry, "question_id", "questionId");
            if (!StringUtils.hasText(questionId)) continue;
            indexed.put(questionId, answerText(entry.path("answer")));
        }
        return indexed;
    }

    private String answerText(JsonNode answer) {
        if (answer == null || answer.isNull() || answer.isMissingNode()) return UNANSWERED;
        String text = answer.isArray() ? joinOptions(answer) : answer.asText("").trim();
        return text.isEmpty() ? UNANSWERED : limit(text, MAX_ANSWER_CHARS);
    }

    /** 本会话里 id 匹配的最后一张答题卡；同一场测验重发过就以最新那张的题面为准。 */
    private JsonNode findQuiz(List<AgentMessage> history, String quizId) {
        if (history == null) return null;
        for (int index = history.size() - 1; index >= 0; index--) {
            AgentMessage message = history.get(index);
            if (!PAYLOAD_TYPE.equals(message.getKind())) continue;
            JsonNode payload = parse(message.getContent());
            if (payload != null && quizId.equals(payload.path("id").asText(""))
                    && payload.path("questions").isArray()) {
                return payload;
            }
        }
        return null;
    }

    private JsonNode parse(String json) {
        if (!StringUtils.hasText(json)) return null;
        try {
            JsonNode node = objectMapper.readTree(json);
            return node != null && node.isObject() ? node : null;
        } catch (Exception error) {
            return null;
        }
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            String value = node.path(name).asText("").trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private String joinOptions(JsonNode options) {
        if (!options.isArray() || options.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        for (JsonNode option : options) {
            String text = option.isObject() ? option.path("label").asText("") : option.asText("");
            if (text.isBlank()) continue;
            if (builder.length() > 0) builder.append(" / ");
            builder.append(text.trim());
        }
        return builder.toString();
    }

    private String typeLabel(String type) {
        return switch (type) {
            case "multiple" -> "多选";
            case "text" -> "主观";
            default -> "单选";
        };
    }

    private String limit(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…";
    }
}

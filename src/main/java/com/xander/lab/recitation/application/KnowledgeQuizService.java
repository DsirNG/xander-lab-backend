package com.xander.lab.recitation.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.recitation.api.KnowledgeQuizAttemptView;
import com.xander.lab.recitation.domain.KnowledgeQuizAttempt;
import com.xander.lab.recitation.domain.RecitationMaterial;
import com.xander.lab.recitation.infrastructure.KnowledgeQuizAttemptMapper;
import com.xander.lab.recitation.infrastructure.RecitationMaterialMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 概念与数学知识的测验闭环：开场取材、评分留档、掌握度推进。
 *
 * <p>录音背诵有 ASR 给出客观分数，概念问答和练习题没有这种客观信号。这里不再为此另建一个
 * "出题模型"，而是让智能体本人出题和判分——它已经拿着知识原文和对话上下文，是现场最清楚
 * 用户答得对不对的一方。服务端只做两件它不能自证的事：把题目和用户原话作为证据存下来，
 * 以及按每题得分算出总分再套用 {@link MasteryPolicy}。</p>
 *
 * <p>分数刻意由服务端从逐题得分算出，而不是让智能体直接报一个总分：总分是可以随口给的，
 * 逐题判分必须逐题给出证据，虚高的代价就大得多。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeQuizService {

    /** 单次测验最多留档的题数；工具的参数说明要引用它，所以对外可见。 */
    public static final int MAX_ITEMS = 20;
    static final int MAX_QUESTION_CHARS = 500;
    static final int MAX_ANSWER_CHARS = 1000;
    static final int MAX_COMMENT_CHARS = 500;
    /** 总体讲评的长度上限；同样出现在工具参数说明里。 */
    public static final int MAX_VERDICT_CHARS = 1000;
    /** 列表接口返回的历史条数上限，与录音任务列表保持一致。 */
    static final int LIST_LIMIT = 30;
    /** 开场交给智能体出题的正文上限：够出题，又不至于把主上下文挤满。 */
    static final int QUIZ_CONTENT_CHARS = 4000;
    /** 开场回带几次历史，够智能体针对上次答错的地方出题。 */
    static final int SESSION_HISTORY_LIMIT = 3;

    private final RecitationMaterialMapper materialMapper;
    private final KnowledgeQuizAttemptMapper quizMapper;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    /** 开一场测验：交出出题需要的原文、当前掌握画像和最近几次测验记录。本身不写库。 */
    @Transactional(readOnly = true)
    public QuizSession openQuiz(Long userId, Long materialId) {
        RecitationMaterial material = requireMaterial(userId, materialId);
        String content = material.getContent() == null ? "" : material.getContent();
        boolean truncated = content.length() > QUIZ_CONTENT_CHARS;
        List<KnowledgeQuizAttemptView> history = recentQuizzes(userId, materialId, SESSION_HISTORY_LIMIT);
        LocalDateTime nextReviewAt = material.getNextReviewAt();
        return new QuizSession(material.getId(), material.getTitle(), material.getKnowledgeType(),
                material.getTestMode(), truncated ? content.substring(0, QUIZ_CONTENT_CHARS) + "…" : content,
                truncated, content.length(), material.getMasteryScore(), material.getMasteryLevel(),
                material.getReviewCount() == null ? 0 : material.getReviewCount(),
                material.getLastReviewAt(), nextReviewAt,
                nextReviewAt != null && !nextReviewAt.isAfter(LocalDateTime.now()), history);
    }

    /** 列出某条知识最近的测验记录。 */
    @Transactional(readOnly = true)
    public List<KnowledgeQuizAttemptView> listQuizzes(Long userId, Long materialId) {
        requireMaterial(userId, materialId);
        return recentQuizzes(userId, materialId, LIST_LIMIT);
    }

    /**
     * 记录一次测验并按分数推进掌握度。
     *
     * <p>写库路径与 {@link RecitationWorker} 完成录音检查时一致：同一个事务里插入评分证据、
     * 更新掌握分数与等级、复习次数 +1、按 {@link MasteryPolicy} 重算下次复习时间。
     * 也就是说概念题和录音背诵最终落在同一套掌握度口径上，知识页的统计不会分家。</p>
     *
     * @param conversationId 出题所在会话，可为空
     * @param verdict        面向用户的总体讲评，可为空
     * @param rawItems       逐题判分证据，不能为空
     */
    public QuizOutcome recordQuiz(Long userId, Long materialId, Long conversationId,
                                  String verdict, List<GradedItem> rawItems) {
        RecitationMaterial material = requireMaterial(userId, materialId);
        List<GradedItem> items = normalize(rawItems);
        BigDecimal score = score(items);
        int correctCount = (int) items.stream()
                .filter(item -> item.credit().compareTo(BigDecimal.ONE) >= 0).count();
        LocalDateTime reviewedAt = LocalDateTime.now();
        MasteryPolicy.Result mastery = MasteryPolicy.evaluate(score, reviewedAt);

        KnowledgeQuizAttempt attempt = new KnowledgeQuizAttempt();
        attempt.setUserId(userId);
        attempt.setMaterialId(material.getId());
        attempt.setConversationId(conversationId);
        attempt.setTestMode(material.getTestMode());
        attempt.setQuestionCount(items.size());
        attempt.setCorrectCount(correctCount);
        attempt.setScore(score);
        attempt.setVerdict(limit(verdict, MAX_VERDICT_CHARS));
        attempt.setItemsJson(writeItems(items));
        attempt.setCreatedAt(reviewedAt);
        transactionTemplate.executeWithoutResult(status -> {
            quizMapper.insert(attempt);
            materialMapper.update(null, new LambdaUpdateWrapper<RecitationMaterial>()
                    .eq(RecitationMaterial::getId, material.getId())
                    .eq(RecitationMaterial::getUserId, userId)
                    .set(RecitationMaterial::getMasteryScore, score)
                    .set(RecitationMaterial::getMasteryLevel, mastery.level())
                    .setSql("review_count = review_count + 1")
                    .set(RecitationMaterial::getLastReviewAt, reviewedAt)
                    .set(RecitationMaterial::getNextReviewAt, mastery.nextReviewAt())
                    .set(RecitationMaterial::getUpdatedAt, reviewedAt));
        });
        log.info("knowledge quiz recorded material={} questions={} correct={} score={}",
                material.getId(), items.size(), correctCount, score);
        return new QuizOutcome(attempt.getId(), material.getId(), material.getTitle(), score, items.size(),
                correctCount, mastery.level(), material.getMasteryLevel(),
                (material.getReviewCount() == null ? 0 : material.getReviewCount()) + 1,
                reviewedAt, mastery.nextReviewAt());
    }

    /**
     * 校验并规范逐题证据。
     *
     * <p>空清单和缺 credit 都直接拒绝而不是当成 0 分：那说明智能体想只报一个总分，
     * 而总分正是本设计不信任的东西。拒绝会变成它的一条观察，它会带着证据重来。</p>
     */
    private List<GradedItem> normalize(List<GradedItem> rawItems) {
        if (rawItems == null || rawItems.isEmpty()) {
            throw new IllegalArgumentException("必须逐题给出题目、用户作答和得分，没有证据的分数不予记录");
        }
        if (rawItems.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("一次最多记录 " + MAX_ITEMS + " 道题的评分");
        }
        List<GradedItem> items = new ArrayList<>(rawItems.size());
        for (GradedItem raw : rawItems) {
            String question = limit(raw == null ? null : raw.question(), MAX_QUESTION_CHARS);
            if (!StringUtils.hasText(question)) {
                throw new IllegalArgumentException("每道题都要写清题目");
            }
            if (raw.credit() == null) {
                throw new IllegalArgumentException("每道题都要给出得分 credit（0 到 1），不能留空");
            }
            BigDecimal credit = raw.credit().max(BigDecimal.ZERO).min(BigDecimal.ONE)
                    .setScale(2, RoundingMode.HALF_UP);
            items.add(new GradedItem(question, limit(raw.userAnswer(), MAX_ANSWER_CHARS),
                    limit(raw.comment(), MAX_COMMENT_CHARS), credit));
        }
        return items;
    }

    /** 总分 = 各题得分之和 ÷ 题数 × 100。 */
    private BigDecimal score(List<GradedItem> items) {
        BigDecimal earned = items.stream().map(GradedItem::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        return earned.multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(items.size()), 2, RoundingMode.HALF_UP);
    }

    private String writeItems(List<GradedItem> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (Exception error) {
            throw new IllegalStateException("无法序列化测验评分证据", error);
        }
    }

    private List<KnowledgeQuizAttemptView> recentQuizzes(Long userId, Long materialId, int limit) {
        return quizMapper.selectList(new LambdaQueryWrapper<KnowledgeQuizAttempt>()
                        .eq(KnowledgeQuizAttempt::getUserId, userId)
                        .eq(KnowledgeQuizAttempt::getMaterialId, materialId)
                        .orderByDesc(KnowledgeQuizAttempt::getCreatedAt, KnowledgeQuizAttempt::getId)
                        .last("LIMIT " + limit))
                .stream().map(this::toView).toList();
    }

    private RecitationMaterial requireMaterial(Long userId, Long materialId) {
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        RecitationMaterial material = materialId == null ? null
                : materialMapper.selectOne(new LambdaQueryWrapper<RecitationMaterial>()
                        .eq(RecitationMaterial::getId, materialId)
                        .eq(RecitationMaterial::getUserId, userId));
        if (material == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识不存在");
        return material;
    }

    private KnowledgeQuizAttemptView toView(KnowledgeQuizAttempt attempt) {
        Object items = null;
        if (StringUtils.hasText(attempt.getItemsJson())) {
            try {
                items = objectMapper.readValue(attempt.getItemsJson(), Object.class);
            } catch (Exception error) {
                log.warn("Unable to deserialize quiz items, attemptId={}", attempt.getId(), error);
            }
        }
        return new KnowledgeQuizAttemptView(attempt.getId(), attempt.getMaterialId(), attempt.getTestMode(),
                attempt.getQuestionCount() == null ? 0 : attempt.getQuestionCount(),
                attempt.getCorrectCount() == null ? 0 : attempt.getCorrectCount(),
                attempt.getScore(), attempt.getVerdict(), items, attempt.getCreatedAt());
    }

    private String limit(String value, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) return "";
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength) + "…";
    }

    /**
     * 开场交给智能体的出题材料。
     *
     * @param contentTruncated 正文被截断时为 true，智能体出题时应避免涉及未见部分
     * @param dueForReview     是否已到建议复习时间
     * @param recentQuizzes    最近几次测验，用于针对错处出题
     */
    public record QuizSession(Long materialId, String title, String knowledgeType, String testMode,
                              String content, boolean contentTruncated, int contentLength,
                              BigDecimal masteryScore, String masteryLevel, int reviewCount,
                              LocalDateTime lastReviewAt, LocalDateTime nextReviewAt, boolean dueForReview,
                              List<KnowledgeQuizAttemptView> recentQuizzes) {
    }

    /**
     * 一道题的判分证据。
     *
     * @param credit 得分比例，0 到 1；0.5 表示只答对一半
     */
    public record GradedItem(String question, String userAnswer, String comment, BigDecimal credit) {
    }

    /**
     * 一次测验落库后的结果。
     *
     * @param previousMasteryLevel 本次之前的掌握等级，便于告诉用户"从 FAMILIAR 升到了 APPLYING"
     */
    public record QuizOutcome(Long attemptId, Long materialId, String title, BigDecimal score,
                              int questionCount, int correctCount, String masteryLevel,
                              String previousMasteryLevel, int reviewCount,
                              LocalDateTime lastReviewAt, LocalDateTime nextReviewAt) {
    }
}

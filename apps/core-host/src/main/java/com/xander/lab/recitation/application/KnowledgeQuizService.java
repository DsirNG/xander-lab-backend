package com.xander.lab.recitation.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.recitation.api.KnowledgeQuizAttemptView;
import com.xander.lab.recitation.domain.KnowledgeQuizAttempt;
import com.xander.lab.recitation.domain.KnowledgeQuizSession;
import com.xander.lab.recitation.domain.RecitationMaterial;
import com.xander.lab.recitation.infrastructure.KnowledgeQuizAttemptMapper;
import com.xander.lab.recitation.infrastructure.KnowledgeQuizSessionMapper;
import com.xander.lab.recitation.infrastructure.RecitationMaterialMapper;
import com.xander.lab.recitation.spi.GradedItemDraft;
import com.xander.lab.recitation.spi.QuizAttemptView;
import com.xander.lab.recitation.spi.QuizOutcomeView;
import com.xander.lab.recitation.spi.QuizSessionView;
import com.xander.lab.recitation.spi.RecitationQuizPort;
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
 *
 * <p>本类实现跨域契约 {@link RecitationQuizPort}（{@code recitation.spi}）。
 * {@code openQuiz} 与 {@code recordQuiz} 就是端口方法本身 —— 它们的返回类型有一对
 * 域内 record（{@code QuizSession} / {@code QuizOutcome}），但**没有同域调用方**：
 * 唯一的调用者是 agent 的两个工具和服务自己的测试，所以直接换返回类型，
 * 不保留带 {@code ForView} 后缀的富方法（那个后缀是用来说明受众的，而这里受众是空的）。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeQuizService implements RecitationQuizPort {

    static final int MAX_QUESTION_CHARS = 500;
    static final int MAX_ANSWER_CHARS = 1000;
    static final int MAX_COMMENT_CHARS = 500;
    /** 列表接口返回的历史条数上限，与录音任务列表保持一致。 */
    static final int LIST_LIMIT = 30;
    /** 开场交给智能体出题的正文上限：够出题，又不至于把主上下文挤满。 */
    static final int QUIZ_CONTENT_CHARS = 4000;
    /** 开场回带几次历史，够智能体针对上次答错的地方出题。 */
    static final int SESSION_HISTORY_LIMIT = 3;
    static final int SESSION_TTL_HOURS = 24;
    private static final String SESSION_OPEN = "OPEN";
    private static final String SESSION_GRADED = "GRADED";

    private final RecitationMaterialMapper materialMapper;
    private final KnowledgeQuizAttemptMapper quizMapper;
    private final KnowledgeQuizSessionMapper sessionMapper;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    /** 开一场测验并持久化会话，使后续评分只能落到这次真实出题上。 */
    @Transactional
    public QuizSessionView openQuiz(Long userId, Long materialId) {
        return openQuiz(userId, materialId, null);
    }

    /** 开一场属于指定智能体对话的测验；跨对话不能拿该会话评分。 */
    @Override
    @Transactional
    public QuizSessionView openQuiz(Long userId, Long materialId, Long conversationId) {
        RecitationMaterial material = requireMaterial(userId, materialId);
        String content = material.getContent() == null ? "" : material.getContent();
        boolean truncated = content.length() > QUIZ_CONTENT_CHARS;
        List<QuizAttemptView> history = recentQuizAttempts(userId, materialId, SESSION_HISTORY_LIMIT);
        LocalDateTime nextReviewAt = material.getNextReviewAt();
        KnowledgeQuizSession quizSession = new KnowledgeQuizSession();
        quizSession.setUserId(userId);
        quizSession.setMaterialId(materialId);
        quizSession.setConversationId(conversationId);
        quizSession.setStatus(SESSION_OPEN);
        quizSession.setExpiresAt(LocalDateTime.now().plusHours(SESSION_TTL_HOURS));
        sessionMapper.insert(quizSession);
        return new QuizSessionView(quizSession.getId(), material.getId(), material.getTitle(),
                material.getKnowledgeType(),
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
     * @param quizSessionId  {@code quiz_knowledge} 创建的持久化测验会话
     * @param conversationId 评分所在会话；必须与测验发起会话一致
     * @param verdict        面向用户的总体讲评，可为空
     * @param rawItems       逐题判分证据，不能为空
     */
    @Override
    public QuizOutcomeView recordQuiz(Long userId, Long quizSessionId, Long conversationId,
                                      String verdict, List<GradedItemDraft> rawItems) {
        List<GradedItem> items = normalize(rawItems);
        BigDecimal score = score(items);
        int correctCount = (int) items.stream()
                .filter(item -> item.credit().compareTo(BigDecimal.ONE) >= 0).count();
        QuizOutcomeView outcome = transactionTemplate.execute(status -> {
            KnowledgeQuizSession session = requireSession(userId, quizSessionId, conversationId);
            RecitationMaterial material = requireMaterial(userId, session.getMaterialId());
            if (SESSION_GRADED.equals(session.getStatus())) {
                return existingOutcome(session, material);
            }
            LocalDateTime reviewedAt = LocalDateTime.now();
            if (session.getExpiresAt() == null || !session.getExpiresAt().isAfter(reviewedAt)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "测验会话已过期，请重新开始测验");
            }
            // 先条件认领 OPEN 会话；并发重复评分只有一个事务能推进掌握度。
            int claimed = sessionMapper.update(null, new LambdaUpdateWrapper<KnowledgeQuizSession>()
                    .eq(KnowledgeQuizSession::getId, session.getId())
                    .eq(KnowledgeQuizSession::getUserId, userId)
                    .eq(KnowledgeQuizSession::getStatus, SESSION_OPEN)
                    .set(KnowledgeQuizSession::getStatus, SESSION_GRADED)
                    .set(KnowledgeQuizSession::getGradedAt, reviewedAt));
            if (claimed != 1) {
                KnowledgeQuizSession latest = requireSession(userId, quizSessionId, conversationId);
                return existingOutcome(latest, requireMaterial(userId, latest.getMaterialId()));
            }
            MasteryPolicy.Result mastery = MasteryPolicy.evaluate(score, reviewedAt);
            KnowledgeQuizAttempt attempt = new KnowledgeQuizAttempt();
            attempt.setUserId(userId);
            attempt.setMaterialId(material.getId());
            attempt.setQuizSessionId(session.getId());
            attempt.setConversationId(conversationId);
            attempt.setTestMode(material.getTestMode());
            attempt.setQuestionCount(items.size());
            attempt.setCorrectCount(correctCount);
            attempt.setScore(score);
            attempt.setVerdict(limit(verdict, MAX_VERDICT_CHARS));
            attempt.setItemsJson(writeItems(items));
            quizMapper.insert(attempt);
            materialMapper.update(null, new LambdaUpdateWrapper<RecitationMaterial>()
                    .eq(RecitationMaterial::getId, material.getId())
                    .eq(RecitationMaterial::getUserId, userId)
                    .set(RecitationMaterial::getMasteryScore, score)
                    .set(RecitationMaterial::getMasteryLevel, mastery.level())
                    .setSql("review_count = review_count + 1")
                    .set(RecitationMaterial::getLastReviewAt, reviewedAt)
                    .set(RecitationMaterial::getNextReviewAt, mastery.nextReviewAt())
                    );
            sessionMapper.update(null, new LambdaUpdateWrapper<KnowledgeQuizSession>()
                    .eq(KnowledgeQuizSession::getId, session.getId())
                    .eq(KnowledgeQuizSession::getUserId, userId)
                    .set(KnowledgeQuizSession::getAttemptId, attempt.getId()));
            return new QuizOutcomeView(attempt.getId(), material.getId(), material.getTitle(), score,
                    items.size(), correctCount, mastery.level(), material.getMasteryLevel(),
                    (material.getReviewCount() == null ? 0 : material.getReviewCount()) + 1,
                    mastery.nextReviewAt());
        });
        if (outcome == null) throw new IllegalStateException("无法记录知识测验");
        log.info("knowledge quiz resolved material={} questions={} correct={} score={}",
                outcome.materialId(), outcome.questionCount(), outcome.correctCount(), outcome.score());
        return outcome;
    }

    private KnowledgeQuizSession requireSession(Long userId, Long quizSessionId, Long conversationId) {
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        KnowledgeQuizSession session = quizSessionId == null ? null
                : sessionMapper.selectOne(new LambdaQueryWrapper<KnowledgeQuizSession>()
                        .eq(KnowledgeQuizSession::getId, quizSessionId)
                        .eq(KnowledgeQuizSession::getUserId, userId));
        if (session == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "测验会话不存在");
        if (session.getConversationId() != null && !session.getConversationId().equals(conversationId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "测验会话不属于当前对话，请重新开始测验");
        }
        return session;
    }

    private QuizOutcomeView existingOutcome(KnowledgeQuizSession session, RecitationMaterial material) {
        if (!SESSION_GRADED.equals(session.getStatus()) || session.getAttemptId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "测验正在评分，请稍后重试");
        }
        KnowledgeQuizAttempt attempt = quizMapper.selectOne(new LambdaQueryWrapper<KnowledgeQuizAttempt>()
                .eq(KnowledgeQuizAttempt::getId, session.getAttemptId())
                .eq(KnowledgeQuizAttempt::getUserId, session.getUserId())
                .eq(KnowledgeQuizAttempt::getQuizSessionId, session.getId()));
        if (attempt == null) throw new IllegalStateException("测验会话缺少评分记录");
        int reviewCount = material.getReviewCount() == null ? 0 : material.getReviewCount();
        return new QuizOutcomeView(attempt.getId(), material.getId(), material.getTitle(), attempt.getScore(),
                attempt.getQuestionCount(), attempt.getCorrectCount(), material.getMasteryLevel(),
                material.getMasteryLevel(), reviewCount, material.getNextReviewAt());
    }

    /**
     * 校验并规范逐题证据，产出**域内**的 {@link GradedItem}。
     *
     * <p>空清单和缺 credit 都直接拒绝而不是当成 0 分：那说明智能体想只报一个总分，
     * 而总分正是本设计不信任的东西。拒绝会变成它的一条观察，它会带着证据重来。</p>
     *
     * <p>⚠️ 入参是契约的 {@link GradedItemDraft}，出参是本类的 {@link GradedItem} ——
     * 两者形状相同，但**不能合并**：{@code GradedItem} 是"已规范化"的那一份，
     * 而 {@link #writeItems} 把它序列化进 {@code items_json} 列。让契约类型直接决定落库格式，
     * 意味着以后改契约的分量名会**静默改变历史行的形状**。这道换算就是那个隔离层，
     * 也是 credit 夹到 [0,1]、字符串截断发生的地方。</p>
     */
    private List<GradedItem> normalize(List<GradedItemDraft> rawItems) {
        if (rawItems == null || rawItems.isEmpty()) {
            throw new IllegalArgumentException("必须逐题给出题目、用户作答和得分，没有证据的分数不予记录");
        }
        if (rawItems.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("一次最多记录 " + MAX_ITEMS + " 道题的评分");
        }
        List<GradedItem> items = new ArrayList<>(rawItems.size());
        for (GradedItemDraft raw : rawItems) {
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

    private List<KnowledgeQuizAttempt> recentQuizEntities(Long userId, Long materialId, int limit) {
        return quizMapper.selectList(new LambdaQueryWrapper<KnowledgeQuizAttempt>()
                .eq(KnowledgeQuizAttempt::getUserId, userId)
                .eq(KnowledgeQuizAttempt::getMaterialId, materialId)
                .orderByDesc(KnowledgeQuizAttempt::getCreatedAt, KnowledgeQuizAttempt::getId)
                .last("LIMIT " + limit));
    }

    /**
     * 前端那一份投影：把 {@code items_json} 解析成对象，随 {@link KnowledgeQuizAttemptView}
     * 序列化给知识页。
     */
    private List<KnowledgeQuizAttemptView> recentQuizzes(Long userId, Long materialId, int limit) {
        return recentQuizEntities(userId, materialId, limit).stream().map(this::toView).toList();
    }

    /**
     * 跨域那一份投影：{@code items_json} **原样**交给契约，不解析。
     *
     * <p>两份投影读同一批行、查同一次库，差别只在 {@code items} 那一列怎么交出去。
     * 分开是必要的：前端要的是结构（它要渲染每道题），跨域要的是文本（智能体只是把它
     * 原样放进工具输出，自己不看结构）。让它们共用一份会把其中一个的语义强加给另一个。</p>
     */
    private List<QuizAttemptView> recentQuizAttempts(Long userId, Long materialId, int limit) {
        return recentQuizEntities(userId, materialId, limit).stream()
                .map(KnowledgeQuizService::toAttemptView).toList();
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

    /**
     * 跨域投影：{@code items_json} 原样带走，不解析、不容错。
     *
     * <p>与上面那个 {@link #toView} 的差别就在这一处：那里解析失败会吞掉异常、留 {@code null}
     * （前端那份视图要能显示"证据读不出来"），而这里**什么都不做** —— 契约只回答
     * "这一列里是什么"，怎么应对非法内容由消费方决定。这正好是
     * "端口只回答数据问题，兜底留调用方"。</p>
     *
     * <p>⚠️ 返回的 {@code itemsJson} 可能是 {@code null} 或空串（列里本来就没内容），
     * 契约侧不做归一化 —— 把它变成 {@code "[]"} 是一种兜底，不属于端口。</p>
     */
    private static QuizAttemptView toAttemptView(KnowledgeQuizAttempt attempt) {
        return new QuizAttemptView(
                attempt.getQuestionCount() == null ? 0 : attempt.getQuestionCount(),
                attempt.getCorrectCount() == null ? 0 : attempt.getCorrectCount(),
                attempt.getScore(),
                attempt.getVerdict(),
                attempt.getItemsJson(),
                attempt.getCreatedAt());
    }

    private String limit(String value, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) return "";
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength) + "…";
    }

    /**
     * 一道题的判分证据，**已规范化**（credit 夹到 [0,1] 并保留两位、三个字符串按上限截断）。
     *
     * <p>⚠️ 它留在域内，不上端口 —— 端口收的是契约的 {@link GradedItemDraft}。两者的分量
     * 逐字段同名同序，看起来像重复，但它们回答的是不同的问题：draft 是"调用方想写什么"
     * （credit 可为 {@code null}，字符串长度未受约束），这个是"准备落库的是什么"。
     * 合并成一份会让 {@link #writeItems} 把**契约类型**序列化进 {@code items_json}，
     * 于是以后改契约的分量名会静默改变历史行的形状。</p>
     *
     * <p>它也不能搬进契约：{@code normalize} 会 {@code new} 它，而契约不许带实现。</p>
     *
     * @param credit 得分比例，0 到 1；0.5 表示只答对一半
     */
    public record GradedItem(String question, String userAnswer, String comment, BigDecimal credit) {
    }
}

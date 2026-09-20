package com.xander.lab.recitation.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 钉住三个换算的<b>逐字段落点</b>：{@code RecitationMaterial → QuizSessionView}、
 * {@code 域内计算结果 → QuizOutcomeView}、{@code KnowledgeQuizAttempt → QuizAttemptView}。
 *
 * <p>契约模块看不见域类型，所以 {@code RecordShapeTest} 只能钉契约自己的分量名与顺序；
 * 真正会静默出错的是换算那一行。{@link QuizSessionView} 在 {@code quizSessionId} 之后连着四个
 * {@code String}（{@code title} / {@code knowledgeType} / {@code testMode} / {@code content}），
 * 换位不会有任何编译错误 —— 把正文当标题写进智能体的提示词，只有这里会红。</p>
 *
 * <h2>为什么这里不能用 spy</h2>
 * <p>材料那边（{@code RecitationMaterialPortMappingTest}）能用 spy，是因为端口方法是
 * "薄壳 + 换算 + 转调同类富方法" —— 把富方法打桩就能单独观察换算。
 * 测验这里<b>没有富方法可打桩</b>：{@code openQuiz} / {@code recordQuiz} 本身就是端口方法，
 * 换算写在它们的函数体里。所以这里只能走真实实现，代价是要把三个 mapper 与
 * {@code TransactionTemplate} 都打桩、并手工初始化 MyBatis 的 lambda 元数据。</p>
 */
class RecitationQuizPortMappingTest {

    private static final LocalDateTime LAST_REVIEW_AT = LocalDateTime.parse("2020-01-02T03:04:05");
    private static final LocalDateTime NEXT_REVIEW_AT = LocalDateTime.parse("2021-02-03T04:05:06");
    private static final LocalDateTime ATTEMPT_CREATED_AT = LocalDateTime.parse("2019-11-12T13:14:15");

    private final RecitationMaterialMapper materialMapper = mock(RecitationMaterialMapper.class);
    private final KnowledgeQuizAttemptMapper quizMapper = mock(KnowledgeQuizAttemptMapper.class);
    private final KnowledgeQuizSessionMapper sessionMapper = mock(KnowledgeQuizSessionMapper.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private KnowledgeQuizService service;

    @BeforeEach
    void setUp() {
        // LambdaUpdateWrapper.set 会立刻解析列名，脱离容器要先手工初始化表信息。
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), RecitationMaterial.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), KnowledgeQuizAttempt.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), KnowledgeQuizSession.class);
        TransactionStatus status = mock(TransactionStatus.class);
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(status);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(status);
        });
        when(quizMapper.insert(any(KnowledgeQuizAttempt.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, KnowledgeQuizAttempt.class).setId(77L);
            return 1;
        });
        when(sessionMapper.insert(any(KnowledgeQuizSession.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, KnowledgeQuizSession.class).setId(55L);
            return 1;
        });
        when(sessionMapper.update(isNull(), any())).thenReturn(1);
        service = new KnowledgeQuizService(
                materialMapper, quizMapper, sessionMapper, transactionTemplate, new ObjectMapper());
    }

    /**
     * 每个分量都必须来自它自己那个源字段。字面量<b>互不相同</b>：四个相邻的 {@code String}
     * 用一组全不相同的值即可钉满（全不同 ⇒ 任何换位都至少有一处对不上）。
     *
     * <p>两个 {@code boolean}（{@code contentTruncated} 与 {@code dueForReview}）被其它分量隔开，
     * 但仍然只有两个取值：这里故意取<b>不同</b>的值（false / true），
     * 否则对调它们不会有任何症状。</p>
     */
    @Test
    void everySessionComponentComesFromItsOwnSourceField() {
        RecitationMaterial material = material();
        material.setContent("正文字段");
        material.setLastReviewAt(LAST_REVIEW_AT);
        // 已过期 ⇒ dueForReview 为 true，与 contentTruncated 的 false 不同。
        material.setNextReviewAt(NEXT_REVIEW_AT);
        when(materialMapper.selectOne(any())).thenReturn(material);
        when(quizMapper.selectList(any())).thenReturn(List.of());

        QuizSessionView session = service.openQuiz(42L, 11L, 7L);

        assertThat(session.quizSessionId()).isEqualTo(55L);
        assertThat(session.materialId()).isEqualTo(11L);
        assertThat(session.title()).isEqualTo("标题字段");
        assertThat(session.knowledgeType()).isEqualTo("类型字段");
        assertThat(session.testMode()).isEqualTo("考法字段");
        assertThat(session.content()).isEqualTo("正文字段");
        assertThat(session.contentTruncated()).isFalse();
        assertThat(session.contentLength()).isEqualTo(4);
        assertThat(session.masteryScore()).isEqualByComparingTo("77");
        assertThat(session.masteryLevel()).isEqualTo("等级字段");
        assertThat(session.reviewCount()).isEqualTo(33);
        assertThat(session.lastReviewAt()).isEqualTo(LAST_REVIEW_AT);
        assertThat(session.nextReviewAt()).isEqualTo(NEXT_REVIEW_AT);
        assertThat(session.dueForReview()).isTrue();
        assertThat(session.recentQuizzes()).isEmpty();
    }

    /**
     * 历史行的换算：{@code itemsJson} 是<b>原样搬运的文本</b>，不是解析后的结构。
     *
     * <p>这一条与前端那条路径（{@code listQuizzes}）的分工必须被钉住 ——
     * 契约侧不带 JSON 库，所以解析只能发生在前端那一侧；如果哪天有人"顺手"在端口这一侧
     * 也解析一遍，就会多出一个契约依赖，而契约的门禁会红（见 {@code contract-dependency-boundary}）。</p>
     */
    @Test
    void everyHistoryComponentComesFromItsOwnSourceFieldAndItemsStayText() {
        RecitationMaterial material = material();
        when(materialMapper.selectOne(any())).thenReturn(material);
        when(quizMapper.selectList(any())).thenReturn(List.of(historyRow()));

        QuizSessionView session = service.openQuiz(42L, 11L, 7L);

        QuizAttemptView attempt = session.recentQuizzes().get(0);
        assertThat(attempt.questionCount()).isEqualTo(21);
        assertThat(attempt.correctCount()).isEqualTo(22);
        assertThat(attempt.score()).isEqualByComparingTo("23.50");
        assertThat(attempt.verdict()).isEqualTo("讲评字段");
        assertThat(attempt.itemsJson()).isEqualTo("[{\"question\":\"逐题证据字段\"}]");
        assertThat(attempt.createdAt()).isEqualTo(ATTEMPT_CREATED_AT);
    }

    /**
     * 评分结果的换算。{@code masteryLevel} 与 {@code previousMasteryLevel} 相邻，
     * 所以让它们取不同的值：本次全对 ⇒ 本次等级是 MASTERED，而材料原等级是那个可识别的字面量。
     */
    @Test
    void everyOutcomeComponentComesFromItsOwnSourceField() {
        RecitationMaterial material = material();
        when(materialMapper.selectOne(any())).thenReturn(material);
        when(sessionMapper.selectOne(any())).thenReturn(openSession());

        QuizOutcomeView outcome = service.recordQuiz(42L, 9L, 7L, "讲评字段",
                List.of(new GradedItemDraft("题一", "答一", "评一", BigDecimal.ONE),
                        new GradedItemDraft("题二", "答二", "评二", BigDecimal.ZERO)));

        assertThat(outcome.attemptId()).isEqualTo(77L);
        assertThat(outcome.materialId()).isEqualTo(11L);
        assertThat(outcome.title()).isEqualTo("标题字段");
        assertThat(outcome.score()).isEqualByComparingTo("50.00");
        assertThat(outcome.questionCount()).isEqualTo(2);
        assertThat(outcome.correctCount()).isEqualTo(1);
        assertThat(outcome.masteryLevel()).isEqualTo("FAMILIAR");
        assertThat(outcome.previousMasteryLevel()).isEqualTo("等级字段");
        assertThat(outcome.reviewCount()).isEqualTo(34);
        assertThat(outcome.nextReviewAt()).isNotNull();
    }

    /**
     * 同一条实体被投影成两份类型：契约的 {@link QuizAttemptView}（文本）与前端可见的
     * {@link KnowledgeQuizAttemptView}（已解析）。两者<b>共享的那几个分量必须一致</b> ——
     * 各写一份投影最危险的地方就是它们会悄悄漂移，而两边各自的测试都不会红。
     */
    @Test
    void theContractProjectionAndTheFrontendProjectionAgreeOnTheFieldsTheyShare() {
        RecitationMaterial material = material();
        when(materialMapper.selectOne(any())).thenReturn(material);
        when(quizMapper.selectList(any())).thenReturn(List.of(historyRow()));

        QuizAttemptView portSide = service.openQuiz(42L, 11L, 7L).recentQuizzes().get(0);
        KnowledgeQuizAttemptView frontendSide = service.listQuizzes(42L, 11L).get(0);

        assertThat(portSide.questionCount()).isEqualTo(frontendSide.questionCount());
        assertThat(portSide.correctCount()).isEqualTo(frontendSide.correctCount());
        assertThat(portSide.score()).isEqualByComparingTo(frontendSide.score());
        assertThat(portSide.verdict()).isEqualTo(frontendSide.verdict());
        assertThat(portSide.createdAt()).isEqualTo(frontendSide.createdAt());
    }

    private RecitationMaterial material() {
        RecitationMaterial material = new RecitationMaterial();
        material.setId(11L);
        material.setUserId(42L);
        material.setTitle("标题字段");
        material.setContent("正文");
        material.setNormalizedContent("正文");
        material.setKnowledgeType("类型字段");
        material.setTestMode("考法字段");
        material.setMasteryScore(new BigDecimal("77"));
        material.setMasteryLevel("等级字段");
        material.setReviewCount(33);
        return material;
    }

    private KnowledgeQuizSession openSession() {
        KnowledgeQuizSession session = new KnowledgeQuizSession();
        session.setId(9L);
        session.setUserId(42L);
        session.setMaterialId(11L);
        session.setConversationId(7L);
        session.setStatus("OPEN");
        session.setExpiresAt(LocalDateTime.now().plusHours(1));
        return session;
    }

    private KnowledgeQuizAttempt historyRow() {
        KnowledgeQuizAttempt row = new KnowledgeQuizAttempt();
        row.setId(5L);
        row.setUserId(42L);
        row.setMaterialId(11L);
        row.setTestMode("AI_QA");
        row.setQuestionCount(21);
        row.setCorrectCount(22);
        row.setScore(new BigDecimal("23.50"));
        row.setVerdict("讲评字段");
        row.setItemsJson("[{\"question\":\"逐题证据字段\"}]");
        row.setCreatedAt(ATTEMPT_CREATED_AT);
        return row;
    }
}

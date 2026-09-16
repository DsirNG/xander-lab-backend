package com.xander.lab.recitation.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.recitation.api.KnowledgeQuizAttemptView;
import com.xander.lab.recitation.domain.KnowledgeQuizAttempt;
import com.xander.lab.recitation.domain.KnowledgeQuizSession;
import com.xander.lab.recitation.domain.RecitationMaterial;
import com.xander.lab.recitation.infrastructure.KnowledgeQuizAttemptMapper;
import com.xander.lab.recitation.infrastructure.KnowledgeQuizSessionMapper;
import com.xander.lab.recitation.infrastructure.RecitationMaterialMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeQuizServiceTest {

    private final RecitationMaterialMapper materialMapper = mock(RecitationMaterialMapper.class);
    private final KnowledgeQuizAttemptMapper quizMapper = mock(KnowledgeQuizAttemptMapper.class);
    private final KnowledgeQuizSessionMapper sessionMapper = mock(KnowledgeQuizSessionMapper.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
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
                materialMapper, quizMapper, sessionMapper, transactionTemplate, objectMapper);
    }

    @Test
    void scoresTheQuizFromPerQuestionCreditsInsteadOfTrustingAReportedTotal() {
        givenMaterial(material());

        KnowledgeQuizService.QuizOutcome outcome = service.recordQuiz(42L, 9L, 7L, "第二题只答对一半",
                List.of(item("和角公式怎么写", "1"), item("怎么推导", "0.5"), item("差角公式呢", "0")));

        assertThat(outcome.score()).isEqualByComparingTo("50.00");
        assertThat(outcome.questionCount()).isEqualTo(3);
        assertThat(outcome.correctCount()).isEqualTo(1);
        assertThat(outcome.attemptId()).isEqualTo(77L);
        assertThat(outcome.title()).isEqualTo("三角函数和角公式");
    }

    @Test
    void writesMasteryTheSameWayFinishedAudioRecitationDoesSoBothPathsShareOneStatistic() {
        givenMaterial(material());

        KnowledgeQuizService.QuizOutcome outcome = service.recordQuiz(42L, 9L, 7L, "全对",
                List.of(item("和角公式怎么写", "1"), item("差角公式呢", "1")));

        assertThat(outcome.score()).isEqualByComparingTo("100.00");
        assertThat(outcome.masteryLevel()).isEqualTo("MASTERED");
        assertThat(outcome.previousMasteryLevel()).isEqualTo("FAMILIAR");
        // 复习次数由 SQL 自增，不是读旧值再写回：并发的两次测验都要各计一次。
        assertThat(outcome.reviewCount()).isEqualTo(3);
        assertThat(outcome.nextReviewAt()).isAfter(LocalDateTime.now().plusDays(29));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<RecitationMaterial>> update =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(materialMapper).update(isNull(), update.capture());
        assertThat(update.getValue().getSqlSet())
                .contains("review_count = review_count + 1")
                .contains("mastery_score").contains("mastery_level")
                .contains("last_review_at").contains("next_review_at");
        assertThat(update.getValue().getParamNameValuePairs().values()).contains("MASTERED");
    }

    @Test
    void aQuizWithoutPerQuestionEvidenceIsRefusedSoNoScoreCanBeAssertedOutOfThinAir() {
        givenMaterial(material());

        assertThatThrownBy(() -> service.recordQuiz(42L, 9L, 7L, "答得不错", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有证据的分数不予记录");
        assertThatThrownBy(() -> service.recordQuiz(42L, 9L, 7L, "答得不错", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(quizMapper, never()).insert(any(KnowledgeQuizAttempt.class));
        verify(materialMapper, never()).update(isNull(), any());
    }

    @Test
    void aQuestionWithNoCreditIsRefusedRatherThanQuietlyCountedAsWrongOrRight() {
        givenMaterial(material());

        assertThatThrownBy(() -> service.recordQuiz(42L, 9L, 7L, "",
                List.of(item("和角公式怎么写", "1"), item("差角公式呢", null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credit");
        assertThatThrownBy(() -> service.recordQuiz(42L, 9L, 7L, "",
                List.of(item("   ", "1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("题目");
    }

    @Test
    void clampsCreditIntoZeroThroughOneSoAnOutOfRangeJudgementCannotInflateTheScore() {
        givenMaterial(material());

        KnowledgeQuizService.QuizOutcome outcome = service.recordQuiz(42L, 9L, 7L, "",
                List.of(item("超范围给分", "5"), item("负分", "-3")));

        assertThat(outcome.score()).isEqualByComparingTo("50.00");
        assertThat(outcome.correctCount()).isEqualTo(1);
    }

    @Test
    void rejectsMoreQuestionsThanTheCapInsteadOfDroppingEvidenceAndMisreportingTheDenominator() {
        givenMaterial(material());
        List<KnowledgeQuizService.GradedItem> tooMany = java.util.stream.IntStream
                .range(0, KnowledgeQuizService.MAX_ITEMS + 1)
                .mapToObj(index -> item("第 " + index + " 题", "1"))
                .toList();

        assertThatThrownBy(() -> service.recordQuiz(42L, 9L, 7L, "", tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(KnowledgeQuizService.MAX_ITEMS));
    }

    @Test
    void keepsTheQuestionsAndTheUsersOwnWordsAsEvidenceAndTruncatesTheOverlongOnes() {
        givenMaterial(material());
        KnowledgeQuizService.GradedItem huge = new KnowledgeQuizService.GradedItem(
                "问".repeat(600), "答".repeat(1200), "评".repeat(600), new BigDecimal("0.5"));

        service.recordQuiz(42L, 9L, 7L, "讲".repeat(1200), List.of(huge));

        ArgumentCaptor<KnowledgeQuizAttempt> saved = ArgumentCaptor.forClass(KnowledgeQuizAttempt.class);
        verify(quizMapper).insert(saved.capture());
        KnowledgeQuizAttempt attempt = saved.getValue();
        assertThat(attempt.getVerdict()).hasSize(KnowledgeQuizService.MAX_VERDICT_CHARS + 1).endsWith("…");
        assertThat(attempt.getTestMode()).isEqualTo("AI_QA");
        assertThat(attempt.getConversationId()).isEqualTo(7L);
        assertThat(attempt.getItemsJson()).contains("问".repeat(500) + "…").contains("答".repeat(1000) + "…");
        assertThat(attempt.getScore()).isEqualByComparingTo("50.00");
    }

    @Test
    void openingAQuizHandsBackTheOriginalTextTheMasteryPictureAndWhatWasAskedLastTime() {
        RecitationMaterial material = material();
        material.setLastReviewAt(LocalDateTime.now().minusDays(5));
        material.setNextReviewAt(LocalDateTime.now().minusHours(1));
        givenMaterial(material);
        when(quizMapper.selectList(any())).thenReturn(List.of(historyRow()));

        KnowledgeQuizService.QuizSession session = service.openQuiz(42L, 9L, 7L);

        assertThat(session.quizSessionId()).isEqualTo(55L);
        assertThat(session.content()).isEqualTo("sin(a+b) = sin a cos b + cos a sin b");
        assertThat(session.contentTruncated()).isFalse();
        assertThat(session.masteryLevel()).isEqualTo("FAMILIAR");
        assertThat(session.reviewCount()).isEqualTo(2);
        // 下次复习时间已过，出题时智能体应当知道这条是到期该考的。
        assertThat(session.dueForReview()).isTrue();
        assertThat(session.recentQuizzes()).singleElement()
                .satisfies(quiz -> {
                    assertThat(quiz.score()).isEqualByComparingTo("60.00");
                    assertThat(quiz.correctCount()).isEqualTo(1);
                    assertThat(quiz.items().toString()).contains("上次问的题");
                });
        verify(quizMapper, never()).insert(any(KnowledgeQuizAttempt.class));
        verify(sessionMapper).insert(any(KnowledgeQuizSession.class));
    }

    @Test
    void openingAQuizBindsTheMaterialAndConversationIntoADurableSession() {
        givenMaterial(material());
        when(quizMapper.selectList(any())).thenReturn(List.of());

        service.openQuiz(42L, 9L, 7L);

        ArgumentCaptor<KnowledgeQuizSession> saved = ArgumentCaptor.forClass(KnowledgeQuizSession.class);
        verify(sessionMapper).insert(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(42L);
        assertThat(saved.getValue().getMaterialId()).isEqualTo(9L);
        assertThat(saved.getValue().getConversationId()).isEqualTo(7L);
        assertThat(saved.getValue().getStatus()).isEqualTo("OPEN");
        assertThat(saved.getValue().getExpiresAt()).isAfter(LocalDateTime.now().plusHours(23));
    }

    @Test
    void gradingAnAlreadyGradedSessionReturnsTheStoredAttemptWithoutAdvancingMasteryAgain() {
        RecitationMaterial material = material();
        givenMaterial(material);
        KnowledgeQuizSession graded = openSession(material.getId());
        graded.setStatus("GRADED");
        graded.setAttemptId(5L);
        when(sessionMapper.selectOne(any())).thenReturn(graded);
        when(quizMapper.selectOne(any())).thenReturn(historyRow());

        KnowledgeQuizService.QuizOutcome outcome = service.recordQuiz(
                42L, 9L, 7L, "重复提交", List.of(item("同一道题", "1")));

        assertThat(outcome.attemptId()).isEqualTo(5L);
        assertThat(outcome.score()).isEqualByComparingTo("60.00");
        verify(sessionMapper, never()).update(isNull(), any());
        verify(quizMapper, never()).insert(any(KnowledgeQuizAttempt.class));
        verify(materialMapper, never()).update(isNull(), any());
    }

    @Test
    void aQuizSessionCannotBeGradedFromAnotherConversation() {
        givenMaterial(material());

        assertThatThrownBy(() -> service.recordQuiz(
                42L, 9L, 99L, "", List.of(item("题", "1"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不属于当前对话");
        verify(quizMapper, never()).insert(any(KnowledgeQuizAttempt.class));
    }

    @Test
    void openingAQuizTruncatesAnOverlongTextAndSaysSoSoTheAgentDoesNotAskAboutUnseenParts() {
        RecitationMaterial material = material();
        material.setContent("推".repeat(KnowledgeQuizService.QUIZ_CONTENT_CHARS + 500));
        givenMaterial(material);
        when(quizMapper.selectList(any())).thenReturn(List.of());

        KnowledgeQuizService.QuizSession session = service.openQuiz(42L, 9L);

        assertThat(session.content()).hasSize(KnowledgeQuizService.QUIZ_CONTENT_CHARS + 1).endsWith("…");
        assertThat(session.contentTruncated()).isTrue();
        assertThat(session.contentLength()).isEqualTo(KnowledgeQuizService.QUIZ_CONTENT_CHARS + 500);
        assertThat(session.dueForReview()).isFalse();
    }

    @Test
    void aKnowledgeThatIsNotYoursIsNotFoundOnEitherThePathThatReadsOrThePathThatGrades() {
        when(materialMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.openQuiz(42L, 9L)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.recordQuiz(42L, 9L, 7L, "", List.of(item("题", "1"))))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.listQuizzes(42L, 9L)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.openQuiz(null, 9L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("未登录");
        verify(materialMapper, never()).update(isNull(), any());
    }

    @Test
    void listingQuizzesReturnsTheOwnersGradedHistoryWithTheEvidenceDeserialized() {
        givenMaterial(material());
        when(quizMapper.selectList(any())).thenReturn(List.of(historyRow()));

        List<KnowledgeQuizAttemptView> history = service.listQuizzes(42L, 9L);

        assertThat(history).singleElement().satisfies(quiz -> {
            assertThat(quiz.questionCount()).isEqualTo(2);
            assertThat(quiz.verdict()).isEqualTo("推导那题没答上");
            assertThat(quiz.items()).isInstanceOf(List.class);
        });
    }

    @Test
    void unreadableStoredEvidenceDegradesToNullInsteadOfBreakingTheWholeHistory() {
        givenMaterial(material());
        KnowledgeQuizAttempt broken = historyRow();
        broken.setItemsJson("{不是合法 JSON");
        when(quizMapper.selectList(any())).thenReturn(List.of(broken));

        assertThat(service.listQuizzes(42L, 9L)).singleElement()
                .satisfies(quiz -> assertThat(quiz.items()).isNull());
    }

    private KnowledgeQuizAttempt historyRow() {
        KnowledgeQuizAttempt row = new KnowledgeQuizAttempt();
        row.setId(5L);
        row.setUserId(42L);
        row.setMaterialId(9L);
        row.setTestMode("AI_QA");
        row.setQuestionCount(2);
        row.setCorrectCount(1);
        row.setScore(new BigDecimal("60.00"));
        row.setVerdict("推导那题没答上");
        row.setItemsJson("[{\"question\":\"上次问的题\",\"userAnswer\":\"忘了\",\"credit\":0}]");
        row.setCreatedAt(LocalDateTime.now().minusDays(5));
        return row;
    }

    private RecitationMaterial material() {
        RecitationMaterial material = new RecitationMaterial();
        material.setId(9L);
        material.setUserId(42L);
        material.setTitle("三角函数和角公式");
        material.setContent("sin(a+b) = sin a cos b + cos a sin b");
        material.setNormalizedContent("sinabsinacosbcosasinb");
        material.setKnowledgeType("CONCEPT");
        material.setTestMode("AI_QA");
        material.setMasteryScore(new BigDecimal("40.00"));
        material.setMasteryLevel("FAMILIAR");
        material.setReviewCount(2);
        return material;
    }

    private void givenMaterial(RecitationMaterial material) {
        when(materialMapper.selectOne(any())).thenReturn(material);
        when(sessionMapper.selectOne(any())).thenReturn(openSession(material.getId()));
    }

    private KnowledgeQuizSession openSession(Long materialId) {
        KnowledgeQuizSession session = new KnowledgeQuizSession();
        session.setId(9L);
        session.setUserId(42L);
        session.setMaterialId(materialId);
        session.setConversationId(7L);
        session.setStatus("OPEN");
        session.setExpiresAt(LocalDateTime.now().plusHours(1));
        return session;
    }

    private KnowledgeQuizService.GradedItem item(String question, String credit) {
        return new KnowledgeQuizService.GradedItem(question, "用户的原话", "点评",
                credit == null ? null : new BigDecimal(credit));
    }
}

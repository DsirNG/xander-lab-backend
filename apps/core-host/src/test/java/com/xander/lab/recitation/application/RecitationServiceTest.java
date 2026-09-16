package com.xander.lab.recitation.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.integration.oss.OssService;
import com.xander.lab.recitation.api.RecitationMaterialView;
import com.xander.lab.recitation.api.UpdateRecitationMaterialRequest;
import com.xander.lab.recitation.domain.ArchiveFilter;
import com.xander.lab.recitation.domain.KnowledgeQuizAttempt;
import com.xander.lab.recitation.domain.KnowledgeQuizSession;
import com.xander.lab.recitation.domain.RecitationAttempt;
import com.xander.lab.recitation.domain.RecitationMaterial;
import com.xander.lab.recitation.infrastructure.KnowledgeQuizAttemptMapper;
import com.xander.lab.recitation.infrastructure.KnowledgeQuizSessionMapper;
import com.xander.lab.recitation.infrastructure.RecitationAttemptMapper;
import com.xander.lab.recitation.infrastructure.RecitationMaterialMapper;
import com.xander.lab.recitation.infrastructure.RecitationProperties;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 知识库筛选是否真的下推到 SQL：这是"页面能搜到、智能体搜不到"那类问题的根。 */
class RecitationServiceTest {

    private RecitationMaterialMapper materialMapper;
    private RecitationAttemptMapper attemptMapper;
    private KnowledgeQuizAttemptMapper quizAttemptMapper;
    private KnowledgeQuizSessionMapper quizSessionMapper;
    private OssService ossService;
    private RecitationProperties properties;
    private RecitationService service;

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, RecitationMaterial.class);
        // 删除会连带清理录音任务与测验留档，这两张表的元数据同样要就位，否则 lambda 解析列名就会失败。
        TableInfoHelper.initTableInfo(assistant, RecitationAttempt.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeQuizAttempt.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeQuizSession.class);
    }

    @BeforeEach
    void setUp() {
        materialMapper = mock(RecitationMaterialMapper.class);
        attemptMapper = mock(RecitationAttemptMapper.class);
        quizAttemptMapper = mock(KnowledgeQuizAttemptMapper.class);
        quizSessionMapper = mock(KnowledgeQuizSessionMapper.class);
        ossService = mock(OssService.class);
        properties = new RecitationProperties();
        properties.setEnabled(true);
        service = new RecitationService(materialMapper, attemptMapper, quizAttemptMapper, quizSessionMapper,
                new RecitationAligner(), ossService, properties,
                inlineTransactions(), new ObjectMapper());
    }

    /** 让事务回调就地执行：否则写库动作全被 mock 吞掉，测试会对着一个什么都没做的服务通过。 */
    @SuppressWarnings("unchecked")
    private static TransactionTemplate inlineTransactions() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, Consumer.class).accept(mock(TransactionStatus.class));
            return null;
        }).when(template).executeWithoutResult(any());
        return template;
    }
    @Test
    void editingOnlyPunctuationKeepsTheMasteryRecord() {
        stored("床前明月光，疑是地上霜。");

        RecitationMaterialView view = service.updateMaterial(42L, 7L,
                request(null, "床前明月光。疑是地上霜！", null, null));

        // 归一化文本没变，说明要背的字一个没动，凭这个把 92 分清零等于凭空罚用户一次。
        assertThat(capturedUpdateSet()).contains("content=")
                .doesNotContain("mastery_score").doesNotContain("mastery_level")
                .doesNotContain("next_review_at");
        assertThat(view.masteryScore()).isEqualByComparingTo("92");
        assertThat(view.masteryLevel()).isEqualTo("MASTERED");
        assertThat(view.nextReviewAt()).isNotNull();
    }

    @Test
    void replacingTheTextResetsMasteryBecauseTheOldScoreMeasuredTheOldText() {
        stored("床前明月光，疑是地上霜。");

        RecitationMaterialView view = service.updateMaterial(42L, 7L,
                request(null, "国破山河在，城春草木深。", null, null));

        assertThat(capturedUpdateSet()).contains("mastery_score=").contains("mastery_level=")
                .contains("next_review_at=");
        assertThat(view.masteryScore()).isEqualByComparingTo("0");
        assertThat(view.masteryLevel()).isEqualTo("NEW");
        assertThat(view.nextReviewAt()).isNull();
        // 测过几次是历史事实，不随改稿一起抹掉。
        assertThat(view.reviewCount()).isEqualTo(4);
        assertThat(capturedUpdateSet()).doesNotContain("review_count");
    }

    @Test
    void changingTheTypeWithoutATestModePicksTheModeThatMatchesTheNewType() {
        stored("勾股定理的证明");

        RecitationMaterialView view = service.updateMaterial(42L, 7L,
                request(null, null, "concept", null));

        // 不自动改考法的话，概念题会继续让用户对着屏幕录音背诵。
        assertThat(view.knowledgeType()).isEqualTo("CONCEPT");
        assertThat(view.testMode()).isEqualTo("AI_QA");
        assertThat(capturedUpdateSet()).contains("test_mode=").contains("mastery_level=");
        assertThat(view.masteryLevel()).isEqualTo("NEW");
    }
    @Test
    void renamingTouchesNeitherTheTextNorTheMastery() {
        stored("床前明月光");

        RecitationMaterialView view = service.updateMaterial(42L, 7L,
                request("静夜思（全文）", null, null, null));

        assertThat(view.title()).isEqualTo("静夜思（全文）");
        assertThat(view.masteryLevel()).isEqualTo("MASTERED");
        assertThat(capturedUpdateSet()).contains("title=")
                .doesNotContain("content=").doesNotContain("mastery_score");
    }

    @Test
    void anUpdateThatChangesNothingDoesNotTouchTheRow() {
        RecitationMaterial material = stored("床前明月光");

        RecitationMaterialView view = service.updateMaterial(42L, 7L,
                request("静夜思", "床前明月光", "recitation", "audio_recitation"));

        // 保存按钮点两下不该刷新 updated_at，否则"最近修改"排序会被空保存搅乱。
        verify(materialMapper, never()).update(any(), any());
        assertThat(view.updatedAt()).isEqualTo(material.getUpdatedAt());
    }

    @Test
    void aBlankContentIsRejectedInsteadOfWipingTheText() {
        stored("床前明月光");

        assertThatThrownBy(() -> service.updateMaterial(42L, 7L, request(null, "   ", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        verify(materialMapper, never()).update(any(), any());
    }

    @Test
    void contentWithNothingToReciteIsRejected() {
        stored("床前明月光");

        assertThatThrownBy(() -> service.updateMaterial(42L, 7L, request(null, "，。！？", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(materialMapper, never()).update(any(), any());
    }

    @Test
    void anUnsupportedTestModeIsRejectedRatherThanSilentlyIgnored() {
        stored("床前明月光");

        assertThatThrownBy(() -> service.updateMaterial(42L, 7L, request(null, null, null, "心算")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(materialMapper, never()).update(any(), any());
    }
    @Test
    void archivingStampsTheRowSoTheKnowledgeLeavesTheDailyList() {
        stored("床前明月光");

        RecitationMaterialView view = service.archiveMaterial(42L, 7L, true);

        assertThat(view.archivedAt()).isNotNull();
        assertThat(capturedUpdateSet()).contains("archived_at=");
        // 越权归档别人的知识只会更新 0 行，而不是按 id 盲改。
        assertThat(capturedUpdateWhere()).contains("user_id = ?");
    }

    @Test
    void restoringClearsTheArchiveStampSoTheKnowledgeComesBack() {
        RecitationMaterial material = stored("床前明月光");
        material.setArchivedAt(LocalDateTime.now().minusDays(3));

        RecitationMaterialView view = service.archiveMaterial(42L, 7L, false);

        assertThat(view.archivedAt()).isNull();
        assertThat(capturedUpdateSet()).contains("archived_at=");
    }

    @Test
    void theDefaultListAndTheDueQueueBothHideArchivedKnowledge() {
        when(materialMapper.selectList(any())).thenReturn(List.of());

        service.searchMaterials(42L, new RecitationService.MaterialQuery(null, null, true, 10));

        assertThat(capturedSql()).contains("archived_at IS NULL").contains("next_review_at <= ?");
    }

    @Test
    void theArchivedViewShowsOnlyWhatWasPutAway() {
        when(materialMapper.selectList(any())).thenReturn(List.of());

        service.searchMaterials(42L,
                new RecitationService.MaterialQuery(null, null, false, 10, ArchiveFilter.ARCHIVED));

        assertThat(capturedSql()).contains("archived_at IS NOT NULL");
    }

    @Test
    void askingForEverythingDropsTheArchiveCondition() {
        when(materialMapper.selectList(any())).thenReturn(List.of());

        service.searchMaterials(42L,
                new RecitationService.MaterialQuery(null, null, false, 10, ArchiveFilter.ALL));

        assertThat(capturedSql()).doesNotContain("archived_at");
    }

    @Test
    void anOmittedArchiveRangeMeansOnlyKnowledgeStillInUse() {
        assertThat(new RecitationService.MaterialQuery(null, null, false, 10, null).archive())
                .isEqualTo(ArchiveFilter.ACTIVE);
        assertThat(RecitationService.MaterialQuery.all().archive()).isEqualTo(ArchiveFilter.ACTIVE);
    }
    @Test
    void deletingClearsTheQuizRecordsTheAttemptsAndTheMaterialItself() {
        stored("床前明月光");
        when(attemptMapper.selectList(any())).thenReturn(List.of(attempt("https://cdn.test/a.mp3")));

        service.deleteMaterial(42L, 7L);

        InOrder order = inOrder(attemptMapper, quizAttemptMapper, quizSessionMapper, materialMapper, ossService);
        // 音频地址必须在删库之前读出来，否则记录一没就再也找不到该删哪些对象。
        order.verify(attemptMapper).selectList(any());
        order.verify(quizAttemptMapper).delete(any());
        order.verify(quizSessionMapper).delete(any());
        order.verify(attemptMapper).delete(any());
        order.verify(materialMapper).delete(any());
        order.verify(ossService).deleteByUrlQuietly("https://cdn.test/a.mp3");
    }

    @Test
    void repeatingARecordingRequestReturnsTheExistingTaskWithoutUploadingAgain() {
        stored("床前明月光");
        RecitationAttempt existing = attempt("https://oss/voice.webm");
        existing.setId(88L);
        existing.setUserId(42L);
        existing.setMaterialId(7L);
        existing.setClientRequestId("request-123");
        existing.setStatus("PENDING");
        when(attemptMapper.selectOne(any())).thenReturn(existing);
        org.springframework.mock.web.MockMultipartFile file = new org.springframework.mock.web.MockMultipartFile(
                "file", "voice.webm", "audio/webm", new byte[]{1, 2, 3});

        var result = service.createAttempt(42L, 7L, file, "request-123");

        assertThat(result.id()).isEqualTo(88L);
        verify(attemptMapper, never()).insert(any(RecitationAttempt.class));
        verifyNoInteractions(ossService);
    }

    @Test
    void attemptsWithoutAudioAreNotSentToObjectStorage() {
        stored("床前明月光");
        when(attemptMapper.selectList(any())).thenReturn(List.of(attempt(null), attempt("  ")));

        service.deleteMaterial(42L, 7L);

        verifyNoInteractions(ossService);
        verify(materialMapper).delete(any());
    }

    @Test
    void anotherUsersKnowledgeCannotBeEditedArchivedOrDeleted() {
        when(materialMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.updateMaterial(42L, 7L, request("改个名", null, null, null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("不存在");
        assertThatThrownBy(() -> service.archiveMaterial(42L, 7L, true))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.deleteMaterial(42L, 7L))
                .isInstanceOf(ResponseStatusException.class);

        verify(materialMapper, never()).update(any(), any());
        verify(materialMapper, never()).delete(any());
        verifyNoInteractions(quizAttemptMapper, quizSessionMapper, ossService);
    }

    @Test
    void aMissingUserIsRejectedBeforeAnythingIsWritten() {
        assertThatThrownBy(() -> service.deleteMaterial(null, 7L))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("未登录");

        verifyNoInteractions(materialMapper, attemptMapper, quizAttemptMapper, ossService);
    }
    /** 一条已经背熟的知识：掌握度非零，才能看出哪些改动会把它清零。 */
    private RecitationMaterial stored(String content) {
        RecitationMaterial material = new RecitationMaterial();
        material.setId(7L);
        material.setUserId(42L);
        material.setTitle("静夜思");
        material.setContent(content);
        material.setNormalizedContent(new RecitationAligner().normalize(content));
        material.setKnowledgeType("RECITATION");
        material.setTestMode("AUDIO_RECITATION");
        material.setMasteryScore(BigDecimal.valueOf(92));
        material.setMasteryLevel("MASTERED");
        material.setReviewCount(4);
        material.setLastReviewAt(LocalDateTime.now().minusDays(2));
        material.setNextReviewAt(LocalDateTime.now().plusDays(30));
        material.setCreatedAt(LocalDateTime.now().minusDays(40));
        material.setUpdatedAt(LocalDateTime.now().minusDays(2));
        when(materialMapper.selectOne(any())).thenReturn(material);
        return material;
    }

    private UpdateRecitationMaterialRequest request(String title, String content, String type, String mode) {
        return new UpdateRecitationMaterialRequest(title, content, type, mode);
    }

    private RecitationAttempt attempt(String audioUrl) {
        RecitationAttempt attempt = new RecitationAttempt();
        attempt.setAudioUrl(audioUrl);
        return attempt;
    }

    private String capturedUpdateSet() {
        return capturedUpdate().getSqlSet();
    }

    private String capturedUpdateWhere() {
        return capturedUpdate().getTargetSql();
    }

    private LambdaUpdateWrapper<RecitationMaterial> capturedUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper<RecitationMaterial>> captor = updateCaptor();
        verify(materialMapper).update(isNull(), captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<LambdaUpdateWrapper<RecitationMaterial>> updateCaptor() {
        return ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    }

    @Test
    void searchLevelAndDueFiltersAllBecomePartOfTheQuery() {
        when(materialMapper.selectList(any())).thenReturn(List.of());

        service.searchMaterials(42L, new RecitationService.MaterialQuery("勾股", "recalling", true, 10));

        String sql = capturedSql();
        assertThat(sql).contains("user_id = ?");
        assertThat(sql).contains("title LIKE ?").contains("content LIKE ?");
        assertThat(sql).contains("mastery_level = ?");
        assertThat(sql).contains("next_review_at IS NOT NULL").contains("next_review_at <= ?");
        assertThat(sql).contains("LIMIT 10");
    }

    @Test
    void anEmptyFilterQueriesTheWholeLibraryWithoutSpuriousConditions() {
        when(materialMapper.selectList(any())).thenReturn(List.of());

        service.listMaterials(42L);

        String sql = capturedSql();
        assertThat(sql).contains("user_id = ?");
        assertThat(sql).doesNotContain("LIKE").doesNotContain("mastery_level").doesNotContain("next_review_at");
    }

    @Test
    void theListLimitIsClampedSoOneCallCannotDragTheWholeLibraryBack() {
        when(materialMapper.selectList(any())).thenReturn(List.of());

        service.searchMaterials(42L, new RecitationService.MaterialQuery(null, null, false, 5000));

        assertThat(capturedSql()).contains("LIMIT 100");
    }

    @Test
    void countUsesTheSameConditionsSoCallersCanTellNoMatchFromNotAllReturned() {
        when(materialMapper.selectCount(any())).thenReturn(24L);

        assertThat(service.countMaterials(42L,
                new RecitationService.MaterialQuery("定理", null, false, 10))).isEqualTo(24L);

        ArgumentCaptor<LambdaQueryWrapper<RecitationMaterial>> captor = wrapperCaptor();
        verify(materialMapper).selectCount(captor.capture());
        assertThat(captor.getValue().getTargetSql()).contains("title LIKE ?");
    }

    @Test
    void aMissingCountIsReportedAsZeroRatherThanBlowingUp() {
        when(materialMapper.selectCount(any())).thenReturn(null);

        assertThat(service.countMaterials(42L)).isZero();
    }

    private String capturedSql() {
        ArgumentCaptor<LambdaQueryWrapper<RecitationMaterial>> captor = wrapperCaptor();
        verify(materialMapper).selectList(captor.capture());
        return captor.getValue().getTargetSql();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<LambdaQueryWrapper<RecitationMaterial>> wrapperCaptor() {
        return ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    }
}

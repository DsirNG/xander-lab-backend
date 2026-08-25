package com.xander.lab.recitation.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.integration.oss.OssService;
import com.xander.lab.recitation.domain.RecitationMaterial;
import com.xander.lab.recitation.infrastructure.RecitationAttemptMapper;
import com.xander.lab.recitation.infrastructure.RecitationMaterialMapper;
import com.xander.lab.recitation.infrastructure.RecitationProperties;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 知识库筛选是否真的下推到 SQL：这是"页面能搜到、智能体搜不到"那类问题的根。 */
class RecitationServiceTest {

    private RecitationMaterialMapper materialMapper;
    private RecitationService service;

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), RecitationMaterial.class);
    }

    @BeforeEach
    void setUp() {
        materialMapper = mock(RecitationMaterialMapper.class);
        service = new RecitationService(materialMapper, mock(RecitationAttemptMapper.class),
                new RecitationAligner(), mock(OssService.class), new RecitationProperties(),
                mock(TransactionTemplate.class), new ObjectMapper());
    }
    // PLACEHOLDER_TESTS

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

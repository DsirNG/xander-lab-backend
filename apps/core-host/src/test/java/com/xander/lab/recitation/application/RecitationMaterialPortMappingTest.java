package com.xander.lab.recitation.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.integration.api.OssPort;
import com.xander.lab.recitation.api.CreateRecitationMaterialRequest;
import com.xander.lab.recitation.api.RecitationMaterialView;
import com.xander.lab.recitation.domain.ArchiveFilter;
import com.xander.lab.recitation.infrastructure.KnowledgeQuizAttemptMapper;
import com.xander.lab.recitation.infrastructure.KnowledgeQuizSessionMapper;
import com.xander.lab.recitation.infrastructure.RecitationAttemptMapper;
import com.xander.lab.recitation.infrastructure.RecitationMaterialMapper;
import com.xander.lab.recitation.infrastructure.RecitationProperties;
import com.xander.lab.recitation.spi.MaterialDraft;
import com.xander.lab.recitation.spi.MaterialFilter;
import com.xander.lab.recitation.spi.MaterialView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * 钉住三个换算的<b>逐字段落点</b>：{@code RecitationMaterialView → MaterialView}、
 * {@code MaterialDraft → CreateRecitationMaterialRequest}、
 * {@code MaterialFilter → RecitationService.MaterialQuery}。
 *
 * <p>契约模块看不见域类型，所以 {@code RecordShapeTest} 只能钉契约自己的分量名与顺序；
 * 真正会静默出错的是换算那一行。{@link MaterialView} 在 {@code id} 之后连着四个
 * {@code String}（{@code title} / {@code content} / {@code knowledgeType} / {@code testMode}），
 * 换位不会有任何编译错误 —— 把正文当标题写进智能体的提示词，只有这里会红。</p>
 *
 * <h2>为什么用 spy 而不是把 mapper 也 mock 起来</h2>
 * <p>端口方法是"薄壳 + 换算 + 转调同类富方法"，所以只要把<b>富方法</b>打桩，
 * 就能单独观察换算这一行，不需要 MyBatis 的 lambda 元数据、不需要真库。
 * Mockito 的 spy 与 Spring 的 AOP 代理不同：真实方法体里的 {@code this.富方法(...)}
 * <b>会</b>被 spy 拦下，所以自调用在这里是可观察的。（也正因为 Spring 的代理<b>不</b>这样，
 * 端口方法必须自己标 {@code @Transactional} —— 见 {@code PortDelegationTransactionTest}。）</p>
 */
@ExtendWith(MockitoExtension.class)
class RecitationMaterialPortMappingTest {

    private static final LocalDateTime NEXT_REVIEW_AT = LocalDateTime.parse("2026-03-04T05:06:07");

    @Mock private RecitationMaterialMapper materialMapper;
    @Mock private RecitationAttemptMapper attemptMapper;
    @Mock private KnowledgeQuizAttemptMapper quizAttemptMapper;
    @Mock private KnowledgeQuizSessionMapper quizSessionMapper;
    @Mock private OssPort ossPort;
    @Mock private TransactionTemplate transactionTemplate;

    private RecitationService newSpy() {
        RecitationProperties properties = new RecitationProperties();
        return spy(new RecitationService(materialMapper, attemptMapper, quizAttemptMapper,
                quizSessionMapper, new RecitationAligner(), ossPort, properties,
                transactionTemplate, new ObjectMapper()));
    }

    /**
     * 每个分量都必须来自它自己那个源字段。字面量<b>互不相同</b>：
     * 四个相邻的 {@code String} 用一组全不相同的值即可钉满（全不同 ⇒ 任何换位都至少有一处对不上），
     * 不需要像相邻 {@code Boolean} 那样用两组互补值 —— 布尔只有两个取值。
     */
    @Test
    void everyNarrowedComponentComesFromItsOwnSourceField() {
        RecitationService service = newSpy();
        RecitationMaterialView source = new RecitationMaterialView(
                11L, "标题字段", "正文字段", 999, "类型字段", "考法字段",
                BigDecimal.valueOf(77), "等级字段", 33,
                LocalDateTime.parse("2020-01-02T03:04:05"), NEXT_REVIEW_AT,
                LocalDateTime.parse("2021-02-03T04:05:06"),
                LocalDateTime.parse("2022-03-04T05:06:07"),
                LocalDateTime.parse("2023-04-05T06:07:08"));
        doReturn(source).when(service).getMaterialForView(7L, 5L);

        MaterialView view = service.getMaterial(7L, 5L);

        assertThat(view.id()).isEqualTo(11L);
        assertThat(view.title()).isEqualTo("标题字段");
        assertThat(view.content()).isEqualTo("正文字段");
        assertThat(view.knowledgeType()).isEqualTo("类型字段");
        assertThat(view.testMode()).isEqualTo("考法字段");
        assertThat(view.masteryScore()).isEqualByComparingTo("77");
        assertThat(view.masteryLevel()).isEqualTo("等级字段");
        assertThat(view.reviewCount()).isEqualTo(33);
        assertThat(view.nextReviewAt()).isEqualTo(NEXT_REVIEW_AT);
    }

    /** 列表路径走同一个换算，不能是第二份实现。 */
    @Test
    void theListPathUsesTheSameSingleMappingPoint() {
        RecitationService service = newSpy();
        doReturn(List.of(new RecitationMaterialView(
                11L, "标题字段", "正文字段", 999, "类型字段", "考法字段",
                BigDecimal.valueOf(77), "等级字段", 33,
                null, NEXT_REVIEW_AT, null, null, null)))
                .when(service).searchMaterials(eq(7L), any(RecitationService.MaterialQuery.class));

        List<MaterialView> views = service.searchMaterials(7L, MaterialFilter.all());

        assertThat(views).hasSize(1);
        assertThat(views.get(0).id()).isEqualTo(11L);
        assertThat(views.get(0).nextReviewAt()).isEqualTo(NEXT_REVIEW_AT);
    }

    /**
     * ⚠️ "没传"与"传了空"不是一回事：{@code testMode} 为 {@code null} 表示"按知识类型自动匹配考法"。
     * 换算层给它补一个默认字符串，就会把"自动匹配"悄悄变成"固定一种考法"，而且不报错。
     */
    @Test
    void materialDraftIsCarriedThroughWithoutDefaultingTestMode() {
        RecitationService service = newSpy();
        doReturn(null).when(service).createMaterial(eq(7L), any(CreateRecitationMaterialRequest.class));
        ArgumentCaptor<CreateRecitationMaterialRequest> captor =
                ArgumentCaptor.forClass(CreateRecitationMaterialRequest.class);

        service.createMaterial(7L, new MaterialDraft("标题字段", "正文字段", "类型字段", null));

        org.mockito.Mockito.verify(service).createMaterial(eq(7L), captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("标题字段");
        assertThat(captor.getValue().content()).isEqualTo("正文字段");
        assertThat(captor.getValue().knowledgeType()).isEqualTo("类型字段");
        assertThat(captor.getValue().testMode())
                .as("null 表示'按类型自动匹配考法'，换算层不许兜底成某个默认字符串")
                .isNull();
    }

    /** 显式给了 testMode 时也必须原样带过去（上一条只证明了 null 那一半）。 */
    @Test
    void materialDraftCarriesAnExplicitTestModeToo() {
        RecitationService service = newSpy();
        doReturn(null).when(service).createMaterial(eq(7L), any(CreateRecitationMaterialRequest.class));
        ArgumentCaptor<CreateRecitationMaterialRequest> captor =
                ArgumentCaptor.forClass(CreateRecitationMaterialRequest.class);

        service.createMaterial(7L, new MaterialDraft("标题字段", "正文字段", "类型字段", "考法字段"));

        org.mockito.Mockito.verify(service).createMaterial(eq(7L), captor.capture());
        assertThat(captor.getValue().testMode()).isEqualTo("考法字段");
    }

    /**
     * 筛选条件的换算：四个分量逐个落位，并且<b>归档维度落回默认的 ACTIVE</b>。
     *
     * <p>最后那条是"智能体看不到归档知识"这条不变量在换算层的一半；另一半（真的生成了
     * {@code archived_at IS NULL} 谓词）由 {@code RecitationServiceTest} 的
     * {@code thePortSearchLandsOnTheSameActiveOnlyPredicateAsThePagesDefaultList} 钉住。</p>
     */
    @Test
    void theFilterConversionKeepsEveryComponentAndLandsOnTheActiveOnlyDefault() {
        RecitationService service = newSpy();
        doReturn(List.of()).when(service).searchMaterials(eq(7L), any(RecitationService.MaterialQuery.class));
        ArgumentCaptor<RecitationService.MaterialQuery> captor =
                ArgumentCaptor.forClass(RecitationService.MaterialQuery.class);

        service.searchMaterials(7L, new MaterialFilter("搜索词字段", "等级过滤字段", true, 42));

        org.mockito.Mockito.verify(service).searchMaterials(eq(7L), captor.capture());
        RecitationService.MaterialQuery query = captor.getValue();
        assertThat(query.search()).isEqualTo("搜索词字段");
        assertThat(query.masteryLevel()).isEqualTo("等级过滤字段");
        assertThat(query.dueOnly()).isTrue();
        assertThat(query.limit()).isEqualTo(42);
        assertThat(query.archive())
                .as("契约筛选条件不带归档维度 ⇒ 必须落到默认的 ACTIVE，否则智能体会看见用户归档掉的知识")
                .isEqualTo(ArchiveFilter.ACTIVE);
    }

    /** 统计走同一份换算，不能是第二份。 */
    @Test
    void countMaterialsUsesTheSameConversion() {
        RecitationService service = newSpy();
        doReturn(9L).when(service).countMaterials(eq(7L), any(RecitationService.MaterialQuery.class));
        ArgumentCaptor<RecitationService.MaterialQuery> captor =
                ArgumentCaptor.forClass(RecitationService.MaterialQuery.class);

        long count = service.countMaterials(7L, new MaterialFilter("搜索词字段", null, false, 42));

        assertThat(count).isEqualTo(9L);
        org.mockito.Mockito.verify(service).countMaterials(eq(7L), captor.capture());
        assertThat(captor.getValue().search()).isEqualTo("搜索词字段");
        assertThat(captor.getValue().archive()).isEqualTo(ArchiveFilter.ACTIVE);
    }
}

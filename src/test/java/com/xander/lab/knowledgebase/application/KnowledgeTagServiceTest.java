package com.xander.lab.knowledgebase.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.xander.lab.knowledgebase.api.KnowledgeTagView;
import com.xander.lab.knowledgebase.domain.KnowledgeFile;
import com.xander.lab.knowledgebase.domain.KnowledgeFileTag;
import com.xander.lab.knowledgebase.domain.KnowledgeTag;
import com.xander.lab.knowledgebase.domain.TagSource;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeBaseProperties;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeFileMapper;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeFileTagMapper;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeTagMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 标签的增删改查，以及最容易出错的一条：手动标签与自动标签共用一张关联表，
 * 重新识别只能整体替换 AUTO 那一批，用户手打的标签不能被抹掉。
 */
class KnowledgeTagServiceTest {

    private KnowledgeTagMapper tagMapper;
    private KnowledgeFileTagMapper fileTagMapper;
    private KnowledgeFileMapper fileMapper;
    private KnowledgeBaseProperties properties;
    private KnowledgeTagService service;

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeTag.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeFileTag.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeFile.class);
    }

    @BeforeEach
    void setUp() {
        tagMapper = mock(KnowledgeTagMapper.class);
        fileTagMapper = mock(KnowledgeFileTagMapper.class);
        fileMapper = mock(KnowledgeFileMapper.class);
        properties = new KnowledgeBaseProperties();
        service = new KnowledgeTagService(tagMapper, fileTagMapper, fileMapper, properties);

        AtomicLong nextTagId = new AtomicLong(10);
        when(tagMapper.insert(any(KnowledgeTag.class))).thenAnswer(invocation -> {
            KnowledgeTag tag = invocation.getArgument(0);
            tag.setId(nextTagId.getAndIncrement());
            return 1;
        });
        when(tagMapper.selectCount(any())).thenReturn(0L);
        when(tagMapper.selectOne(any())).thenReturn(null);
        when(tagMapper.selectList(any())).thenReturn(List.of());
        when(tagMapper.selectBatchIds(any())).thenReturn(List.of());
        when(fileTagMapper.selectCount(any())).thenReturn(0L);
        when(fileTagMapper.selectList(any())).thenReturn(List.of());
        // 关联操作前都会先确认文件属于当前用户，默认让它通过。
        when(fileMapper.selectCount(any())).thenReturn(1L);
    }

    @Test
    void creatingATagCollapsesWhitespaceInTheName() {
        KnowledgeTagView view = service.create(7L, "  读书  笔记 ", "#112233", "  说明  ");

        assertThat(view.name()).isEqualTo("读书 笔记");
        assertThat(view.color()).isEqualTo("#112233");
        assertThat(view.description()).isEqualTo("说明");
        assertThat(view.usageCount()).isZero();
    }

    @Test
    void aDuplicateTagNameIsRejected() {
        when(tagMapper.selectOne(any())).thenReturn(tag(10L, "读书笔记"));

        assertThatThrownBy(() -> service.create(7L, "读书笔记", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("标签已存在");
        verify(tagMapper, never()).insert(any(KnowledgeTag.class));
    }

    @Test
    void theTagCountCeilingIsEnforced() {
        properties.setMaxTags(3);
        when(tagMapper.selectCount(any())).thenReturn(3L);

        assertThatThrownBy(() -> service.create(7L, "新标签", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("标签数量已达上限 3");
    }

    @Test
    void anInvalidColorIsRejectedBeforeAnythingIsWritten() {
        assertThatThrownBy(() -> service.create(7L, "标签", "red", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("#RRGGBB");
        verify(tagMapper, never()).insert(any(KnowledgeTag.class));
    }

    @Test
    void anEmptyOrOverlongTagNameIsRejected() {
        assertThatThrownBy(() -> service.create(7L, "   ", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("标签名不能为空");
        assertThatThrownBy(() -> service.create(7L, "a".repeat(61), null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("最长 60 个字符");
    }

    @Test
    void updatingWithNullFieldsLeavesThemAloneAndABlankColorClearsIt() {
        KnowledgeTag existing = tag(10L, "读书笔记");
        existing.setColor("#112233");
        existing.setDescription("旧说明");
        when(tagMapper.selectOne(any())).thenReturn(existing);

        KnowledgeTagView view = service.update(7L, 10L, null, "", null);

        // 局部更新的约定：null 表示不改，空串表示清空——两者不能混为一谈。
        assertThat(view.name()).isEqualTo("读书笔记");
        assertThat(view.color()).isNull();
        assertThat(view.description()).isEqualTo("旧说明");
    }

    @Test
    void renamingOntoAnotherExistingTagIsRejected() {
        when(tagMapper.selectOne(any())).thenReturn(tag(10L, "甲"), tag(11L, "乙"));

        assertThatThrownBy(() -> service.update(7L, 10L, "乙", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("标签已存在：乙");
    }

    @Test
    void renamingToItsOwnNameIsAllowed() {
        KnowledgeTag existing = tag(10L, "甲");
        when(tagMapper.selectOne(any())).thenReturn(existing, existing);

        assertThat(service.update(7L, 10L, "甲", null, null).name()).isEqualTo("甲");
    }

    @Test
    void deletingATagAlsoUnlinksItFromEveryFile() {
        when(tagMapper.selectOne(any())).thenReturn(tag(10L, "读书笔记"));

        service.delete(7L, 10L);

        // 只删词表行会在关联表里留下指向不存在标签的僵尸关联。
        assertThat(capturedLinkDeleteValues()).contains(10L);
        verify(tagMapper).deleteById(10L);
    }

    @Test
    void anUnknownTagIsNotFoundRatherThanNullDereferenced() {
        assertThatThrownBy(() -> service.update(7L, 999L, "甲", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("标签不存在");
        assertThatThrownBy(() -> service.delete(7L, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("标签不存在");
    }

    @Test
    void attachingByNameCreatesTheMissingTagAndLinksItAsManual() {
        service.attachByNames(7L, 500L, List.of("读书笔记", "  ", "认知"));

        // 前端不必先建标签再打标；空白名跳过而不是建出一个空标签。
        verify(tagMapper, org.mockito.Mockito.times(2)).insert(any(KnowledgeTag.class));
        assertThat(insertedLinks()).extracting(KnowledgeFileTag::getSource)
                .containsOnly(TagSource.MANUAL.name());
        assertThat(insertedLinks()).extracting(KnowledgeFileTag::getFileId).containsOnly(500L);
    }

    @Test
    void attachingATagTheFileAlreadyHasDoesNotDuplicateTheLink() {
        when(tagMapper.selectOne(any())).thenReturn(tag(10L, "读书笔记"));
        when(fileTagMapper.selectCount(any())).thenReturn(1L);

        service.attachByNames(7L, 500L, List.of("读书笔记"));

        verify(fileTagMapper, never()).insert(any(KnowledgeFileTag.class));
    }

    @Test
    void autoTagsReplaceOnlyTheAutoBatchSoManualTagsSurvive() {
        int applied = service.applyAutoTags(7L, 500L, List.of("记忆曲线", "间隔重复"));

        assertThat(applied).isEqualTo(2);
        // 删除条件里必须带 source=AUTO，否则每次重新识别都会清掉用户手打的标签。
        assertThat(capturedLinkDeleteValues()).contains(TagSource.AUTO.name())
                .doesNotContain(TagSource.MANUAL.name());
        assertThat(insertedLinks()).extracting(KnowledgeFileTag::getSource)
                .containsOnly(TagSource.AUTO.name());
    }

    @Test
    void anAutoTagNameAlreadyLinkedManuallyKeepsItsManualSource() {
        when(tagMapper.selectOne(any())).thenReturn(tag(10L, "记忆曲线"));
        when(fileTagMapper.selectCount(any())).thenReturn(1L);

        int applied = service.applyAutoTags(7L, 500L, List.of("记忆曲线"));

        // 已经手动打过的标签不改来源，否则下一次重算会把它当自动标签删掉。
        assertThat(applied).isZero();
        verify(fileTagMapper, never()).insert(any(KnowledgeFileTag.class));
    }

    @Test
    void duplicateAndOverlongAutoTagNamesAreDroppedQuietly() {
        int applied = service.applyAutoTags(7L, 500L,
                List.of("记忆曲线", "记忆曲线", "a".repeat(61), " "));

        assertThat(applied).isEqualTo(1);
    }

    @Test
    void autoTaggingGivesUpQuietlyWhenTheTagCeilingIsReached() {
        properties.setMaxTags(1);
        when(tagMapper.selectCount(any())).thenReturn(1L);

        int applied = service.applyAutoTags(7L, 500L, List.of("记忆曲线"));

        // 自动标签撞上限只是少一个标签，不该让整次上传失败。
        assertThat(applied).isZero();
        verify(tagMapper, never()).insert(any(KnowledgeTag.class));
    }

    @Test
    void replacingManualTagsDropsOnlyTheManualLinks() {
        service.replaceManual(7L, 500L, List.of("新标签"));

        assertThat(capturedLinkDeleteValues()).contains(TagSource.MANUAL.name())
                .doesNotContain(TagSource.AUTO.name());
        assertThat(insertedLinks()).extracting(KnowledgeFileTag::getSource)
                .containsOnly(TagSource.MANUAL.name());
    }

    @Test
    void aFileTagCarriesItsSourceSoTheUiCanTellThemApart() {
        when(fileTagMapper.selectList(any()))
                .thenReturn(List.of(link(500L, 10L, TagSource.AUTO), link(500L, 11L, TagSource.MANUAL)));
        when(tagMapper.selectBatchIds(any())).thenReturn(List.of(tag(10L, "乙自动"), tag(11L, "甲手动")));

        List<KnowledgeTagView> views = service.tagsOfFile(7L, 500L);

        // 同一个文件上手动标签与自动标签并存，来源必须逐个跟着标签走。
        assertThat(views).extracting(KnowledgeTagView::name, KnowledgeTagView::source)
                .containsExactlyInAnyOrder(
                        tuple("甲手动", TagSource.MANUAL.name()),
                        tuple("乙自动", TagSource.AUTO.name()));
        assertThat(views).extracting(KnowledgeTagView::usageCount).containsOnlyNulls();
    }

    @Test
    void theTagListReportsHowManyFilesUseEachTag() {
        when(tagMapper.selectList(any())).thenReturn(List.of(tag(10L, "甲"), tag(11L, "乙")));
        when(fileTagMapper.selectList(any())).thenReturn(
                List.of(link(500L, 10L, TagSource.MANUAL), link(501L, 10L, TagSource.AUTO)));

        List<KnowledgeTagView> views = service.list(7L);

        assertThat(views).extracting(KnowledgeTagView::usageCount).containsExactly(2, 0);
        assertThat(views).extracting(KnowledgeTagView::source).containsOnlyNulls();
    }

    @Test
    void filteringByTagsRequiresEveryTagNotJustOne() {
        when(fileTagMapper.selectList(any())).thenReturn(List.of(
                link(500L, 10L, TagSource.MANUAL),
                link(500L, 11L, TagSource.AUTO),
                link(501L, 10L, TagSource.MANUAL)));

        // 多标签筛选是交集语义：501 只命中一个标签，不该出现在结果里。
        assertThat(service.fileIdsByTags(7L, List.of(10L, 11L))).containsExactly(500L);
        assertThat(service.fileIdsByTags(7L, List.of())).isEmpty();
        assertThat(service.fileIdsByTags(7L, null)).isEmpty();
    }

    @Test
    void taggingAFileThatIsNotYoursIsNotFound() {
        when(fileMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.attachByNames(7L, 500L, List.of("甲")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("文件不存在");
        verify(fileTagMapper, never()).insert(any(KnowledgeFileTag.class));
    }

    @Test
    void anonymousCallersAreRejectedBeforeAnyQuery() {
        assertThatThrownBy(() -> service.list(null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("未登录");
        assertThatThrownBy(() -> service.create(null, "甲", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("未登录");
        org.mockito.Mockito.verifyNoInteractions(tagMapper);
    }

    @Test
    void deletingAFileClearsAllOfItsTagLinks() {
        service.deleteLinksOfFile(7L, 500L);

        assertThat(capturedLinkDeleteValues()).contains(500L);
    }

    private static KnowledgeTag tag(Long id, String name) {
        KnowledgeTag tag = new KnowledgeTag();
        tag.setId(id);
        tag.setUserId(7L);
        tag.setName(name);
        return tag;
    }

    private static KnowledgeFileTag link(Long fileId, Long tagId, TagSource source) {
        KnowledgeFileTag link = new KnowledgeFileTag();
        link.setUserId(7L);
        link.setFileId(fileId);
        link.setTagId(tagId);
        link.setSource(source.name());
        return link;
    }

    private List<KnowledgeFileTag> insertedLinks() {
        ArgumentCaptor<KnowledgeFileTag> captor = ArgumentCaptor.forClass(KnowledgeFileTag.class);
        verify(fileTagMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        return captor.getAllValues();
    }

    /**
     * delete(...) 的条件值也走参数占位符，用它确认删的是哪一批关联。
     *
     * <p>与 update 的 {@code set(...)} 不同，{@code eq(...)} 的取值是懒的：不先渲染一次 SQL，
     * {@code paramNameValuePairs} 会一直是空的。</p>
     */
    private List<Object> capturedLinkDeleteValues() {
        ArgumentCaptor<LambdaQueryWrapper<KnowledgeFileTag>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(fileTagMapper, org.mockito.Mockito.atLeastOnce()).delete(captor.capture());
        List<Object> values = new ArrayList<>();
        captor.getAllValues().forEach(wrapper -> {
            wrapper.getSqlSegment();
            values.addAll(wrapper.getParamNameValuePairs().values());
        });
        return values;
    }
}

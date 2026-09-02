package com.xander.lab.knowledgebase.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.xander.lab.automation.application.BlogNotificationService;
import com.xander.lab.automation.domain.BlogNotification;
import com.xander.lab.knowledgebase.domain.ChapterDetector;
import com.xander.lab.knowledgebase.domain.DetectedChapter;
import com.xander.lab.knowledgebase.domain.KnowledgeChapter;
import com.xander.lab.knowledgebase.domain.KnowledgeFile;
import com.xander.lab.knowledgebase.domain.KnowledgeFileContent;
import com.xander.lab.knowledgebase.domain.StructureStatus;
import com.xander.lab.knowledgebase.infrastructure.DocumentTextExtractor;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeBaseProperties;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeChapterMapper;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeFileContentMapper;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeFileMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识化处理的分流规则。需求里最容易做丢的一条就在这儿：很大一本分章节的书不自动出整本结构图，
 * 而是发通知告诉用户「看哪一章时再为那一章生成」——所以阈值内外两条路径都要有用例钉住。
 */
class KnowledgeIngestionServiceTest {

    private DocumentTextExtractor extractor;
    private KnowledgeFileMapper fileMapper;
    private KnowledgeFileContentMapper contentMapper;
    private KnowledgeChapterMapper chapterMapper;
    private KnowledgeStructureService structureService;
    private KnowledgeTagService tagService;
    private BlogNotificationService notificationService;
    private KnowledgeBaseProperties properties;
    private KnowledgeIngestionService service;

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeChapter.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeFile.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeFileContent.class);
    }

    @BeforeEach
    void setUp() {
        extractor = mock(DocumentTextExtractor.class);
        fileMapper = mock(KnowledgeFileMapper.class);
        contentMapper = mock(KnowledgeFileContentMapper.class);
        chapterMapper = mock(KnowledgeChapterMapper.class);
        structureService = mock(KnowledgeStructureService.class);
        tagService = mock(KnowledgeTagService.class);
        notificationService = mock(BlogNotificationService.class);
        properties = new KnowledgeBaseProperties();
        service = new KnowledgeIngestionService(extractor, fileMapper, contentMapper, chapterMapper,
                structureService, tagService, notificationService, properties);

        AtomicLong nextChapterId = new AtomicLong(900);
        when(chapterMapper.insert(any(KnowledgeChapter.class))).thenAnswer(invocation -> {
            KnowledgeChapter chapter = invocation.getArgument(0);
            chapter.setId(nextChapterId.getAndIncrement());
            return 1;
        });
    }

    private static final String TWO_CHAPTERS = """
            # 第一章 甲
            - 要点一
            ## 1.1 甲之一
            - 子要点
            # 第二章 乙
            - 要点二
            """;

    @Test
    void anOrdinaryDocumentGetsItsWholeGraphGeneratedImmediately() {
        KnowledgeFile file = file();
        when(extractor.extract(any(), eq("md")))
                .thenReturn(new DocumentTextExtractor.ExtractionResult(true, TWO_CHAPTERS));

        service.ingest(file, TWO_CHAPTERS.getBytes(), null);

        assertThat(file.getStructureStatus()).isEqualTo(StructureStatus.READY.name());
        assertThat(file.getTextExtracted()).isTrue();
        assertThat(file.getCharCount()).isEqualTo(TWO_CHAPTERS.length());
        assertThat(file.getChapterCount()).isEqualTo(2);
        assertThat(file.getStructureMessage()).contains("结构图已生成");
        verify(structureService).generateForFile(eq(7L), eq(file), eq(TWO_CHAPTERS), any());
        // 阈值内的文档整本出图，没有任何需要告诉用户的事。
        verify(notificationService, never())
                .notifyResource(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void everyChapterIsMarkedReadyWhenTheWholeBookGraphWasGenerated() {
        when(extractor.extract(any(), eq("md")))
                .thenReturn(new DocumentTextExtractor.ExtractionResult(true, TWO_CHAPTERS));

        service.ingest(file(), TWO_CHAPTERS.getBytes(), null);

        // 章节状态是「这一章的图有没有」的唯一依据，整本出图后必须一起翻成 READY。
        assertThat(insertedChapters()).allSatisfy(chapter ->
                assertThat(chapter.getStructureStatus()).isEqualTo(StructureStatus.READY.name()));
        verify(chapterMapper, org.mockito.Mockito.times(3)).updateById(any(KnowledgeChapter.class));
    }

    @Test
    void aBookWithTooManyChaptersDefersTheGraphAndTellsTheUserWhy() {
        properties.setAutoStructureChapterThreshold(2);
        KnowledgeFile file = file();
        when(extractor.extract(any(), eq("md")))
                .thenReturn(new DocumentTextExtractor.ExtractionResult(true, TWO_CHAPTERS));

        service.ingest(file, TWO_CHAPTERS.getBytes(), null);

        // 需求原话：很大一本分章节的书不自动生成，发通知说明看某一章时再生成。
        assertThat(file.getStructureStatus()).isEqualTo(StructureStatus.CHAPTER_ON_DEMAND.name());
        assertThat(file.getStructureMessage()).contains("阅读某一章时会为该章单独生成");
        verify(structureService, never()).generateForFile(any(), any(), anyString(), any());
        verify(notificationService).notifyResource(eq(7L),
                eq(BlogNotification.TYPE_KNOWLEDGE_STRUCTURE_PENDING),
                anyString(), anyString(),
                eq(BlogNotification.RESOURCE_KNOWLEDGE_FILE), eq(500L));
    }

    @Test
    void aVeryLongDocumentIsDeferredEvenWithOnlyAFewChapters() {
        properties.setAutoStructureMaxChars(10);
        KnowledgeFile file = file();
        when(extractor.extract(any(), eq("md")))
                .thenReturn(new DocumentTextExtractor.ExtractionResult(true, TWO_CHAPTERS));

        service.ingest(file, TWO_CHAPTERS.getBytes(), null);

        // 章节少但正文长也要走按需：整本出图的耗时是按字数涨的。
        assertThat(file.getStructureStatus()).isEqualTo(StructureStatus.CHAPTER_ON_DEMAND.name());
        assertThat(file.getStructureMessage()).contains("字，篇幅较大");
    }

    @Test
    void deferredChaptersStayPendingSoTheyCanBeGeneratedWhenOpened() {
        properties.setAutoStructureChapterThreshold(1);
        when(extractor.extract(any(), eq("md")))
                .thenReturn(new DocumentTextExtractor.ExtractionResult(true, TWO_CHAPTERS));

        service.ingest(file(), TWO_CHAPTERS.getBytes(), null);

        assertThat(insertedChapters()).allSatisfy(chapter ->
                assertThat(chapter.getStructureStatus()).isEqualTo(StructureStatus.PENDING.name()));
        verify(chapterMapper, never()).updateById(any(KnowledgeChapter.class));
    }

    @Test
    void aNotificationFailureDoesNotFailTheIngestion() {
        properties.setAutoStructureChapterThreshold(1);
        KnowledgeFile file = file();
        when(extractor.extract(any(), eq("md")))
                .thenReturn(new DocumentTextExtractor.ExtractionResult(true, TWO_CHAPTERS));
        when(notificationService.notifyResource(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyLong())).thenThrow(new IllegalStateException("通知服务不可用"));

        service.ingest(file, TWO_CHAPTERS.getBytes(), null);

        // 文件已经存进对象存储了，发不出通知不该让整次上传看起来失败。
        assertThat(file.getStructureStatus()).isEqualTo(StructureStatus.CHAPTER_ON_DEMAND.name());
        verify(fileMapper).updateById(file);
    }

    @Test
    void sectionsAreNestedUnderTheChapterTheyBelongTo() {
        when(extractor.extract(any(), eq("md")))
                .thenReturn(new DocumentTextExtractor.ExtractionResult(true, TWO_CHAPTERS));

        service.ingest(file(), TWO_CHAPTERS.getBytes(), null);

        List<KnowledgeChapter> chapters = insertedChapters();
        assertThat(chapters).extracting(KnowledgeChapter::getTitle)
                .containsExactly("第一章 甲", "1.1 甲之一", "第二章 乙");
        assertThat(chapters.get(0).getParentId()).isEqualTo(KnowledgeChapter.ROOT_PARENT_ID);
        assertThat(chapters.get(1).getParentId()).isEqualTo(chapters.get(0).getId());
        assertThat(chapters.get(2).getParentId()).isEqualTo(KnowledgeChapter.ROOT_PARENT_ID);
        assertThat(chapters).extracting(KnowledgeChapter::getOrderIndex).containsExactly(0, 1, 2);
    }

    @Test
    void anUnsupportedFormatKeepsTheFileUsableWithoutChaptersOrGraph() {
        KnowledgeFile file = file();
        file.setExtension("pdf");
        when(extractor.extract(any(), eq("pdf")))
                .thenReturn(DocumentTextExtractor.ExtractionResult.unsupported());

        service.ingest(file, new byte[]{1, 2, 3}, List.of("论文"));

        assertThat(file.getStructureStatus()).isEqualTo(StructureStatus.UNSUPPORTED.name());
        assertThat(file.getTextExtracted()).isFalse();
        assertThat(file.getChapterCount()).isZero();
        verify(chapterMapper, never()).insert(any(KnowledgeChapter.class));
        verify(contentMapper, never()).insert(any(KnowledgeFileContent.class));
        // 抽不出正文也不该丢掉用户手填的标签。
        verify(tagService).attachByNames(7L, 500L, List.of("论文"));
        verify(tagService, never()).applyAutoTags(anyLong(), anyLong(), any());
    }

    @Test
    void autoTagsComeFromTopLevelTitlesWithTheNumberingStripped() {
        List<DetectedChapter> detected = ChapterDetector.detect("""
                # 第一章 记忆曲线
                正文
                ## 1.1 不该成为标签
                正文
                # 2. 间隔重复
                正文
                # Chapter 3: Retrieval Practice
                正文
                """, 100);

        List<String> tags = service.autoTags(detected);

        // 「第一章」「2.」「Chapter 3:」这类序号不是主题词，留着会污染标签表。
        assertThat(tags).containsExactly("记忆曲线", "间隔重复", "Retrieval Practice");
    }

    @Test
    void autoTagsAreCappedAndSkipTitlesThatAreTooShort() {
        properties.setMaxAutoTags(2);
        List<DetectedChapter> detected = ChapterDetector.detect("""
                # 甲
                正文
                # 记忆曲线
                正文
                # 间隔重复
                正文
                # 提取练习
                正文
                """, 100);

        assertThat(service.autoTags(detected)).containsExactly("记忆曲线", "间隔重复");
    }

    @Test
    void aFileWithoutRecognizableChaptersStillGetsAGraphAndNoAutoTags() {
        String prose = "今天读完了这本书。它讲的是记忆与遗忘。";
        KnowledgeFile file = file();
        when(extractor.extract(any(), eq("md")))
                .thenReturn(new DocumentTextExtractor.ExtractionResult(true, prose));

        service.ingest(file, prose.getBytes(), null);

        assertThat(file.getStructureStatus()).isEqualTo(StructureStatus.READY.name());
        assertThat(file.getChapterCount()).isZero();
        assertThat(file.getStructureMessage()).contains("未识别到章节标题");
        verify(structureService).generateForFile(eq(7L), eq(file), eq(prose), eq(List.of()));
        verify(tagService).applyAutoTags(7L, 500L, List.of());
    }

    @Test
    void manualTagsAreKeptAlongsideTheAutomaticOnes() {
        String text = """
                # 第一章 记忆曲线
                - 要点
                # 第二章 间隔重复
                - 要点
                """;
        when(extractor.extract(any(), eq("md")))
                .thenReturn(new DocumentTextExtractor.ExtractionResult(true, text));

        service.ingest(file(), text.getBytes(), List.of("读书笔记"));

        // 自动标签会被整批替换，手填的走另一条路，两者不能互相清掉。
        verify(tagService).applyAutoTags(7L, 500L, List.of("记忆曲线", "间隔重复"));
        verify(tagService).attachByNames(7L, 500L, List.of("读书笔记"));
    }

    @Test
    void reIdentifyingUsesTheStoredTextInsteadOfDownloadingTheFileAgain() {
        KnowledgeFile file = file();

        service.ingestExtractedText(file, TWO_CHAPTERS, null);

        // 章节偏移的坐标系就是抽取出的正文，没必要回源对象存储。
        assertThat(file.getStructureStatus()).isEqualTo(StructureStatus.READY.name());
        verify(extractor, never()).extract(any(), anyString());
        verify(contentMapper).insert(any(KnowledgeFileContent.class));
    }

    @Test
    void ingestingNullTextIsTreatedAsEmptyRatherThanCrashing() {
        KnowledgeFile file = file();

        service.ingestExtractedText(file, null, null);

        assertThat(file.getCharCount()).isZero();
        assertThat(file.getStructureStatus()).isEqualTo(StructureStatus.READY.name());
    }

    @Test
    void markFailedRecordsTheFailureAndPointsAtTheManualRetry() {
        KnowledgeFile file = file();

        service.markFailed(file);

        assertThat(file.getStructureStatus()).isEqualTo(StructureStatus.FAILED.name());
        assertThat(file.getStructureMessage()).contains("手动重新识别");
        assertThat(file.getTextExtracted()).isFalse();
        verify(fileMapper).updateById(file);
    }

    @Test
    void resetKnowledgeClearsChaptersGraphAndStoredText() {
        service.resetKnowledge(7L, 500L);

        // 三样都要清：留下任何一样，重新识别的结果就会和旧数据混在一起。
        verify(chapterMapper).delete(any());
        verify(structureService).deleteByFile(7L, 500L);
        verify(contentMapper).deleteById(500L);
    }

    private static KnowledgeFile file() {
        KnowledgeFile file = new KnowledgeFile();
        file.setId(500L);
        file.setUserId(7L);
        file.setFolderId(3L);
        file.setOriginalName("book.md");
        file.setDisplayName("book.md");
        file.setExtension("md");
        return file;
    }

    private List<KnowledgeChapter> insertedChapters() {
        ArgumentCaptor<KnowledgeChapter> captor = ArgumentCaptor.forClass(KnowledgeChapter.class);
        verify(chapterMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        return captor.getAllValues();
    }
}

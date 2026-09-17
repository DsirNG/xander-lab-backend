package com.xander.lab.knowledgebase.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.xander.lab.automation.spi.NotificationPort;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上传后的知识化处理。这里最要紧的是需求里那条分流规则：普通文档整本出结构图，
 * 很大一本分章节的书不自动出图，只发通知说明「阅读某一章时再为该章生成」。
 */
class KnowledgeIngestionServiceTest {

    private DocumentTextExtractor extractor;
    private KnowledgeFileMapper fileMapper;
    private KnowledgeFileContentMapper contentMapper;
    private KnowledgeChapterMapper chapterMapper;
    private KnowledgeStructureService structureService;
    private KnowledgeTagService tagService;
    private NotificationPort notificationService;
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
        notificationService = mock(NotificationPort.class);
        properties = new KnowledgeBaseProperties();
        service = new KnowledgeIngestionService(extractor, fileMapper, contentMapper, chapterMapper,
                structureService, tagService, notificationService, properties);

        AtomicLong nextChapterId = new AtomicLong(1);
        when(chapterMapper.insert(any(KnowledgeChapter.class))).thenAnswer(invocation -> {
            KnowledgeChapter chapter = invocation.getArgument(0);
            chapter.setId(nextChapterId.getAndIncrement());
            return 1;
        });
    }

    @Test
    void anOrdinaryDocumentGetsItsWholeGraphRightAwayAndNoNotification() {
        KnowledgeFile file = file();
        String text = book(3);
        when(extractor.extract(any(), eq("md")))
                .thenReturn(new DocumentTextExtractor.ExtractionResult(true, text));

        service.ingest(file, text.getBytes(), null);

        assertThat(file.getStructureStatus()).isEqualTo(StructureStatus.READY.name());
        assertThat(file.getTextExtracted()).isTrue();
        assertThat(file.getCharCount()).isEqualTo(text.length());
        assertThat(file.getChapterCount()).isEqualTo(3);
        assertThat(file.getStructureMessage()).contains("结构图已生成");
        verify(structureService).generateForFile(eq(7L), eq(file), eq(text), anyList());
        verify(notificationService, never())
                .notifyResource(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong());
        // 整本出图时每一章都是现成的，章节状态要一并推到 READY。
        assertThat(insertedChapters()).allSatisfy(chapter ->
                assertThat(chapter.getStructureStatus()).isEqualTo(StructureStatus.READY.name()));
    }

    @Test
    void aLargeChapterizedBookDefersItsGraphAndNotifiesTheUser() {
        properties.setAutoStructureChapterThreshold(12);
        KnowledgeFile file = file();
        String text = book(15);
        when(extractor.extract(any(), eq("md")))
                .thenReturn(new DocumentTextExtractor.ExtractionResult(true, text));

        service.ingest(file, text.getBytes(), null);

        // 这就是需求里的那条规则：大部头不自动出图，改为看哪一章再生成，并且要通知用户。
        assertThat(file.getStructureStatus()).isEqualTo(StructureStatus.CHAPTER_ON_DEMAND.name());
        assertThat(file.getStructureMessage()).contains("阅读某一章时会为该章单独生成");
        verify(structureService, never()).generateForFile(any(), any(), anyString(), anyList());
        verify(notificationService).notifyResource(eq(7L),
                eq(NotificationPort.TYPE_KNOWLEDGE_STRUCTURE_PENDING),
                anyString(), anyString(), eq(NotificationPort.RESOURCE_KNOWLEDGE_FILE), eq(500L));
        // 章节目录照旧要落库，否则用户点开章节时没有可生成的对象。
        assertThat(insertedChapters()).hasSize(15);
        assertThat(insertedChapters()).allSatisfy(chapter ->
                assertThat(chapter.getStructureStatus()).isEqualTo(StructureStatus.PENDING.name()));
    }

    @Test
    void aVeryLongDocumentDefersEvenWithOnlyAFewChapters() {
        properties.setAutoStructureMaxChars(40);
        KnowledgeFile file = file();
        String text = book(3);
        when(extractor.extract(any(), eq("md")))
                .thenReturn(new DocumentTextExtractor.ExtractionResult(true, text));

        service.ingest(file, text.getBytes(), null);

        // 章节数不多但正文很长时同样不整本出图——出图成本跟字数一起涨。
        assertThat(text.length()).isGreaterThan(40);
        assertThat(file.getStructureStatus()).isEqualTo(StructureStatus.CHAPTER_ON_DEMAND.name());
        verify(structureService, never()).generateForFile(any(), any(), anyString(), anyList());
    }

    @Test
    void aFailedNotificationDoesNotFailTheIngestion() {
        properties.setAutoStructureChapterThreshold(1);
        KnowledgeFile file = file();
        String text = book(3);
        when(extractor.extract(any(), eq("md")))
                .thenReturn(new DocumentTextExtractor.ExtractionResult(true, text));
        when(notificationService.notifyResource(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyLong())).thenThrow(new IllegalStateException("通知服务不可用"));

        service.ingest(file, text.getBytes(), null);

        // 文件已经存好了，通知发不出去不该把整次知识化拖成失败。
        assertThat(file.getStructureStatus()).isEqualTo(StructureStatus.CHAPTER_ON_DEMAND.name());
        verify(fileMapper).updateById(file);
    }

    @Test
    void anUnsupportedFormatKeepsTheFileAndExplainsWhyThereIsNoGraph() {
        KnowledgeFile file = file();
        file.setExtension("pdf");
        when(extractor.extract(any(), eq("pdf")))
                .thenReturn(DocumentTextExtractor.ExtractionResult.unsupported());

        service.ingest(file, new byte[]{1, 2, 3}, List.of("论文"));

        assertThat(file.getStructureStatus()).isEqualTo(StructureStatus.UNSUPPORTED.name());
        assertThat(file.getTextExtracted()).isFalse();
        assertThat(file.getStructureMessage()).contains("PDF").contains("可正常保存与下载");
        // 抽不出正文也不该丢掉用户手填的标签，文件仍然要能被检索到。
        verify(tagService).attachByNames(7L, 500L, List.of("论文"));
        verify(contentMapper, never()).insert(any(KnowledgeFileContent.class));
        verify(chapterMapper, never()).insert(any(KnowledgeChapter.class));
    }

    @Test
    void sectionsArePersistedUnderTheChapterTheyBelongTo() {
        KnowledgeFile file = file();
        String text = """
                # 甲
                正文
                ## 甲一
                正文
                # 乙
                正文
                """;
        when(extractor.extract(any(), eq("md")))
                .thenReturn(new DocumentTextExtractor.ExtractionResult(true, text));

        service.ingest(file, text.getBytes(), null);

        List<KnowledgeChapter> chapters = insertedChapters();
        assertThat(chapters).extracting(KnowledgeChapter::getTitle).containsExactly("甲", "甲一", "乙");
        // 章节树靠 parentId 串起来：小节挂在它上面那一章下，下一章回到根。
        assertThat(chapters).extracting(KnowledgeChapter::getParentId)
                .containsExactly(KnowledgeChapter.ROOT_PARENT_ID, 1L, KnowledgeChapter.ROOT_PARENT_ID);
        assertThat(chapters).extracting(KnowledgeChapter::getOrderIndex).containsExactly(0, 1, 2);
        assertThat(file.getChapterCount()).isEqualTo(2);
    }

    @Test
    void aFileWithoutHeadingsStillGetsAGraphFromItsParagraphs() {
        KnowledgeFile file = file();
        String text = "今天读了一本书。它讲的是记忆。";
        when(extractor.extract(any(), eq("md")))
                .thenReturn(new DocumentTextExtractor.ExtractionResult(true, text));

        service.ingest(file, text.getBytes(), null);

        assertThat(file.getStructureStatus()).isEqualTo(StructureStatus.READY.name());
        assertThat(file.getChapterCount()).isZero();
        assertThat(file.getStructureMessage()).contains("未识别到章节标题");
        verify(structureService).generateForFile(eq(7L), eq(file), eq(text), eq(List.of()));
    }

    @Test
    void theExtractedTextIsStoredOnceReplacingAnyEarlierCopy() {
        KnowledgeFile file = file();
        String text = book(2);
        when(extractor.extract(any(), eq("md")))
                .thenReturn(new DocumentTextExtractor.ExtractionResult(true, text));

        service.ingest(file, text.getBytes(), null);

        // 重新识别会再走一遍这里，先删后插才不会留下两份正文。
        verify(contentMapper).deleteById(500L);
        ArgumentCaptor<KnowledgeFileContent> captor = ArgumentCaptor.forClass(KnowledgeFileContent.class);
        verify(contentMapper).insert(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo(text);
        assertThat(captor.getValue().getFileId()).isEqualTo(500L);
    }

    @Test
    void autoTagsComeFromTopLevelTitlesWithTheirNumberingStripped() {
        List<String> tags = service.autoTags(List.of(
                new DetectedChapter(1, "第一章 记忆的规律", 0, 10),
                new DetectedChapter(2, "1.1 遗忘曲线", 10, 20),
                new DetectedChapter(1, "Chapter 2: Spaced Repetition", 20, 30),
                new DetectedChapter(1, "3.2 间隔重复", 30, 40)));

        // 「第一章」「Chapter 2」「3.2」这类序号是位置信息，不是主题词。
        assertThat(tags).containsExactly("记忆的规律", "Spaced Repetition", "间隔重复");
    }

    @Test
    void autoTagsSkipTitlesThatAreTooShortOrTooLongAndStopAtTheCeiling() {
        properties.setMaxAutoTags(2);

        List<String> tags = service.autoTags(List.of(
                new DetectedChapter(1, "第一章 一", 0, 10),
                new DetectedChapter(1, "甲乙", 10, 20),
                new DetectedChapter(1, "a".repeat(61), 20, 30),
                new DetectedChapter(1, "丙丁", 30, 40),
                new DetectedChapter(1, "戊己", 40, 50)));

        assertThat(tags).containsExactly("甲乙", "丙丁");
    }

    @Test
    void manualTagsAreAttachedAlongsideTheAutoOnes() {
        KnowledgeFile file = file();
        String text = book(2);
        when(extractor.extract(any(), eq("md")))
                .thenReturn(new DocumentTextExtractor.ExtractionResult(true, text));

        service.ingest(file, text.getBytes(), List.of("读书笔记"));

        verify(tagService).applyAutoTags(eq(7L), eq(500L), eq(List.of("标题1", "标题2")));
        verify(tagService).attachByNames(7L, 500L, List.of("读书笔记"));
    }

    @Test
    void reIdentifyingReusesTheStoredTextInsteadOfDownloadingTheFileAgain() {
        KnowledgeFile file = file();
        String text = book(2);

        service.ingestExtractedText(file, text, null);

        // 章节偏移是以抽取出的正文为坐标系的，重算不需要回源对象存储。
        assertThat(file.getStructureStatus()).isEqualTo(StructureStatus.READY.name());
        verify(extractor, never()).extract(any(), anyString());
        verify(structureService).generateForFile(eq(7L), eq(file), eq(text), anyList());
    }

    @Test
    void resettingClearsTheOldChaptersGraphAndStoredTextBeforeARerun() {
        service.resetKnowledge(7L, 500L);

        // 不清场就重新识别会留下两套章节，结构图还会指向已经不存在的旧章节。
        ArgumentCaptor<LambdaQueryWrapper<KnowledgeChapter>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(chapterMapper).delete(captor.capture());
        captor.getValue().getSqlSegment();
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains(7L, 500L);
        verify(structureService).deleteByFile(7L, 500L);
        verify(contentMapper).deleteById(500L);
    }

    @Test
    void aFailedIngestionIsRecordedWithoutThrowingAwayTheFile() {
        KnowledgeFile file = file();

        service.markFailed(file);

        assertThat(file.getStructureStatus()).isEqualTo(StructureStatus.FAILED.name());
        assertThat(file.getStructureMessage()).contains("手动重新识别");
        assertThat(file.getTextExtracted()).isFalse();
        verify(fileMapper).updateById(file);
        // 失败只是没有结构图，正文与章节不该被这一步顺手删掉。
        verify(contentMapper, never()).deleteById(anyLong());
        verify(chapterMapper, never()).delete(any());
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

    private static String book(int chapterCount) {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= chapterCount; i++) {
            text.append("# 第").append(i).append("章 标题").append(i).append('\n')
                    .append("- 要点").append(i).append('\n');
        }
        return text.toString();
    }

    private List<KnowledgeChapter> insertedChapters() {
        ArgumentCaptor<KnowledgeChapter> captor = ArgumentCaptor.forClass(KnowledgeChapter.class);
        verify(chapterMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        return captor.getAllValues();
    }
}

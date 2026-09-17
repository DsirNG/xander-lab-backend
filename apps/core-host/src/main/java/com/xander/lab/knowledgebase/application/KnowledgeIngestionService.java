package com.xander.lab.knowledgebase.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xander.lab.automation.spi.NotificationPort;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 上传完成后的知识化处理：抽正文 → 识别章节 → 生成自动标签 → 决定结构图怎么出。
 *
 * <p>结构图的「自动 vs 按需」就在这里分流：普通文档直接整本出图；章节数或正文长度超过阈值的大部头
 * 只落章节目录，图留到用户点开某一章时再生成，并发一条通知说明这件事——这正是需求里
 * 「很大一本书分章节的就不自动生成，发通知告诉用户看哪一章的时候再生成」。</p>
 *
 * <p>本服务的失败不应该让上传失败：文件已经存进对象存储了，抽取或识别出问题只把
 * {@code structureStatus} 记成 FAILED，用户仍然能看到并下载文件。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIngestionService {

    private final DocumentTextExtractor extractor;
    private final KnowledgeFileMapper fileMapper;
    private final KnowledgeFileContentMapper contentMapper;
    private final KnowledgeChapterMapper chapterMapper;
    private final KnowledgeStructureService structureService;
    private final KnowledgeTagService tagService;
    private final NotificationPort notificationService;
    private final KnowledgeBaseProperties properties;

    /**
     * 对刚落库的文件做知识化处理，并就地更新它的结构图状态字段。
     *
     * <p>独立事务：文件本体已经在调用方的事务里提交/或已上传对象存储，知识化失败必须能够
     * 单独回滚，再由调用方调 {@link #markFailed} 记下失败状态——否则失败会连带把文件记录也回滚掉。</p>
     *
     * @param manualTagNames 上传时一并提交的手动标签名；与自动标签互不覆盖
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ingest(KnowledgeFile file, byte[] content, List<String> manualTagNames) {
        DocumentTextExtractor.ExtractionResult extraction = extractor.extract(content, file.getExtension());
        if (!extraction.supported()) {
            markUnsupported(file);
            if (manualTagNames != null && !manualTagNames.isEmpty()) {
                tagService.attachByNames(file.getUserId(), file.getId(), manualTagNames);
            }
            return;
        }
        apply(file, extraction.text(), manualTagNames);
    }

    /**
     * 用已经存在库里的正文重跑一遍识别。重新识别不必回源对象存储下载原文件：
     * 正文是抽取结果，章节偏移本来就以它为坐标系。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ingestExtractedText(KnowledgeFile file, String text, List<String> manualTagNames) {
        apply(file, text == null ? "" : text, manualTagNames);
    }

    private void apply(KnowledgeFile file, String text, List<String> manualTagNames) {
        saveContent(file, text);

        List<DetectedChapter> detected = ChapterDetector.detect(text, properties.getMaxChapters());
        List<KnowledgeChapter> chapters = persistChapters(file, detected);
        int topLevel = (int) detected.stream().filter(chapter -> chapter.level() == 1).count();

        file.setTextExtracted(true);
        file.setCharCount(text.length());
        file.setChapterCount(topLevel);

        boolean tooManyChapters = detected.size() > properties.getAutoStructureChapterThreshold();
        boolean tooLong = text.length() > properties.getAutoStructureMaxChars();
        if (tooManyChapters || tooLong) {
            deferToChapters(file, detected.size(), text.length());
        } else {
            structureService.generateForFile(file.getUserId(), file, text, chapters);
            chapters.forEach(chapter -> {
                chapter.setStructureStatus(StructureStatus.READY.name());
                chapterMapper.updateById(chapter);
            });
            file.setStructureStatus(StructureStatus.READY.name());
            file.setStructureMessage(detected.isEmpty()
                    ? "未识别到章节标题，已按段落要点生成结构图"
                    : "已识别 " + detected.size() + " 个标题，结构图已生成");
        }
        fileMapper.updateById(file);

        tagService.applyAutoTags(file.getUserId(), file.getId(), autoTags(detected));
        if (manualTagNames != null && !manualTagNames.isEmpty()) {
            tagService.attachByNames(file.getUserId(), file.getId(), manualTagNames);
        }
    }

    /** 知识化失败后单独记状态，文件本体保持可用。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(KnowledgeFile file) {
        file.setTextExtracted(Boolean.TRUE.equals(file.getTextExtracted()));
        file.setStructureStatus(StructureStatus.FAILED.name());
        file.setStructureMessage("结构图生成失败，可在文件详情页手动重新识别");
        fileMapper.updateById(file);
    }

    /** 重新识别前的清场：删掉旧章节、旧结构图与旧正文。由调用方紧接着调 {@link #ingest}。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resetKnowledge(Long userId, Long fileId) {
        chapterMapper.delete(new LambdaQueryWrapper<KnowledgeChapter>()
                .eq(KnowledgeChapter::getUserId, userId)
                .eq(KnowledgeChapter::getFileId, fileId));
        structureService.deleteByFile(userId, fileId);
        contentMapper.deleteById(fileId);
    }

    private void markUnsupported(KnowledgeFile file) {
        file.setTextExtracted(false);
        file.setCharCount(0);
        file.setChapterCount(0);
        file.setStructureStatus(StructureStatus.UNSUPPORTED.name());
        file.setStructureMessage("当前不支持从 " + file.getExtension().toUpperCase()
                + " 提取正文，文件可正常保存与下载，但没有章节与结构图");
        fileMapper.updateById(file);
    }

    private void deferToChapters(KnowledgeFile file, int chapterCount, int charCount) {
        file.setStructureStatus(StructureStatus.CHAPTER_ON_DEMAND.name());
        String message = "共识别 " + chapterCount + " 个标题、" + charCount
                + " 字，篇幅较大未自动生成整本结构图；阅读某一章时会为该章单独生成";
        file.setStructureMessage(message);
        try {
            notificationService.notifyResource(file.getUserId(),
                    NotificationPort.TYPE_KNOWLEDGE_STRUCTURE_PENDING,
                    "《" + file.getDisplayName() + "》未自动生成知识结构图",
                    message, NotificationPort.RESOURCE_KNOWLEDGE_FILE, file.getId());
        } catch (Exception e) {
            // 通知发不出去不影响文件本身可用，状态字段里已经写清楚了原因。
            log.warn("[知识库] 结构图待生成通知发送失败，fileId={}: {}", file.getId(), e.getMessage());
        }
    }

    private void saveContent(KnowledgeFile file, String text) {
        contentMapper.deleteById(file.getId());
        KnowledgeFileContent content = new KnowledgeFileContent();
        content.setFileId(file.getId());
        content.setUserId(file.getUserId());
        content.setContent(text);
        contentMapper.insert(content);
    }

    /** 按阅读顺序插入章节，并用一个层级栈把小节挂到它所属的章下面。 */
    private List<KnowledgeChapter> persistChapters(KnowledgeFile file, List<DetectedChapter> detected) {
        List<KnowledgeChapter> saved = new ArrayList<>(detected.size());
        Deque<KnowledgeChapter> ancestors = new ArrayDeque<>();
        int orderIndex = 0;
        for (DetectedChapter item : detected) {
            while (!ancestors.isEmpty() && ancestors.peek().getLevel() >= item.level()) {
                ancestors.pop();
            }
            KnowledgeChapter chapter = new KnowledgeChapter();
            chapter.setUserId(file.getUserId());
            chapter.setFileId(file.getId());
            chapter.setParentId(ancestors.isEmpty() ? KnowledgeChapter.ROOT_PARENT_ID : ancestors.peek().getId());
            chapter.setLevel(item.level());
            chapter.setOrderIndex(orderIndex++);
            chapter.setTitle(item.title());
            chapter.setStartOffset(item.startOffset());
            chapter.setEndOffset(item.endOffset());
            chapter.setCharCount(item.charCount());
            chapter.setStructureStatus(StructureStatus.PENDING.name());
            chapterMapper.insert(chapter);
            ancestors.push(chapter);
            saved.add(chapter);
        }
        return saved;
    }

    /**
     * 自动标签取顶层章标题，去掉「第N章」「1.2」这类序号前缀后作为主题词。
     * 章节识别不出来时返回空：胡乱切词生成的标签只会污染词表。
     */
    List<String> autoTags(List<DetectedChapter> detected) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (DetectedChapter chapter : detected) {
            if (chapter.level() != 1) {
                continue;
            }
            String name = stripNumbering(chapter.title());
            if (name.length() < 2 || name.length() > 60) {
                continue;
            }
            names.add(name);
            if (names.size() >= properties.getMaxAutoTags()) {
                break;
            }
        }
        return List.copyOf(names);
    }

    private String stripNumbering(String title) {
        String text = title == null ? "" : title.trim();
        text = text.replaceFirst("^第\\s*[0-9０-９一二三四五六七八九十百千零〇]{1,12}\\s*[章篇部编节節回讲講课課]\\s*[:：、.\\-—]*\\s*", "");
        text = text.replaceFirst("^(?i:chapter|part)\\s+[0-9ivxlc]{1,8}\\s*[:.\\-—]?\\s*", "");
        text = text.replaceFirst("^\\d{1,3}(?:\\.\\d{1,3})*[.、）)]?\\s*", "");
        return text.trim();
    }
}

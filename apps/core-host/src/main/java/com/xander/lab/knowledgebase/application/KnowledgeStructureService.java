package com.xander.lab.knowledgebase.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xander.lab.knowledgebase.api.KnowledgeStructureView;
import com.xander.lab.knowledgebase.domain.KnowledgeChapter;
import com.xander.lab.knowledgebase.domain.KnowledgeFile;
import com.xander.lab.knowledgebase.domain.KnowledgeFileContent;
import com.xander.lab.knowledgebase.domain.KnowledgeStructureBuilder;
import com.xander.lab.knowledgebase.domain.KnowledgeStructureNode;
import com.xander.lab.knowledgebase.domain.StructureStatus;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeBaseProperties;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeChapterMapper;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeFileContentMapper;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeFileMapper;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeStructureNodeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识结构图的生成、替换与读取。
 *
 * <p>一次生成的产物由 {@code (fileId, scopeChapterId)} 界定：整本书用 scope=0，按章生成用章节 id。
 * 重新生成只清掉同一个 scope 的旧节点，所以「重算整本书」不会顺手删掉用户按章生成的结果。</p>
 *
 * <p>生成过程是确定性的纯计算（见 {@link KnowledgeStructureBuilder}），不依赖模型服务：
 * 同一份正文重复生成必然得到同一张图，也不会因为缺少模型配置而失败。</p>
 */
@Service
@RequiredArgsConstructor
public class KnowledgeStructureService {

    private final KnowledgeStructureNodeMapper nodeMapper;
    private final KnowledgeChapterMapper chapterMapper;
    private final KnowledgeFileMapper fileMapper;
    private final KnowledgeFileContentMapper contentMapper;
    private final KnowledgeBaseProperties properties;

    /** 整本书维度生成。调用方已经拿到正文与章节，避免重复回读大文本。 */
    @Transactional
    public int generateForFile(Long userId, KnowledgeFile file, String text, List<KnowledgeChapter> chapters) {
        List<KnowledgeStructureBuilder.ChapterRef> refs = chapters.stream()
                .map(this::toRef)
                .toList();
        KnowledgeStructureBuilder.StructureDraft draft = KnowledgeStructureBuilder.buildForFile(
                file.getDisplayName(), text, refs, options());
        replaceScope(userId, file.getId(), KnowledgeStructureNode.FILE_SCOPE, draft);
        return countNodes(draft);
    }

    /**
     * 按章按需生成：大部头文件上传时不整本出图，用户点进某一章时调这里补上。
     * 已生成过就直接返回现成的，不重复算。
     */
    @Transactional
    public KnowledgeStructureView generateForChapter(Long userId, Long fileId, Long chapterId) {
        KnowledgeFile file = requireFile(userId, fileId);
        KnowledgeChapter chapter = requireChapter(userId, fileId, chapterId);
        if (StructureStatus.READY.name().equals(chapter.getStructureStatus())
                && nodeMapper.selectCount(scopeCondition(fileId, chapterId)) > 0) {
            return read(userId, fileId, chapterId);
        }
        if (!Boolean.TRUE.equals(file.getTextExtracted())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该文件没有可用正文，无法生成结构图");
        }
        String text = readContent(fileId);

        List<KnowledgeChapter> all = listChapters(userId, fileId);
        List<KnowledgeChapter> descendants = descendantsOf(all, chapter);
        KnowledgeStructureBuilder.StructureDraft draft = KnowledgeStructureBuilder.buildForChapter(
                toRef(chapter), descendants.stream().map(this::toRef).toList(), text, options());
        replaceScope(userId, fileId, chapterId, draft);

        chapter.setStructureStatus(StructureStatus.READY.name());
        chapterMapper.updateById(chapter);
        // 整本书维度仍是按需状态：其他章还没生成，文件状态不能改成 READY。
        return read(userId, fileId, chapterId);
    }

    /** 读取已生成的结构图。scope 为 null 时读整本书维度。 */
    @Transactional(readOnly = true)
    public KnowledgeStructureView read(Long userId, Long fileId, Long chapterId) {
        KnowledgeFile file = requireFile(userId, fileId);
        long scope = chapterId == null ? KnowledgeStructureNode.FILE_SCOPE : chapterId;
        if (scope != KnowledgeStructureNode.FILE_SCOPE) {
            requireChapter(userId, fileId, scope);
        }
        List<KnowledgeStructureNode> nodes = nodeMapper.selectList(scopeQuery(fileId, scope));
        return KnowledgeStructureView.of(file, scope, nodes);
    }

    /** 整本书维度重新生成（用户手动触发）。 */
    @Transactional
    public KnowledgeStructureView regenerateForFile(Long userId, Long fileId) {
        KnowledgeFile file = requireFile(userId, fileId);
        if (!Boolean.TRUE.equals(file.getTextExtracted())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该文件没有可用正文，无法生成结构图");
        }
        String text = readContent(fileId);
        generateForFile(userId, file, text, listChapters(userId, fileId));
        file.setStructureStatus(StructureStatus.READY.name());
        file.setStructureMessage("结构图已按当前正文重新生成");
        fileMapper.updateById(file);
        return read(userId, fileId, null);
    }

    /** 删除一个文件的全部结构节点，文件删除时调用。 */
    @Transactional
    public void deleteByFile(Long userId, Long fileId) {
        nodeMapper.delete(new LambdaQueryWrapper<KnowledgeStructureNode>()
                .eq(KnowledgeStructureNode::getUserId, userId)
                .eq(KnowledgeStructureNode::getFileId, fileId));
    }

    @Transactional(readOnly = true)
    public List<KnowledgeChapter> listChapters(Long userId, Long fileId) {
        return chapterMapper.selectList(new LambdaQueryWrapper<KnowledgeChapter>()
                .eq(KnowledgeChapter::getUserId, userId)
                .eq(KnowledgeChapter::getFileId, fileId)
                .orderByAsc(KnowledgeChapter::getOrderIndex));
    }

    /** 章节的后代：按阅读顺序紧跟其后、层级更深的连续一段。 */
    static List<KnowledgeChapter> descendantsOf(List<KnowledgeChapter> ordered, KnowledgeChapter chapter) {
        List<KnowledgeChapter> descendants = new ArrayList<>();
        boolean started = false;
        for (KnowledgeChapter candidate : ordered) {
            if (candidate.getId().equals(chapter.getId())) {
                started = true;
                continue;
            }
            if (!started) {
                continue;
            }
            if (candidate.getLevel() <= chapter.getLevel()) {
                break;
            }
            descendants.add(candidate);
        }
        return descendants;
    }

    private void replaceScope(Long userId, Long fileId, long scope,
                              KnowledgeStructureBuilder.StructureDraft draft) {
        nodeMapper.delete(scopeCondition(fileId, scope).eq(KnowledgeStructureNode::getUserId, userId));
        insert(userId, fileId, scope, KnowledgeStructureNode.ROOT_PARENT_ID, 0, draft);
    }

    private void insert(Long userId, Long fileId, long scope, long parentId, int orderIndex,
                        KnowledgeStructureBuilder.StructureDraft draft) {
        KnowledgeStructureNode node = new KnowledgeStructureNode();
        node.setUserId(userId);
        node.setFileId(fileId);
        node.setScopeChapterId(scope);
        node.setParentId(parentId);
        node.setNodeType(draft.type().name());
        node.setTitle(draft.title());
        node.setSummary(clampSummary(draft.summary()));
        node.setOrderIndex(orderIndex);
        node.setChapterId(draft.chapterId());
        nodeMapper.insert(node);
        int childIndex = 0;
        for (KnowledgeStructureBuilder.StructureDraft child : draft.children()) {
            insert(userId, fileId, scope, node.getId(), childIndex++, child);
        }
    }

    private String clampSummary(String summary) {
        if (summary == null) {
            return null;
        }
        return summary.length() > 500 ? summary.substring(0, 500) : summary;
    }

    private int countNodes(KnowledgeStructureBuilder.StructureDraft draft) {
        int total = 1;
        for (KnowledgeStructureBuilder.StructureDraft child : draft.children()) {
            total += countNodes(child);
        }
        return total;
    }

    private LambdaQueryWrapper<KnowledgeStructureNode> scopeCondition(Long fileId, long scope) {
        return new LambdaQueryWrapper<KnowledgeStructureNode>()
                .eq(KnowledgeStructureNode::getFileId, fileId)
                .eq(KnowledgeStructureNode::getScopeChapterId, scope);
    }

    /** 读取用的排序版本。删除与计数不能带 ORDER BY，因此与 {@link #scopeCondition} 分开。 */
    private LambdaQueryWrapper<KnowledgeStructureNode> scopeQuery(Long fileId, long scope) {
        return scopeCondition(fileId, scope)
                .orderByAsc(KnowledgeStructureNode::getParentId)
                .orderByAsc(KnowledgeStructureNode::getOrderIndex)
                .orderByAsc(KnowledgeStructureNode::getId);
    }

    private KnowledgeStructureBuilder.Options options() {
        return new KnowledgeStructureBuilder.Options(
                properties.getMaxPointsPerNode(), properties.getSummaryLength());
    }

    private KnowledgeStructureBuilder.ChapterRef toRef(KnowledgeChapter chapter) {
        return new KnowledgeStructureBuilder.ChapterRef(chapter.getId(), chapter.getLevel(),
                chapter.getTitle(), chapter.getStartOffset(), chapter.getEndOffset());
    }

    private String readContent(Long fileId) {
        KnowledgeFileContent content = contentMapper.selectById(fileId);
        if (content == null || content.getContent() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该文件的正文已不存在，无法生成结构图");
        }
        return content.getContent();
    }

    private KnowledgeFile requireFile(Long userId, Long fileId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        KnowledgeFile file = fileId == null ? null : fileMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeFile>()
                        .eq(KnowledgeFile::getId, fileId)
                        .eq(KnowledgeFile::getUserId, userId));
        if (file == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在");
        }
        return file;
    }

    private KnowledgeChapter requireChapter(Long userId, Long fileId, Long chapterId) {
        KnowledgeChapter chapter = chapterId == null ? null : chapterMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeChapter>()
                        .eq(KnowledgeChapter::getId, chapterId)
                        .eq(KnowledgeChapter::getFileId, fileId)
                        .eq(KnowledgeChapter::getUserId, userId));
        if (chapter == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "章节不存在");
        }
        return chapter;
    }
}

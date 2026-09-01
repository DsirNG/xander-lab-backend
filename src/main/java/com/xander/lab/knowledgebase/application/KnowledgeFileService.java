package com.xander.lab.knowledgebase.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xander.lab.integration.oss.OssService;
import com.xander.lab.integration.oss.UploadResponse;
import com.xander.lab.knowledgebase.api.KnowledgeChapterView;
import com.xander.lab.knowledgebase.api.KnowledgeFilePreviewView;
import com.xander.lab.knowledgebase.api.KnowledgeFileView;
import com.xander.lab.knowledgebase.api.KnowledgePageResult;
import com.xander.lab.knowledgebase.api.KnowledgeStructureView;
import com.xander.lab.knowledgebase.api.KnowledgeTagView;
import com.xander.lab.knowledgebase.domain.KnowledgeChapter;
import com.xander.lab.knowledgebase.domain.KnowledgeFile;
import com.xander.lab.knowledgebase.domain.KnowledgeFileContent;
import com.xander.lab.knowledgebase.domain.KnowledgeFolder;
import com.xander.lab.knowledgebase.domain.StructureStatus;
import com.xander.lab.knowledgebase.infrastructure.ChunkStagingStore;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeBaseProperties;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeChapterMapper;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeFileContentMapper;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeFileMapper;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeFolderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 知识库文件的上传、检索、维护与知识结构预览。
 *
 * <p>不注入 {@link KnowledgeFolderService}：那个服务为了递归删除已经注入了本服务，
 * 反向再注入就成了循环依赖。本服务要校验目标文件夹时直接查 {@code KnowledgeFolderMapper}。</p>
 *
 * <p>上传的顺序是「先传对象存储、再落库、最后知识化」：对象存储成功而落库失败只会留下一个孤儿对象，
 * 反过来则会留下一条指向不存在文件的记录，前者代价小得多。知识化失败也不让上传失败，
 * 只把结构图状态记成 FAILED（见 {@link KnowledgeIngestionService#markFailed}）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeFileService {

    /** 对象存储路径前缀。 */
    private static final String OSS_PATH_PREFIX = "knowledge-base/";
    /** 预览接口随手带回的正文片段长度，够首屏展示即可，不把整本书塞进响应。 */
    private static final int PREVIEW_EXCERPT_CHARS = 2000;

    private final KnowledgeFileMapper fileMapper;
    private final KnowledgeFolderMapper folderMapper;
    private final KnowledgeFileContentMapper contentMapper;
    private final KnowledgeChapterMapper chapterMapper;
    private final KnowledgeStructureService structureService;
    private final KnowledgeIngestionService ingestionService;
    private final KnowledgeTagService tagService;
    private final OssService ossService;
    private final KnowledgeBaseProperties properties;

    /**
     * 文件检索条件，全部可选。
     *
     * @param folderId  限定文件夹；为空表示全库检索
     * @param recursive true 时连同该文件夹的所有子文件夹一起搜
     * @param keyword   在展示名与原始文件名上做模糊匹配
     * @param tagIds    要求同时具备全部指定标签（AND 语义）
     */
    public record FileQuery(
            Long folderId,
            boolean recursive,
            String keyword,
            List<Long> tagIds,
            String extension,
            String structureStatus,
            long page,
            long size) {
    }

    /**
     * 表单直传。小文件走这条路，大文件用 {@link KnowledgeUploadService} 的分片接口。
     *
     * @param tagNames 上传时一并提交的手动标签，可为 null
     */
    public KnowledgeFileView upload(Long userId, Long folderId, MultipartFile file, List<String> tagNames) {
        requireUser(userId);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择要上传的文件");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取上传文件失败：" + e.getMessage());
        }
        KnowledgeFile saved = store(userId, folderId, file.getOriginalFilename(), file.getContentType(),
                content, ChunkStagingStore.sha256Hex(content), null, tagNames);
        return KnowledgeFileView.of(saved, tagService.tagsOfFile(userId, saved.getId()));
    }

    /**
     * 落一个文件：校验 → 传对象存储 → 入库 → 知识化。分片上传合并完成后复用同一条路径，
     * 保证「直传的文件」和「分片传的文件」在库里长得一模一样。
     *
     * <p>不加 {@code @Transactional}：整段里最慢的一步是对象存储上传，不能让它占着数据库连接与事务；
     * 而且知识化必须以独立事务提交或回滚，包在一个大事务里反而会互相拖累。</p>
     *
     * @param uploadSessionId 来源分片会话 id，直传为 null
     */
    public KnowledgeFile store(Long userId, Long folderId, String originalName, String contentType,
                               byte[] content, String fileHash, Long uploadSessionId, List<String> tagNames) {
        requireUser(userId);
        requireUploadableFolder(userId, folderId);
        String safeName = sanitizeName(originalName);
        String extension = extensionOf(safeName);
        validateExtension(extension);
        validateSize(content == null ? 0 : content.length);

        UploadResponse uploaded = ossService.upload(content, safeName, contentType, OSS_PATH_PREFIX);
        KnowledgeFile file = new KnowledgeFile();
        file.setUserId(userId);
        file.setFolderId(folderId);
        file.setOriginalName(safeName);
        file.setDisplayName(safeName);
        file.setExtension(extension);
        file.setContentType(contentType);
        file.setSizeBytes((long) (content == null ? 0 : content.length));
        file.setFileHash(fileHash);
        file.setStorageUrl(uploaded.getUrl());
        file.setTextExtracted(false);
        file.setCharCount(0);
        file.setChapterCount(0);
        file.setStructureStatus(StructureStatus.PENDING.name());
        file.setUploadSessionId(uploadSessionId);
        try {
            fileMapper.insert(file);
        } catch (RuntimeException e) {
            // 记录没落上，刚传上去的对象就成了孤儿，顺手删掉。
            ossService.deleteByUrlQuietly(uploaded.getUrl());
            throw e;
        }

        try {
            ingestionService.ingest(file, content, tagNames);
        } catch (RuntimeException e) {
            log.warn("[知识库] 知识化处理失败，fileId={}: {}", file.getId(), e.getMessage());
            ingestionService.markFailed(file);
        }
        return file;
    }

    /**
     * 文件检索。文件夹、关键词、标签、扩展名、结构图状态可任意组合，服务端分页。
     *
     * <p>标签筛选先在关联表上算出候选 id 再作为 {@code IN} 条件，而不是 JOIN：
     * 关联表按用户+标签有索引，先收窄再查主表比连表更稳定。候选为空时直接返回空页，
     * 不能落进 {@code IN ()} 那种非法 SQL。</p>
     */
    @Transactional(readOnly = true)
    public KnowledgePageResult<KnowledgeFileView> search(Long userId, FileQuery query) {
        requireUser(userId);
        long page = Math.max(query.page(), 1);
        long size = Math.min(Math.max(query.size(), 1), 100);

        LambdaQueryWrapper<KnowledgeFile> wrapper = new LambdaQueryWrapper<KnowledgeFile>()
                .eq(KnowledgeFile::getUserId, userId);
        if (query.folderId() != null && query.folderId() > KnowledgeFolder.ROOT_PARENT_ID) {
            if (query.recursive()) {
                List<Long> folderIds = subTreeFolderIds(userId, query.folderId());
                if (folderIds.isEmpty()) {
                    return KnowledgePageResult.empty(page, size);
                }
                wrapper.in(KnowledgeFile::getFolderId, folderIds);
            } else {
                wrapper.eq(KnowledgeFile::getFolderId, query.folderId());
            }
        }
        String keyword = query.keyword() == null ? "" : query.keyword().trim();
        if (!keyword.isEmpty()) {
            wrapper.and(inner -> inner.like(KnowledgeFile::getDisplayName, keyword)
                    .or().like(KnowledgeFile::getOriginalName, keyword));
        }
        if (query.tagIds() != null && !query.tagIds().isEmpty()) {
            List<Long> fileIds = tagService.fileIdsByTags(userId, query.tagIds());
            if (fileIds.isEmpty()) {
                return KnowledgePageResult.empty(page, size);
            }
            wrapper.in(KnowledgeFile::getId, fileIds);
        }
        if (query.extension() != null && !query.extension().isBlank()) {
            wrapper.eq(KnowledgeFile::getExtension, query.extension().trim().toLowerCase(Locale.ROOT));
        }
        if (query.structureStatus() != null && !query.structureStatus().isBlank()) {
            wrapper.eq(KnowledgeFile::getStructureStatus, query.structureStatus().trim().toUpperCase(Locale.ROOT));
        }
        wrapper.orderByDesc(KnowledgeFile::getCreatedAt).orderByDesc(KnowledgeFile::getId);

        Page<KnowledgeFile> result = fileMapper.selectPage(new Page<>(page, size), wrapper);
        Map<Long, List<KnowledgeTagView>> tags = tagService.tagsOfFiles(userId,
                result.getRecords().stream().map(KnowledgeFile::getId).toList());
        List<KnowledgeFileView> views = result.getRecords().stream()
                .map(file -> KnowledgeFileView.of(file, tags.getOrDefault(file.getId(), List.of())))
                .toList();
        return KnowledgePageResult.of(views, result.getTotal(), page, size);
    }

    @Transactional(readOnly = true)
    public KnowledgeFileView detail(Long userId, Long fileId) {
        KnowledgeFile file = requireFile(userId, fileId);
        return KnowledgeFileView.of(file, tagService.tagsOfFile(userId, fileId));
    }

    /** 章节目录，按阅读顺序装成树。 */
    @Transactional(readOnly = true)
    public List<KnowledgeChapterView> chapters(Long userId, Long fileId) {
        requireFile(userId, fileId);
        return KnowledgeChapterView.tree(structureService.listChapters(userId, fileId));
    }

    /**
     * 知识结构预览：文件信息 + 章节目录 + 整本书结构图 + 正文开头片段，一次给全。
     *
     * <p>大部头文件在这里拿到的是一张空图（{@code generated=false}），前端应展示
     * {@code file.structureMessage} 并在用户点开某一章时调按章生成接口。</p>
     */
    @Transactional(readOnly = true)
    public KnowledgeFilePreviewView preview(Long userId, Long fileId) {
        KnowledgeFile file = requireFile(userId, fileId);
        List<KnowledgeChapter> chapters = structureService.listChapters(userId, fileId);
        KnowledgeStructureView structure = structureService.read(userId, fileId, null);

        KnowledgeFileContent content = contentMapper.selectById(fileId);
        String text = content == null || content.getContent() == null ? "" : content.getContent();
        boolean truncated = text.length() > PREVIEW_EXCERPT_CHARS;
        String excerpt = truncated ? text.substring(0, PREVIEW_EXCERPT_CHARS) : text;

        return new KnowledgeFilePreviewView(
                KnowledgeFileView.of(file, tagService.tagsOfFile(userId, fileId)),
                KnowledgeChapterView.tree(chapters),
                structure,
                excerpt,
                truncated);
    }

    /** 读取抽取出的正文（供前端做全文阅读）。抽不出正文的格式返回 409 而不是空串。 */
    @Transactional(readOnly = true)
    public String content(Long userId, Long fileId) {
        KnowledgeFile file = requireFile(userId, fileId);
        if (!Boolean.TRUE.equals(file.getTextExtracted())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该文件没有可用正文：" + (file.getStructureMessage() == null ? "" : file.getStructureMessage()));
        }
        KnowledgeFileContent content = contentMapper.selectById(fileId);
        return content == null || content.getContent() == null ? "" : content.getContent();
    }

    /** 重命名。只改展示名，扩展名与对象存储里的键都不动，避免改名把文件类型改了。 */
    @Transactional
    public KnowledgeFileView rename(Long userId, Long fileId, String rawName) {
        KnowledgeFile file = requireFile(userId, fileId);
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件名不能为空");
        }
        if (name.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件名最长 255 个字符");
        }
        file.setDisplayName(name);
        fileMapper.updateById(file);
        return KnowledgeFileView.of(file, tagService.tagsOfFile(userId, fileId));
    }

    @Transactional
    public KnowledgeFileView move(Long userId, Long fileId, Long targetFolderId) {
        KnowledgeFile file = requireFile(userId, fileId);
        requireUploadableFolder(userId, targetFolderId);
        file.setFolderId(targetFolderId);
        fileMapper.updateById(file);
        return KnowledgeFileView.of(file, tagService.tagsOfFile(userId, fileId));
    }

    /**
     * 删除文件及其全部派生数据：章节、结构图、正文、标签关联，最后删对象存储里的原文件。
     *
     * <p>对象删除挂在事务提交之后：先删对象再回滚事务的话，库里会留下一条指向已消失文件的记录，
     * 那比留个孤儿对象糟糕得多。没有事务在跑时（例如被非事务方法直接调用）就地删除。</p>
     */
    @Transactional
    public void delete(Long userId, Long fileId) {
        KnowledgeFile file = requireFile(userId, fileId);
        chapterMapper.delete(new LambdaQueryWrapper<KnowledgeChapter>()
                .eq(KnowledgeChapter::getUserId, userId)
                .eq(KnowledgeChapter::getFileId, fileId));
        structureService.deleteByFile(userId, fileId);
        contentMapper.deleteById(fileId);
        tagService.deleteLinksOfFile(userId, fileId);
        fileMapper.deleteById(fileId);
        deleteObjectAfterCommit(file.getStorageUrl());
    }

    /**
     * 重新识别章节与结构图。用库里存着的正文重跑，不回源对象存储下载原文件——
     * 正文本身就是抽取结果，章节偏移也是以它为坐标系的。
     */
    public KnowledgeFileView reidentify(Long userId, Long fileId) {
        KnowledgeFile file = requireFile(userId, fileId);
        if (!Boolean.TRUE.equals(file.getTextExtracted())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该文件没有可用正文，无法重新识别；请重新上传可抽取正文的格式");
        }
        KnowledgeFileContent content = contentMapper.selectById(fileId);
        String text = content == null || content.getContent() == null ? "" : content.getContent();
        ingestionService.resetKnowledge(userId, fileId);
        try {
            ingestionService.ingestExtractedText(file, text, null);
        } catch (RuntimeException e) {
            log.warn("[知识库] 重新识别失败，fileId={}: {}", fileId, e.getMessage());
            ingestionService.markFailed(file);
        }
        return KnowledgeFileView.of(file, tagService.tagsOfFile(userId, fileId));
    }

    /**
     * 分片上传在 init 阶段就要用的一套前置校验：目标文件夹、扩展名、大小。
     *
     * <p>放在 init 而不是 complete，是为了让客户端在传完几十兆之后才被拒这件事不会发生。</p>
     *
     * @return 归一化后的文件名，会话按这个名字记录
     */
    String validateUploadTarget(Long userId, Long folderId, String originalName, long sizeBytes) {
        requireUser(userId);
        requireUploadableFolder(userId, folderId);
        String safeName = sanitizeName(originalName);
        validateExtension(extensionOf(safeName));
        validateSize(sizeBytes);
        return safeName;
    }

    /**
     * 文件只能落在真实存在的非根文件夹里。这里直接查 mapper 而不是调
     * {@link KnowledgeFolderService#requireUploadableFolder}，是为了不和它形成循环依赖。
     */
    private KnowledgeFolder requireUploadableFolder(Long userId, Long folderId) {
        if (folderId == null || folderId <= KnowledgeFolder.ROOT_PARENT_ID) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "根目录不能直接上传文件，请先选择一个文件夹");
        }
        KnowledgeFolder folder = folderMapper.selectOne(new LambdaQueryWrapper<KnowledgeFolder>()
                .eq(KnowledgeFolder::getId, folderId)
                .eq(KnowledgeFolder::getUserId, userId));
        if (folder == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件夹不存在");
        }
        return folder;
    }

    /** 子树文件夹 id（含自身），靠物化路径前缀一次查出来。 */
    private List<Long> subTreeFolderIds(Long userId, Long folderId) {
        KnowledgeFolder folder = folderMapper.selectOne(new LambdaQueryWrapper<KnowledgeFolder>()
                .eq(KnowledgeFolder::getId, folderId)
                .eq(KnowledgeFolder::getUserId, userId));
        if (folder == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件夹不存在");
        }
        return folderMapper.selectList(new LambdaQueryWrapper<KnowledgeFolder>()
                        .select(KnowledgeFolder::getId)
                        .eq(KnowledgeFolder::getUserId, userId)
                        .likeRight(KnowledgeFolder::getPath, folder.getPath()))
                .stream().map(KnowledgeFolder::getId).toList();
    }

    private KnowledgeFile requireFile(Long userId, Long fileId) {
        requireUser(userId);
        KnowledgeFile file = fileId == null ? null : fileMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeFile>()
                        .eq(KnowledgeFile::getId, fileId)
                        .eq(KnowledgeFile::getUserId, userId));
        if (file == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在");
        }
        return file;
    }

    /** 去掉路径分隔符，防止客户端用 {@code ../} 之类的名字影响后续存储键与展示。 */
    private String sanitizeName(String originalName) {
        String name = originalName == null ? "" : originalName.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("\\s+", " ").trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件名不能为空");
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1
                ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private void validateExtension(String extension) {
        List<String> allowed = properties.getAllowedExtensions();
        if (allowed == null || allowed.isEmpty()) {
            return;
        }
        if (extension.isEmpty() || !allowed.contains(extension)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "不支持的文件类型，允许的扩展名：" + String.join("、", allowed));
        }
    }

    private void validateSize(long sizeBytes) {
        if (sizeBytes <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件内容为空");
        }
        if (sizeBytes > properties.getMaxFileBytes()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "文件超过大小上限 " + (properties.getMaxFileBytes() / 1024 / 1024) + "MB");
        }
    }

    private void deleteObjectAfterCommit(String storageUrl) {
        if (storageUrl == null || storageUrl.isBlank()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            ossService.deleteByUrlQuietly(storageUrl);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                ossService.deleteByUrlQuietly(storageUrl);
            }
        });
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
    }
}

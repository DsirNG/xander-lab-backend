package com.xander.lab.knowledgebase.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xander.lab.knowledgebase.api.KnowledgeFolderView;
import com.xander.lab.knowledgebase.domain.KnowledgeFile;
import com.xander.lab.knowledgebase.domain.KnowledgeFolder;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeBaseProperties;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeFileMapper;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeFolderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件夹树的增删改查。
 *
 * <p>树用物化路径（{@code /3/17/}）维护：子树查询是一个前缀匹配，移动时的环检测是一次
 * 前缀判断，都不需要递归 SQL。代价是移动子树要重写后代的 path 与 depth，本服务负责这件事。</p>
 *
 * <p>根目录不是记录而是 {@code parentId == 0} 这个约定，因此根目录下只能建文件夹、不能放文件。</p>
 */
@Service
@RequiredArgsConstructor
public class KnowledgeFolderService {

    private final KnowledgeFolderMapper folderMapper;
    private final KnowledgeFileMapper fileMapper;
    private final KnowledgeFileService fileService;
    private final KnowledgeBaseProperties properties;

    /** 列出该用户的全部文件夹（扁平，按 path 排序即为先序遍历），附带每个文件夹的直属文件数。 */
    @Transactional(readOnly = true)
    public List<KnowledgeFolderView> list(Long userId) {
        requireUser(userId);
        List<KnowledgeFolder> folders = folderMapper.selectList(
                new LambdaQueryWrapper<KnowledgeFolder>()
                        .eq(KnowledgeFolder::getUserId, userId)
                        .orderByAsc(KnowledgeFolder::getPath));
        Map<Long, Integer> fileCounts = directFileCounts(userId);
        return folders.stream()
                .map(folder -> KnowledgeFolderView.of(folder, fileCounts.getOrDefault(folder.getId(), 0)))
                .toList();
    }

    @Transactional
    public KnowledgeFolderView create(Long userId, Long parentId, String rawName) {
        requireUser(userId);
        String name = normalizeName(rawName);
        long parent = parentId == null ? KnowledgeFolder.ROOT_PARENT_ID : parentId;

        int depth = 1;
        String parentPath = "/";
        if (parent != KnowledgeFolder.ROOT_PARENT_ID) {
            KnowledgeFolder parentFolder = requireFolder(userId, parent);
            depth = parentFolder.getDepth() + 1;
            parentPath = parentFolder.getPath();
        }
        if (depth > properties.getMaxFolderDepth()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "文件夹层级最多 " + properties.getMaxFolderDepth() + " 层");
        }
        requireNameAvailable(userId, parent, name, null);
        long total = folderMapper.selectCount(new LambdaQueryWrapper<KnowledgeFolder>()
                .eq(KnowledgeFolder::getUserId, userId));
        if (total >= properties.getMaxFolders()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "文件夹数量已达上限 " + properties.getMaxFolders());
        }

        KnowledgeFolder folder = new KnowledgeFolder();
        folder.setUserId(userId);
        folder.setParentId(parent);
        folder.setName(name);
        folder.setDepth(depth);
        // path 要以自增主键结尾，只能先插入拿到 id 再回填。
        folder.setPath("/");
        folderMapper.insert(folder);
        folder.setPath(parentPath + folder.getId() + "/");
        folderMapper.updateById(folder);
        return KnowledgeFolderView.of(folder, 0);
    }

    @Transactional
    public KnowledgeFolderView rename(Long userId, Long folderId, String rawName) {
        requireUser(userId);
        String name = normalizeName(rawName);
        KnowledgeFolder folder = requireFolder(userId, folderId);
        if (name.equals(folder.getName())) {
            return KnowledgeFolderView.of(folder, directFileCount(userId, folderId));
        }
        requireNameAvailable(userId, folder.getParentId(), name, folderId);
        folder.setName(name);
        folderMapper.updateById(folder);
        return KnowledgeFolderView.of(folder, directFileCount(userId, folderId));
    }

    /** 移动文件夹。会重写整棵子树的 path 与 depth，因此顺带校验移动后不会超出最大层级。 */
    @Transactional
    public KnowledgeFolderView move(Long userId, Long folderId, Long newParentId) {
        requireUser(userId);
        KnowledgeFolder folder = requireFolder(userId, folderId);
        long target = newParentId == null ? KnowledgeFolder.ROOT_PARENT_ID : newParentId;
        if (target == folder.getParentId()) {
            return KnowledgeFolderView.of(folder, directFileCount(userId, folderId));
        }
        if (target == folder.getId()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能把文件夹移动到自己下面");
        }

        int newDepth = 1;
        String newParentPath = "/";
        if (target != KnowledgeFolder.ROOT_PARENT_ID) {
            KnowledgeFolder parentFolder = requireFolder(userId, target);
            // 目标在自己的子树里就会形成环：物化路径让这件事只需一次前缀判断。
            if (parentFolder.getPath().startsWith(folder.getPath())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能把文件夹移动到它自己的子目录下");
            }
            newDepth = parentFolder.getDepth() + 1;
            newParentPath = parentFolder.getPath();
        }
        requireNameAvailable(userId, target, folder.getName(), folderId);

        List<KnowledgeFolder> subTree = folderMapper.selectList(new LambdaQueryWrapper<KnowledgeFolder>()
                .eq(KnowledgeFolder::getUserId, userId)
                .likeRight(KnowledgeFolder::getPath, folder.getPath()));
        int depthDelta = newDepth - folder.getDepth();
        int deepest = subTree.stream().mapToInt(KnowledgeFolder::getDepth).max().orElse(folder.getDepth());
        if (deepest + depthDelta > properties.getMaxFolderDepth()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "移动后层级会超过上限 " + properties.getMaxFolderDepth() + " 层");
        }

        String oldPrefix = folder.getPath();
        String newPrefix = newParentPath + folder.getId() + "/";
        for (KnowledgeFolder node : subTree) {
            node.setPath(newPrefix + node.getPath().substring(oldPrefix.length()));
            node.setDepth(node.getDepth() + depthDelta);
            if (node.getId().equals(folder.getId())) {
                node.setParentId(target);
            }
            folderMapper.updateById(node);
        }
        folder.setParentId(target);
        folder.setPath(newPrefix);
        folder.setDepth(newDepth);
        return KnowledgeFolderView.of(folder, directFileCount(userId, folderId));
    }

    /**
     * 删除文件夹。
     *
     * @param recursive false 时只删空文件夹，非空直接拒绝；true 时连子文件夹与其中的文件一并删除。
     *                  默认不递归：一次误点不该把一整棵子树连同文件删掉。
     */
    @Transactional
    public int delete(Long userId, Long folderId, boolean recursive) {
        requireUser(userId);
        KnowledgeFolder folder = requireFolder(userId, folderId);
        List<KnowledgeFolder> subTree = folderMapper.selectList(new LambdaQueryWrapper<KnowledgeFolder>()
                .eq(KnowledgeFolder::getUserId, userId)
                .likeRight(KnowledgeFolder::getPath, folder.getPath()));
        List<Long> folderIds = subTree.stream().map(KnowledgeFolder::getId).toList();
        long fileCount = fileMapper.selectCount(new LambdaQueryWrapper<KnowledgeFile>()
                .eq(KnowledgeFile::getUserId, userId)
                .in(KnowledgeFile::getFolderId, folderIds));

        if (!recursive && (subTree.size() > 1 || fileCount > 0)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "文件夹不为空：包含 " + (subTree.size() - 1) + " 个子文件夹、" + fileCount + " 个文件");
        }
        int deletedFiles = 0;
        if (fileCount > 0) {
            List<KnowledgeFile> files = fileMapper.selectList(new LambdaQueryWrapper<KnowledgeFile>()
                    .eq(KnowledgeFile::getUserId, userId)
                    .in(KnowledgeFile::getFolderId, folderIds));
            for (KnowledgeFile file : files) {
                fileService.delete(userId, file.getId());
                deletedFiles++;
            }
        }
        folderMapper.deleteBatchIds(folderIds);
        return deletedFiles;
    }

    /** 文件只能落在真实存在的非根文件夹里，上传与移动前都要过这一关。 */
    @Transactional(readOnly = true)
    public KnowledgeFolder requireUploadableFolder(Long userId, Long folderId) {
        if (folderId == null || folderId <= KnowledgeFolder.ROOT_PARENT_ID) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "根目录不能直接上传文件，请先选择一个文件夹");
        }
        return requireFolder(userId, folderId);
    }

    private KnowledgeFolder requireFolder(Long userId, Long folderId) {
        KnowledgeFolder folder = folderId == null ? null : folderMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeFolder>()
                        .eq(KnowledgeFolder::getId, folderId)
                        .eq(KnowledgeFolder::getUserId, userId));
        if (folder == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件夹不存在");
        }
        return folder;
    }

    private void requireNameAvailable(Long userId, Long parentId, String name, Long excludeId) {
        LambdaQueryWrapper<KnowledgeFolder> wrapper = new LambdaQueryWrapper<KnowledgeFolder>()
                .eq(KnowledgeFolder::getUserId, userId)
                .eq(KnowledgeFolder::getParentId, parentId)
                .eq(KnowledgeFolder::getName, name);
        if (excludeId != null) {
            wrapper.ne(KnowledgeFolder::getId, excludeId);
        }
        if (folderMapper.selectCount(wrapper) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同级目录下已有同名文件夹");
        }
    }

    private Map<Long, Integer> directFileCounts(Long userId) {
        Map<Long, Integer> counts = new HashMap<>();
        List<KnowledgeFile> files = fileMapper.selectList(new LambdaQueryWrapper<KnowledgeFile>()
                .select(KnowledgeFile::getId, KnowledgeFile::getFolderId)
                .eq(KnowledgeFile::getUserId, userId));
        for (KnowledgeFile file : files) {
            counts.merge(file.getFolderId(), 1, Integer::sum);
        }
        return counts;
    }

    private int directFileCount(Long userId, Long folderId) {
        return Math.toIntExact(fileMapper.selectCount(new LambdaQueryWrapper<KnowledgeFile>()
                .eq(KnowledgeFile::getUserId, userId)
                .eq(KnowledgeFile::getFolderId, folderId)));
    }

    private String normalizeName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件夹名不能为空");
        }
        if (name.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件夹名最长 120 个字符");
        }
        if (name.contains("/") || name.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件夹名不能包含斜杠");
        }
        return name;
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
    }

    /** 供其他服务复用：列出某文件夹子树下的全部文件夹 id（含自身）。 */
    @Transactional(readOnly = true)
    public List<Long> subTreeFolderIds(Long userId, Long folderId) {
        KnowledgeFolder folder = requireFolder(userId, folderId);
        List<Long> ids = new ArrayList<>();
        folderMapper.selectList(new LambdaQueryWrapper<KnowledgeFolder>()
                        .select(KnowledgeFolder::getId)
                        .eq(KnowledgeFolder::getUserId, userId)
                        .likeRight(KnowledgeFolder::getPath, folder.getPath()))
                .forEach(node -> ids.add(node.getId()));
        return ids;
    }
}

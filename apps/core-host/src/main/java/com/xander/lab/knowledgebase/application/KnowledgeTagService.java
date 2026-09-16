package com.xander.lab.knowledgebase.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xander.lab.knowledgebase.api.KnowledgeTagView;
import com.xander.lab.knowledgebase.domain.KnowledgeFile;
import com.xander.lab.knowledgebase.domain.KnowledgeFileTag;
import com.xander.lab.knowledgebase.domain.KnowledgeTag;
import com.xander.lab.knowledgebase.domain.TagSource;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeBaseProperties;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeFileMapper;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeFileTagMapper;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeTagMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 标签词表的增删改查，以及文件与标签的关联维护。
 *
 * <p>手动标签与自动标签共存于同一张关联表，靠 {@code source} 区分：重新识别章节时只整体替换
 * {@code AUTO} 那一批，用户手工打的标签不会被覆盖掉。</p>
 */
@Service
@RequiredArgsConstructor
public class KnowledgeTagService {

    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private final KnowledgeTagMapper tagMapper;
    private final KnowledgeFileTagMapper fileTagMapper;
    private final KnowledgeFileMapper fileMapper;
    private final KnowledgeBaseProperties properties;

    @Transactional(readOnly = true)
    public List<KnowledgeTagView> list(Long userId) {
        requireUser(userId);
        List<KnowledgeTag> tags = tagMapper.selectList(new LambdaQueryWrapper<KnowledgeTag>()
                .eq(KnowledgeTag::getUserId, userId)
                .orderByAsc(KnowledgeTag::getName));
        Map<Long, Integer> usage = new HashMap<>();
        fileTagMapper.selectList(new LambdaQueryWrapper<KnowledgeFileTag>()
                        .select(KnowledgeFileTag::getId, KnowledgeFileTag::getTagId)
                        .eq(KnowledgeFileTag::getUserId, userId))
                .forEach(link -> usage.merge(link.getTagId(), 1, Integer::sum));
        return tags.stream().map(tag -> KnowledgeTagView.of(tag, usage.getOrDefault(tag.getId(), 0))).toList();
    }

    @Transactional
    public KnowledgeTagView create(Long userId, String rawName, String color, String description) {
        requireUser(userId);
        String name = normalizeName(rawName);
        validateColor(color);
        if (findByName(userId, name) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "标签已存在：" + name);
        }
        if (tagMapper.selectCount(new LambdaQueryWrapper<KnowledgeTag>()
                .eq(KnowledgeTag::getUserId, userId)) >= properties.getMaxTags()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标签数量已达上限 " + properties.getMaxTags());
        }
        KnowledgeTag tag = new KnowledgeTag();
        tag.setUserId(userId);
        tag.setName(name);
        tag.setColor(color);
        tag.setDescription(clamp(description, 200));
        tagMapper.insert(tag);
        return KnowledgeTagView.of(tag, 0);
    }

    @Transactional
    public KnowledgeTagView update(Long userId, Long tagId, String rawName, String color, String description) {
        requireUser(userId);
        KnowledgeTag tag = requireTag(userId, tagId);
        if (rawName != null) {
            String name = normalizeName(rawName);
            KnowledgeTag existing = findByName(userId, name);
            if (existing != null && !existing.getId().equals(tagId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "标签已存在：" + name);
            }
            tag.setName(name);
        }
        if (color != null) {
            validateColor(color);
            tag.setColor(color.isBlank() ? null : color);
        }
        if (description != null) {
            tag.setDescription(clamp(description, 200));
        }
        tagMapper.updateById(tag);
        return KnowledgeTagView.of(tag, usageCount(userId, tagId));
    }

    /** 删除标签会连带解除它在所有文件上的关联；文件本身不受影响。 */
    @Transactional
    public void delete(Long userId, Long tagId) {
        requireUser(userId);
        requireTag(userId, tagId);
        fileTagMapper.delete(new LambdaQueryWrapper<KnowledgeFileTag>()
                .eq(KnowledgeFileTag::getUserId, userId)
                .eq(KnowledgeFileTag::getTagId, tagId));
        tagMapper.deleteById(tagId);
    }

    /** 给文件加手动标签。标签名不存在就顺手建出来，前端不必先建标签再打标。 */
    @Transactional
    public List<KnowledgeTagView> attachByNames(Long userId, Long fileId, List<String> names) {
        requireUser(userId);
        requireFile(userId, fileId);
        for (String rawName : names == null ? List.<String>of() : names) {
            String name = rawName == null ? "" : rawName.trim();
            if (name.isEmpty()) {
                continue;
            }
            KnowledgeTag tag = ensureTag(userId, normalizeName(name));
            link(userId, fileId, tag.getId(), TagSource.MANUAL);
        }
        return tagsOfFile(userId, fileId);
    }

    @Transactional
    public List<KnowledgeTagView> detach(Long userId, Long fileId, Long tagId) {
        requireUser(userId);
        requireFile(userId, fileId);
        fileTagMapper.delete(new LambdaQueryWrapper<KnowledgeFileTag>()
                .eq(KnowledgeFileTag::getUserId, userId)
                .eq(KnowledgeFileTag::getFileId, fileId)
                .eq(KnowledgeFileTag::getTagId, tagId));
        return tagsOfFile(userId, fileId);
    }

    /** 整体替换某文件的手动标签，自动标签保持不动。 */
    @Transactional
    public List<KnowledgeTagView> replaceManual(Long userId, Long fileId, List<String> names) {
        requireUser(userId);
        requireFile(userId, fileId);
        fileTagMapper.delete(new LambdaQueryWrapper<KnowledgeFileTag>()
                .eq(KnowledgeFileTag::getUserId, userId)
                .eq(KnowledgeFileTag::getFileId, fileId)
                .eq(KnowledgeFileTag::getSource, TagSource.MANUAL.name()));
        return attachByNames(userId, fileId, names);
    }

    /**
     * 上传/重新识别时写入自动标签：整体替换该文件上的 {@code AUTO} 关联。
     * 若某个标签名已由用户手动打过，跳过而不改它的来源，避免下次重算把手动标签抹掉。
     */
    @Transactional
    public int applyAutoTags(Long userId, Long fileId, List<String> names) {
        fileTagMapper.delete(new LambdaQueryWrapper<KnowledgeFileTag>()
                .eq(KnowledgeFileTag::getUserId, userId)
                .eq(KnowledgeFileTag::getFileId, fileId)
                .eq(KnowledgeFileTag::getSource, TagSource.AUTO.name()));
        int applied = 0;
        for (String rawName : new LinkedHashSet<>(names == null ? List.<String>of() : names)) {
            String name = rawName == null ? "" : rawName.trim();
            if (name.isEmpty() || name.length() > 60) {
                continue;
            }
            KnowledgeTag tag = ensureTagQuietly(userId, name);
            if (tag == null) {
                continue;
            }
            if (link(userId, fileId, tag.getId(), TagSource.AUTO)) {
                applied++;
            }
        }
        return applied;
    }

    @Transactional(readOnly = true)
    public List<KnowledgeTagView> tagsOfFile(Long userId, Long fileId) {
        List<KnowledgeFileTag> links = fileTagMapper.selectList(new LambdaQueryWrapper<KnowledgeFileTag>()
                .eq(KnowledgeFileTag::getUserId, userId)
                .eq(KnowledgeFileTag::getFileId, fileId));
        if (links.isEmpty()) {
            return List.of();
        }
        List<Long> tagIds = links.stream().map(KnowledgeFileTag::getTagId).distinct().toList();
        Map<Long, KnowledgeTag> tags = new HashMap<>();
        tagMapper.selectBatchIds(tagIds).forEach(tag -> tags.put(tag.getId(), tag));
        List<KnowledgeTagView> views = new ArrayList<>();
        for (KnowledgeFileTag link : links) {
            KnowledgeTag tag = tags.get(link.getTagId());
            if (tag != null) {
                views.add(KnowledgeTagView.of(tag, link.getSource()));
            }
        }
        views.sort((left, right) -> left.name().compareTo(right.name()));
        return views;
    }

    /** 批量取多个文件的标签，供列表接口一次查完，避免 N+1。 */
    @Transactional(readOnly = true)
    public Map<Long, List<KnowledgeTagView>> tagsOfFiles(Long userId, List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return Map.of();
        }
        List<KnowledgeFileTag> links = fileTagMapper.selectList(new LambdaQueryWrapper<KnowledgeFileTag>()
                .eq(KnowledgeFileTag::getUserId, userId)
                .in(KnowledgeFileTag::getFileId, fileIds));
        if (links.isEmpty()) {
            return Map.of();
        }
        Map<Long, KnowledgeTag> tags = new HashMap<>();
        tagMapper.selectBatchIds(links.stream().map(KnowledgeFileTag::getTagId).distinct().toList())
                .forEach(tag -> tags.put(tag.getId(), tag));
        Map<Long, List<KnowledgeTagView>> result = new HashMap<>();
        for (KnowledgeFileTag link : links) {
            KnowledgeTag tag = tags.get(link.getTagId());
            if (tag != null) {
                result.computeIfAbsent(link.getFileId(), key -> new ArrayList<>())
                        .add(KnowledgeTagView.of(tag, link.getSource()));
            }
        }
        result.values().forEach(list -> list.sort((left, right) -> left.name().compareTo(right.name())));
        return result;
    }

    /** 按标签筛选文件时用：返回带有全部指定标签的文件 id。 */
    @Transactional(readOnly = true)
    public List<Long> fileIdsByTags(Long userId, List<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return List.of();
        }
        List<KnowledgeFileTag> links = fileTagMapper.selectList(new LambdaQueryWrapper<KnowledgeFileTag>()
                .eq(KnowledgeFileTag::getUserId, userId)
                .in(KnowledgeFileTag::getTagId, tagIds));
        Map<Long, Integer> hits = new HashMap<>();
        for (KnowledgeFileTag link : links) {
            hits.merge(link.getFileId(), 1, Integer::sum);
        }
        int required = (int) tagIds.stream().distinct().count();
        return hits.entrySet().stream()
                .filter(entry -> entry.getValue() >= required)
                .map(Map.Entry::getKey)
                .toList();
    }

    @Transactional
    public void deleteLinksOfFile(Long userId, Long fileId) {
        fileTagMapper.delete(new LambdaQueryWrapper<KnowledgeFileTag>()
                .eq(KnowledgeFileTag::getUserId, userId)
                .eq(KnowledgeFileTag::getFileId, fileId));
    }

    private boolean link(Long userId, Long fileId, Long tagId, TagSource source) {
        long existing = fileTagMapper.selectCount(new LambdaQueryWrapper<KnowledgeFileTag>()
                .eq(KnowledgeFileTag::getFileId, fileId)
                .eq(KnowledgeFileTag::getTagId, tagId));
        if (existing > 0) {
            return false;
        }
        KnowledgeFileTag link = new KnowledgeFileTag();
        link.setUserId(userId);
        link.setFileId(fileId);
        link.setTagId(tagId);
        link.setSource(source.name());
        fileTagMapper.insert(link);
        return true;
    }

    private KnowledgeTag ensureTag(Long userId, String name) {
        KnowledgeTag existing = findByName(userId, name);
        if (existing != null) {
            return existing;
        }
        if (tagMapper.selectCount(new LambdaQueryWrapper<KnowledgeTag>()
                .eq(KnowledgeTag::getUserId, userId)) >= properties.getMaxTags()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标签数量已达上限 " + properties.getMaxTags());
        }
        KnowledgeTag tag = new KnowledgeTag();
        tag.setUserId(userId);
        tag.setName(name);
        tagMapper.insert(tag);
        return tag;
    }

    /** 自动标签不该因为撞上数量上限而让整次上传失败，所以这里返回 null 而不是抛异常。 */
    private KnowledgeTag ensureTagQuietly(Long userId, String name) {
        KnowledgeTag existing = findByName(userId, name);
        if (existing != null) {
            return existing;
        }
        if (tagMapper.selectCount(new LambdaQueryWrapper<KnowledgeTag>()
                .eq(KnowledgeTag::getUserId, userId)) >= properties.getMaxTags()) {
            return null;
        }
        KnowledgeTag tag = new KnowledgeTag();
        tag.setUserId(userId);
        tag.setName(name);
        tagMapper.insert(tag);
        return tag;
    }

    private KnowledgeTag findByName(Long userId, String name) {
        return tagMapper.selectOne(new LambdaQueryWrapper<KnowledgeTag>()
                .eq(KnowledgeTag::getUserId, userId)
                .eq(KnowledgeTag::getName, name)
                .last("LIMIT 1"));
    }

    private int usageCount(Long userId, Long tagId) {
        return Math.toIntExact(fileTagMapper.selectCount(new LambdaQueryWrapper<KnowledgeFileTag>()
                .eq(KnowledgeFileTag::getUserId, userId)
                .eq(KnowledgeFileTag::getTagId, tagId)));
    }

    private KnowledgeTag requireTag(Long userId, Long tagId) {
        KnowledgeTag tag = tagId == null ? null : tagMapper.selectOne(new LambdaQueryWrapper<KnowledgeTag>()
                .eq(KnowledgeTag::getId, tagId)
                .eq(KnowledgeTag::getUserId, userId));
        if (tag == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "标签不存在");
        }
        return tag;
    }

    private void requireFile(Long userId, Long fileId) {
        long count = fileId == null ? 0 : fileMapper.selectCount(new LambdaQueryWrapper<KnowledgeFile>()
                .eq(KnowledgeFile::getId, fileId)
                .eq(KnowledgeFile::getUserId, userId));
        if (count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在");
        }
    }

    private String normalizeName(String rawName) {
        String name = rawName == null ? "" : rawName.replaceAll("\\s+", " ").trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标签名不能为空");
        }
        if (name.length() > 60) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标签名最长 60 个字符");
        }
        return name;
    }

    private void validateColor(String color) {
        if (color != null && !color.isBlank() && !COLOR.matcher(color).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "色值格式应为 #RRGGBB");
        }
    }

    private String clamp(String text, int limit) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > limit ? trimmed.substring(0, limit) : trimmed;
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
    }
}

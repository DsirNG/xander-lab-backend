package com.xander.lab.knowledgebase.api;

import com.xander.lab.knowledgebase.domain.KnowledgeTag;
import com.xander.lab.knowledgebase.domain.TagSource;

import java.time.LocalDateTime;

/**
 * 一个标签。同一个记录服务两种场景，因此有两个字段只在其中一种场景下有值：
 * 词表列表关心「这个标签被多少文件用了」（{@code usageCount}），
 * 文件详情关心「这个标签是手动打的还是自动生成的」（{@code source}）。
 * 用不到的那个返回 null，而不是塞一个会被误读的 0 或 MANUAL。
 */
public record KnowledgeTagView(
        Long id,
        String name,
        String color,
        String description,
        /** 词表场景：被多少个文件使用；文件详情场景为 null。 */
        Integer usageCount,
        /** 文件详情场景：MANUAL/AUTO；词表场景为 null。 */
        String source,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** 词表列表用：带使用次数。 */
    public static KnowledgeTagView of(KnowledgeTag tag, int usageCount) {
        return new KnowledgeTagView(tag.getId(), tag.getName(), tag.getColor(), tag.getDescription(),
                usageCount, null, tag.getCreatedAt(), tag.getUpdatedAt());
    }

    /** 文件标签用：带来源。来源列脏了也不抛异常，按手动处理（见 {@link TagSource#fromNullable}）。 */
    public static KnowledgeTagView of(KnowledgeTag tag, String source) {
        return new KnowledgeTagView(tag.getId(), tag.getName(), tag.getColor(), tag.getDescription(),
                null, TagSource.fromNullable(source).name(), tag.getCreatedAt(), tag.getUpdatedAt());
    }
}

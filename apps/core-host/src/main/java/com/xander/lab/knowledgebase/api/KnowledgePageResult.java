package com.xander.lab.knowledgebase.api;

import java.util.List;

/**
 * 分页结果的统一形状。
 *
 * @param records 当前页记录
 * @param total   满足条件的总条数
 * @param page    当前页号，从 1 开始
 * @param size    每页条数
 */
public record KnowledgePageResult<T>(
        List<T> records,
        long total,
        long page,
        long size) {

    public static <T> KnowledgePageResult<T> of(List<T> records, long total, long page, long size) {
        return new KnowledgePageResult<>(records == null ? List.of() : records, total, page, size);
    }

    public static <T> KnowledgePageResult<T> empty(long page, long size) {
        return new KnowledgePageResult<>(List.of(), 0L, page, size);
    }
}

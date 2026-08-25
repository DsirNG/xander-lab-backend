package com.xander.lab.recitation.domain;

import java.util.Locale;

/**
 * 知识列表的归档可见范围。
 *
 * <p>归档不是删除，所以查询必须能表达"只看在用的"、"只看归档的"、"都看"三种意图。
 * 默认取 {@link #ACTIVE}：归档的本意就是从日常视野和到期复习里移出去，
 * 如果默认把归档条目一起返回，归档这个动作对用户就没有效果。</p>
 */
public enum ArchiveFilter {
    /** 只看未归档的知识，默认口径。 */
    ACTIVE,
    /** 只看已归档的知识，用于回收视图和恢复操作。 */
    ARCHIVED,
    /** 归档与未归档都看，用于统计口径或全库检索。 */
    ALL;

    /** 解析外部传入的值；空或不认识的值一律回落到 {@link #ACTIVE}，而不是抛错打断列表查询。 */
    public static ArchiveFilter fromNullable(String value) {
        if (value == null || value.isBlank()) return ACTIVE;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            return ACTIVE;
        }
    }
}

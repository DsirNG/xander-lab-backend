package com.xander.lab.knowledgebase.domain;

/** 文件标签的来源。 */
public enum TagSource {
    /** 用户手动添加；重算自动标签时不会被删掉。 */
    MANUAL,
    /** 由章节标题自动生成；整批可被重算替换。 */
    AUTO;

    public static TagSource fromNullable(String raw) {
        if (raw == null || raw.isBlank()) {
            return MANUAL;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return MANUAL;
        }
    }
}

package com.xander.lab.recitation.domain;

import java.util.Locale;

/** 个人知识镜像支持的知识类型。 */
public enum KnowledgeType {
    RECITATION,
    CONCEPT,
    MATH;

    public static KnowledgeType fromNullable(String value) {
        if (value == null || value.isBlank()) return RECITATION;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("不支持的知识类型");
        }
    }
}

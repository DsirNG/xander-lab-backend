package com.xander.lab.recitation.domain;

import java.util.Locale;

/** 知识点当前采用的掌握度测试方式。 */
public enum KnowledgeTestMode {
    AUDIO_RECITATION,
    AI_QA,
    PRACTICE;

    public static KnowledgeTestMode fromNullable(String value, KnowledgeType type) {
        if (value == null || value.isBlank()) {
            return switch (type) {
                case RECITATION -> AUDIO_RECITATION;
                case CONCEPT -> AI_QA;
                case MATH -> PRACTICE;
            };
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("不支持的测试方式");
        }
    }
}

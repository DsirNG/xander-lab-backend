package com.xander.lab.publishing.domain;

/**
 * 多平台发布共享的限制常量。外部平台各有要求，这里统一取最严格的值，
 * 保证同一份发布数据对 CSDN 与掘金都合法：掘金摘要硬性要求 50-100 字，
 * 故摘要上限为 100 字；掘金标签至少 1 个且编辑器最多 3 个，故按 3 个封顶。
 */
public final class PublishingLimits {
    /** 摘要最大长度（字符），取两个外部平台中最严格的上限：掘金 100 / CSDN 200。 */
    public static final int MAX_SUMMARY_LENGTH = 100;
    /** 掘金单篇文章最多允许的标签数。 */
    public static final int MAX_JUEJIN_TAGS = 3;

    private PublishingLimits() {
    }
}
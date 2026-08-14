package com.xander.lab.integration.mcp.juejin.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JuejinPublisherTest {

    @Test
    void buildBriefUsesSummaryAndPadsShortSummaries() {
        assertThat(JuejinPublisher.buildBrief("太短", null)).hasSizeGreaterThanOrEqualTo(50);
        String fromContent = JuejinPublisher.buildBrief(null, "## 标题\n\n第一段有意义的正文内容。");
        assertThat(fromContent).startsWith("第一段有意义的正文内容。");
        assertThat(fromContent.length()).isBetween(50, 100);
        assertThat(JuejinPublisher.buildBrief("x".repeat(200), null)).hasSize(100);
    }

    @Test
    void buildBriefGuaranteesAtLeastFiftyChars() {
        String brief = JuejinPublisher.buildBrief("too short", null);
        assertThat(brief.length()).isBetween(50, 100);
        assertThat(brief).startsWith("too short");
    }

    @Test
    void buildBriefTruncatesSummariesOverOneHundredChars() {
        String brief = JuejinPublisher.buildBrief("长摘要".repeat(40), null);
        assertThat(brief).hasSize(100);
    }

    @Test
    void firstParagraphSkipsMarkdownPrefixes() {
        assertThat(JuejinPublisher.firstParagraph("""
                # 标题
                ## 小节
                > 引用
                3. 列表项
                这是第一段正文，足够长。
                """))
                .isEqualTo("这是第一段正文，足够长。");
    }

    @Test
    void firstParagraphReturnsNullForBlankContent() {
        assertThat(JuejinPublisher.firstParagraph("  \n  ")).isNull();
        assertThat(JuejinPublisher.firstParagraph(null)).isNull();
    }
}
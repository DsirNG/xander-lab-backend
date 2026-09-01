package com.xander.lab.knowledgebase.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 章节识别的行为约定。这些用例都是纯文本进、章节出，没有 Spring、没有数据库——
 * 章节识别错了整条知识化链路都跟着错，所以它必须是最容易验证的一环。
 */
class ChapterDetectorTest {

    @Test
    void markdownHeadingsAreCompressedSoTheDocumentAlwaysHasTopLevelChapters() {
        String text = """
                ## 第一部分
                正文一
                ### 细节
                正文二
                ## 第二部分
                正文三
                """;

        List<DetectedChapter> chapters = ChapterDetector.detect(text, 100);

        assertThat(chapters).extracting(DetectedChapter::title)
                .containsExactly("第一部分", "细节", "第二部分");
        // 文档只用了 ## 与 ###，压缩后仍要有 1 级章，否则整本书没有顶层结构。
        assertThat(chapters).extracting(DetectedChapter::level).containsExactly(1, 2, 1);
    }

    @Test
    void aParentChapterRangeContainsItsSectionRanges() {
        String text = """
                # 甲
                aaa
                ## 甲一
                bbb
                # 乙
                ccc
                """;

        List<DetectedChapter> chapters = ChapterDetector.detect(text, 100);

        DetectedChapter parent = chapters.get(0);
        DetectedChapter section = chapters.get(1);
        DetectedChapter sibling = chapters.get(2);
        assertThat(parent.endOffset()).isEqualTo(sibling.startOffset());
        assertThat(section.startOffset()).isGreaterThan(parent.startOffset());
        assertThat(section.endOffset()).isLessThanOrEqualTo(parent.endOffset());
    }

    @Test
    void chineseAndEnglishChapterHeadingsAreRecognized() {
        String text = """
                第一章 绪论
                内容
                第二节 背景
                内容
                Chapter 2: Methods
                内容
                """;

        List<DetectedChapter> chapters = ChapterDetector.detect(text, 100);

        assertThat(chapters).extracting(DetectedChapter::title)
                .containsExactly("第一章 绪论", "第二节 背景", "Chapter 2: Methods");
        assertThat(chapters).extracting(DetectedChapter::level).containsExactly(1, 2, 1);
    }

    @Test
    void multiSegmentNumberingTakesItsLevelFromTheSegmentCount() {
        String text = """
                1.1 起因
                aaa
                1.1.2 细节
                bbb
                2.1 结果
                ccc
                """;

        List<DetectedChapter> chapters = ChapterDetector.detect(text, 100);

        assertThat(chapters).extracting(DetectedChapter::level).containsExactly(1, 2, 1);
    }

    @Test
    void commentsInsideCodeFencesAreNotHeadings() {
        String text = """
                # 真标题
                ```bash
                # 这是命令注释
                echo hi
                ```
                正文
                """;

        List<DetectedChapter> chapters = ChapterDetector.detect(text, 100);

        assertThat(chapters).extracting(DetectedChapter::title).containsExactly("真标题");
    }

    @Test
    void listItemsAreNotChaptersOnceRealHeadingsExist() {
        String text = """
                # 购物清单
                1. 买牛奶
                2. 买面包
                3. 买鸡蛋
                # 备注
                记得带袋子
                """;

        List<DetectedChapter> chapters = ChapterDetector.detect(text, 100);

        // 严格规则已经找到 2 个标题，不再启用单段序号降级，否则每个待办都会变成一章。
        assertThat(chapters).extracting(DetectedChapter::title).containsExactly("购物清单", "备注");
    }

    @Test
    void singleNumberedLinesBecomeChaptersOnlyWhenNothingElseWasFound() {
        String text = """
                # 购物清单
                1. 买牛奶
                2. 买面包
                """;

        List<DetectedChapter> chapters = ChapterDetector.detect(text, 100);

        // 一个标题给不出任何结构，此时把序号行当章节比只留一个根节点有用。
        assertThat(chapters).extracting(DetectedChapter::title)
                .containsExactly("购物清单", "1. 买牛奶", "2. 买面包");
    }

    @Test
    void documentsThatOnlyUseNumberingStillSplitIntoChapters() {
        String text = """
                1. 第一件事
                做法若干
                2. 第二件事
                做法若干
                3. 第三件事
                做法若干
                """;

        List<DetectedChapter> chapters = ChapterDetector.detect(text, 100);

        assertThat(chapters).hasSize(3);
        assertThat(chapters).extracting(DetectedChapter::level).containsOnly(1);
    }

    @Test
    void aSentenceStartingWithADecimalIsNotAHeading() {
        String text = """
                3.14 是圆周率。
                1.2 这一节讲什么
                内容
                """;

        List<DetectedChapter> chapters = ChapterDetector.detect(text, 100);

        assertThat(chapters).extracting(DetectedChapter::title).containsExactly("1.2 这一节讲什么");
    }

    @Test
    void setextUnderlinedTitlesAreRecognized() {
        String text = """
                总纲
                ====
                正文
                分论
                ----
                正文
                """;

        List<DetectedChapter> chapters = ChapterDetector.detect(text, 100);

        assertThat(chapters).extracting(DetectedChapter::title).containsExactly("总纲", "分论");
        assertThat(chapters).extracting(DetectedChapter::level).containsExactly(1, 2);
    }

    @Test
    void detectionIsTruncatedAtTheConfiguredCeiling() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            text.append("# 标题").append(i).append("\n正文\n");
        }

        assertThat(ChapterDetector.detect(text.toString(), 5)).hasSize(5);
    }

    @Test
    void emptyTextOrAnInvalidCeilingYieldsNoChapters() {
        assertThat(ChapterDetector.detect(null, 10)).isEmpty();
        assertThat(ChapterDetector.detect("   ", 10)).isEmpty();
        assertThat(ChapterDetector.detect("# 标题", 0)).isEmpty();
    }

    @Test
    void proseWithoutAnyHeadingYieldsNoChapters() {
        assertThat(ChapterDetector.detect("今天天气不错，我去买了牛奶和面包。", 100)).isEmpty();
    }
}

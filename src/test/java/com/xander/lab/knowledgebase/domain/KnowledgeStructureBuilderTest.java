package com.xander.lab.knowledgebase.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识结构图的生成约定。图必须是确定性的：同一份正文重复生成得到同一张图，
 * 否则用户每次预览看到的结构都在变。
 */
class KnowledgeStructureBuilderTest {

    private static final KnowledgeStructureBuilder.Options OPTIONS =
            new KnowledgeStructureBuilder.Options(8, 200);

    @Test
    void chaptersBecomeTheFirstLevelAndSectionsNestUnderThem() {
        String text = """
                # 第一章 甲
                - 要点一
                ## 1.1 甲之一
                - 子要点
                # 第二章 乙
                - 要点二
                """;
        List<KnowledgeStructureBuilder.ChapterRef> chapters = refs(text);

        KnowledgeStructureBuilder.StructureDraft root =
                KnowledgeStructureBuilder.buildForFile("测试文件", text, chapters, OPTIONS);

        assertThat(root.type()).isEqualTo(StructureNodeType.ROOT);
        assertThat(root.title()).isEqualTo("测试文件");
        List<KnowledgeStructureBuilder.StructureDraft> topChapters = root.children().stream()
                .filter(node -> node.type() == StructureNodeType.CHAPTER)
                .toList();
        assertThat(topChapters).extracting(KnowledgeStructureBuilder.StructureDraft::title)
                .containsExactly("第一章 甲", "第二章 乙");
        assertThat(topChapters.get(0).children())
                .anySatisfy(child -> assertThat(child.type()).isEqualTo(StructureNodeType.SECTION));
    }

    @Test
    void aChaptersOwnPointsComeFromItsOwnBodyNotFromItsSections() {
        String text = """
                # 甲
                - 甲自己的要点
                ## 甲一
                - 小节的要点
                """;

        KnowledgeStructureBuilder.StructureDraft root =
                KnowledgeStructureBuilder.buildForFile("f", text, refs(text), OPTIONS);

        KnowledgeStructureBuilder.StructureDraft chapter = root.children().get(0);
        List<String> ownPoints = chapter.children().stream()
                .filter(node -> node.type() == StructureNodeType.POINT)
                .map(KnowledgeStructureBuilder.StructureDraft::title)
                .toList();
        // 小节的要点挂在小节下面，不能同时冒泡到父章，否则一章会重复列出整章内容。
        assertThat(ownPoints).containsExactly("甲自己的要点");
    }

    @Test
    void proseWithoutListsFallsBackToTheFirstSentenceOfEachParagraph() {
        String text = """
                # 甲

                第一段讲了一件事。后面还有别的话。

                第二段讲了另一件事。也还有别的话。
                """;

        KnowledgeStructureBuilder.StructureDraft root =
                KnowledgeStructureBuilder.buildForFile("f", text, refs(text), OPTIONS);

        assertThat(root.children().get(0).children())
                .extracting(KnowledgeStructureBuilder.StructureDraft::title)
                .containsExactly("第一段讲了一件事", "第二段讲了另一件事");
    }

    @Test
    void pointsPerNodeAreCapped() {
        StringBuilder text = new StringBuilder("# 甲\n");
        for (int i = 1; i <= 20; i++) {
            text.append("- 要点").append(i).append('\n');
        }
        KnowledgeStructureBuilder.Options capped = new KnowledgeStructureBuilder.Options(3, 200);

        KnowledgeStructureBuilder.StructureDraft root = KnowledgeStructureBuilder.buildForFile(
                "f", text.toString(), refs(text.toString()), capped);

        assertThat(root.children().get(0).children()).hasSize(3);
    }

    @Test
    void aFileWithoutChaptersStillGetsPointsUnderTheRoot() {
        String text = """
                - 随手记一
                - 随手记二
                """;

        KnowledgeStructureBuilder.StructureDraft root =
                KnowledgeStructureBuilder.buildForFile("散记", text, List.of(), OPTIONS);

        assertThat(root.type()).isEqualTo(StructureNodeType.ROOT);
        assertThat(root.children()).extracting(KnowledgeStructureBuilder.StructureDraft::title)
                .containsExactly("随手记一", "随手记二");
        assertThat(root.children()).extracting(KnowledgeStructureBuilder.StructureDraft::type)
                .containsOnly(StructureNodeType.POINT);
    }

    @Test
    void buildingOneChapterOnlyUsesThatChaptersSubtree() {
        String text = """
                # 甲
                - 甲要点
                ## 甲一
                - 甲一要点
                # 乙
                - 乙要点
                """;
        List<KnowledgeStructureBuilder.ChapterRef> all = refs(text);
        KnowledgeStructureBuilder.ChapterRef jia = all.get(0);
        List<KnowledgeStructureBuilder.ChapterRef> descendants = List.of(all.get(1));

        KnowledgeStructureBuilder.StructureDraft root =
                KnowledgeStructureBuilder.buildForChapter(jia, descendants, text, OPTIONS);

        assertThat(root.type()).isEqualTo(StructureNodeType.ROOT);
        assertThat(root.title()).isEqualTo("甲");
        assertThat(flatTitles(root)).contains("甲一要点").doesNotContain("乙要点");    }

    @Test
    void theSameInputAlwaysProducesTheSameGraph() {
        String text = """
                # 甲
                - 一
                ## 甲一
                - 二
                """;

        KnowledgeStructureBuilder.StructureDraft first =
                KnowledgeStructureBuilder.buildForFile("f", text, refs(text), OPTIONS);
        KnowledgeStructureBuilder.StructureDraft second =
                KnowledgeStructureBuilder.buildForFile("f", text, refs(text), OPTIONS);

        // record 的 equals 是深比较，图完全一致才通得过。
        assertThat(first).isEqualTo(second);
    }

    @Test
    void aBlankFileTitleFallsBackInsteadOfWritingAnEmptyNode() {
        KnowledgeStructureBuilder.StructureDraft root =
                KnowledgeStructureBuilder.buildForFile("   ", "正文若干。", List.of(), OPTIONS);

        assertThat(root.title()).isEqualTo("未命名");
    }

    private static List<KnowledgeStructureBuilder.ChapterRef> refs(String text) {
        List<DetectedChapter> detected = ChapterDetector.detect(text, 100);
        return detected.stream()
                .map(chapter -> new KnowledgeStructureBuilder.ChapterRef(
                        (long) detected.indexOf(chapter) + 1, chapter.level(), chapter.title(),
                        chapter.startOffset(), chapter.endOffset()))
                .toList();
    }

    private static List<String> flatTitles(KnowledgeStructureBuilder.StructureDraft root) {
        List<String> titles = new ArrayList<>();
        collect(root, titles);
        return titles;
    }

    private static void collect(KnowledgeStructureBuilder.StructureDraft node, List<String> titles) {
        titles.add(node.title());
        node.children().forEach(child -> collect(child, titles));
    }
}

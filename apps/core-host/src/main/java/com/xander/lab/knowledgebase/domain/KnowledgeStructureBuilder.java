package com.xander.lab.knowledgebase.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 由章节目录与正文推导知识结构图。纯函数、确定性输出，不调模型：
 * 同一个文件重复生成必须得到同一张图，否则用户每次预览看到的结构都在变。
 *
 * <p>树的形状是「文件（或章）→ 章 → 节 → 知识点」。知识点取该节点<b>自身正文</b>
 * （不含子节）里的要点行：优先 Markdown 列表项，没有列表就退化为各段首句。</p>
 */
public final class KnowledgeStructureBuilder {

    /** 要点行：Markdown 无序列表项，或「1. / 1、」这类有序列表项。 */
    private static final Pattern LIST_ITEM = Pattern.compile(
            "^(?:[-*+•·]|\\d{1,3}[.、)）]|[（(]\\d{1,3}[）)])\\s+(\\S.*)$");
    /** 标题与知识点标题在库里最长 300 字符。 */
    private static final int MAX_TITLE_LENGTH = 300;

    private KnowledgeStructureBuilder() {
    }

    /**
     * 生成参数。
     *
     * @param maxPointsPerNode 单个节点下最多挂几个知识点，防止一章几百个列表项把图撑爆
     * @param summaryLength    摘要截断长度
     */
    public record Options(int maxPointsPerNode, int summaryLength) {
    }

    /** 章节输入：来自库里的 {@link KnowledgeChapter}，或识别后还没落库的结果。 */
    public record ChapterRef(Long id, int level, String title, int startOffset, int endOffset) {
    }

    /** 待落库的结构图节点草稿，树形。 */
    public record StructureDraft(StructureNodeType type, String title, String summary, Long chapterId,
                                 List<StructureDraft> children) {
    }

    /** 整本书维度：根节点是文件本身。章节为空时退化成「文件 + 若干知识点」，散记也有结构可看。 */
    public static StructureDraft buildForFile(String fileTitle, String text, List<ChapterRef> chapters,
                                             Options options) {
        String body = text == null ? "" : text;
        if (chapters == null || chapters.isEmpty()) {
            return new StructureDraft(StructureNodeType.ROOT, safeTitle(fileTitle),
                    summarize(body, 0, body.length(), options), null,
                    extractPoints(body, 0, body.length(), null, options));
        }
        int topLevel = chapters.stream().mapToInt(ChapterRef::level).min().orElse(1);
        List<StructureDraft> children = buildRange(chapters, 0, chapters.size(), topLevel, body, options);
        return new StructureDraft(StructureNodeType.ROOT, safeTitle(fileTitle),
                summarize(body, 0, body.length(), options), null, children);
    }

    /**
     * 单章维度：根节点是这一章，子树只用这一章的后代。大部头文件靠它按需出图，
     * 一次只处理用户正在看的那一章。
     */
    public static StructureDraft buildForChapter(ChapterRef chapter, List<ChapterRef> descendants, String text,
                                                 Options options) {
        String body = text == null ? "" : text;
        List<ChapterRef> children = descendants == null ? List.of() : descendants;
        int childLevel = children.stream().mapToInt(ChapterRef::level).min().orElse(chapter.level() + 1);
        List<StructureDraft> nodes = new ArrayList<>(
                buildRange(children, 0, children.size(), childLevel, body, options));
        int ownBodyEnd = children.isEmpty() ? chapter.endOffset() : children.get(0).startOffset();
        nodes.addAll(extractPoints(body, chapter.startOffset(), ownBodyEnd, chapter.title(), options));
        return new StructureDraft(StructureNodeType.ROOT, safeTitle(chapter.title()),
                summarize(body, chapter.startOffset(), chapter.endOffset(), options), chapter.id(), nodes);
    }

    /** 把扁平的「层级 + 顺序」列表还原成树：同层节点的子树延伸到下一个层级不深于自己的兄弟为止。 */
    private static List<StructureDraft> buildRange(List<ChapterRef> chapters, int from, int to, int level,
                                                   String text, Options options) {
        List<StructureDraft> nodes = new ArrayList<>();
        int i = from;
        while (i < to) {
            ChapterRef current = chapters.get(i);
            if (current.level() != level) {
                i++;
                continue;
            }
            int subTreeEnd = i + 1;
            while (subTreeEnd < to && chapters.get(subTreeEnd).level() > level) {
                subTreeEnd++;
            }
            List<StructureDraft> children = new ArrayList<>();
            if (subTreeEnd > i + 1) {
                int childLevel = chapters.subList(i + 1, subTreeEnd).stream()
                        .mapToInt(ChapterRef::level).min().orElse(level + 1);
                children.addAll(buildRange(chapters, i + 1, subTreeEnd, childLevel, text, options));
            }
            int ownBodyEnd = subTreeEnd > i + 1 ? chapters.get(i + 1).startOffset() : current.endOffset();
            children.addAll(extractPoints(text, current.startOffset(), ownBodyEnd, current.title(), options));
            nodes.add(new StructureDraft(level == 1 ? StructureNodeType.CHAPTER : StructureNodeType.SECTION,
                    safeTitle(current.title()), summarize(text, current.startOffset(), current.endOffset(), options),
                    current.id(), children));
            i = subTreeEnd;
        }
        return nodes;
    }

    /**
     * 从一段正文里抽知识点。两级策略：有 Markdown 列表就只取列表项（作者已经自己列过要点了，
     * 再掺入段落只会重复）；一个列表项都没有才退化为各段首句。
     *
     * @param skipTitle 该节点自己的标题；标题行本身在区间内，不能又变成自己的知识点
     */
    private static List<StructureDraft> extractPoints(String text, int start, int end, String skipTitle,
                                                      Options options) {
        String slice = slice(text, start, end);
        if (slice.isBlank()) {
            return List.of();
        }
        int limit = Math.max(0, options.maxPointsPerNode());
        if (limit == 0) {
            return List.of();
        }
        String normalizedTitle = skipTitle == null ? null : skipTitle.replaceAll("\\s+", " ").trim();

        List<String> items = new ArrayList<>();
        boolean inFence = false;
        for (String rawLine : slice.split("\n", -1)) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            String trimmed = line.trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence;
                continue;
            }
            if (inFence || trimmed.isEmpty()) {
                continue;
            }
            Matcher item = LIST_ITEM.matcher(trimmed);
            if (item.matches()) {
                String content = item.group(1).trim();
                if (!content.isEmpty() && !content.equals(normalizedTitle)) {
                    items.add(content);
                    if (items.size() >= limit) {
                        break;
                    }
                }
            }
        }
        if (items.isEmpty()) {
            items = firstSentences(slice, normalizedTitle, limit);
        }

        List<StructureDraft> points = new ArrayList<>(items.size());
        for (String item : items) {
            // 标题按 300 截断；只有确实被截断时才另存摘要，否则摘要与标题重复。
            String summary = item.length() > MAX_TITLE_LENGTH
                    ? item.substring(0, Math.min(item.length(), Math.max(1, options.summaryLength())))
                    : null;
            points.add(new StructureDraft(StructureNodeType.POINT, safeTitle(item), summary, null, List.of()));
        }
        return points;
    }

    /** 没有列表的散文：每个自然段贡献首句，句末标点找不到就整段截断。 */
    private static List<String> firstSentences(String slice, String normalizedTitle, int limit) {
        List<String> sentences = new ArrayList<>();
        for (String paragraph : slice.split("(?:\\r?\\n){2,}")) {
            String flat = paragraph.replaceAll("```[\\s\\S]*?```", " ").replaceAll("\\s+", " ").trim();
            if (flat.length() < 4 || flat.equals(normalizedTitle)) {
                continue;
            }
            int cut = -1;
            for (int i = 0; i < flat.length(); i++) {
                if ("。！？!?；;".indexOf(flat.charAt(i)) >= 0) {
                    cut = i;
                    break;
                }
            }
            String sentence = cut >= 4 ? flat.substring(0, cut) : flat;
            if (sentence.length() >= 4 && !sentence.equals(normalizedTitle)) {
                sentences.add(sentence);
                if (sentences.size() >= limit) {
                    break;
                }
            }
        }
        return sentences;
    }

    private static String safeTitle(String raw) {
        String title = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
        if (title.isEmpty()) {
            title = "未命名";
        }
        return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;
    }

    private static String summarize(String text, int start, int end, Options options) {
        String slice = slice(text, start, end);
        if (slice.isBlank()) {
            return null;
        }
        String flat = slice.replaceAll("\\s+", " ").trim();
        int limit = Math.max(1, options.summaryLength());
        return flat.length() > limit ? flat.substring(0, limit) : flat;
    }

    private static String slice(String text, int start, int end) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int from = Math.max(0, Math.min(start, text.length()));
        int to = Math.max(from, Math.min(end, text.length()));
        return text.substring(from, to);
    }
}

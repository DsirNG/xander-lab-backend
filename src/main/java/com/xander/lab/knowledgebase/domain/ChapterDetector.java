package com.xander.lab.knowledgebase.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从纯文本里识别章节目录。纯函数、无外部依赖，因此可以直接单测。
 *
 * <p>识别的是「看起来像标题的整行」，支持四类写法：Markdown 的 {@code #} 标题与 setext 下划线标题、
 * 中文的「第N章/第N节」、英文的 {@code Chapter N / Part N}，以及 {@code 1.2 小标题} 这类多段编号。</p>
 *
 * <p>纯数字单段编号（{@code 1. 买牛奶}）默认<b>不</b>算标题：清单类文档里到处都是这种行，
 * 一律当章节会把目录冲垮。只有当上述四类一个都没匹配到（少于 2 个标题）时才降级启用它，
 * 让「只用序号分段」的文档也能分出章。</p>
 *
 * <p>代码围栏（```）里的内容整段跳过：Markdown 笔记里的示例代码常含 {@code # 注释}。</p>
 */
public final class ChapterDetector {

    /** Markdown ATX 标题，允许结尾的装饰性井号。 */
    private static final Pattern ATX = Pattern.compile("^(#{1,6})\\s+(\\S.*?)\\s*#*$");
    /** 中文章/篇/部：层级 1。 */
    private static final Pattern CN_CHAPTER = Pattern.compile(
            "^第\\s*[0-9０-９一二三四五六七八九十百千零〇]{1,12}\\s*[章篇部编]\\s*[:：、.\\-—\\s]*(.*)$");
    /** 中文节/回/讲/课：层级 2。 */
    private static final Pattern CN_SECTION = Pattern.compile(
            "^第\\s*[0-9０-９一二三四五六七八九十百千零〇]{1,12}\\s*[节節回讲講课課]\\s*[:：、.\\-—\\s]*(.*)$");
    /** 英文 Chapter/Part：层级 1。 */
    private static final Pattern EN_CHAPTER = Pattern.compile(
            "^(?:chapter|part)\\s+([0-9ivxlc]{1,8})\\b\\s*[:.\\-—]?\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    /** 多段编号 {@code 1.2.3 标题}：层级=段数。 */
    private static final Pattern MULTI_NUMBERED = Pattern.compile(
            "^(\\d{1,3}(?:\\.\\d{1,3}){1,4})[.、）)]?\\s+(\\S.{0,120})$");
    /** 单段编号 {@code 1. 标题}：仅在降级模式启用，层级 1。 */
    private static final Pattern SINGLE_NUMBERED = Pattern.compile(
            "^(\\d{1,3})[.、）)]\\s*(\\S.{0,80})$");
    /** 标题在库里最长 300 字符。 */
    private static final int MAX_TITLE_LENGTH = 300;

    private ChapterDetector() {
    }

    /**
     * @param text        抽取出的纯文本正文
     * @param maxChapters 识别上限；超出后截断，避免异常文件把目录撑爆
     * @return 按正文出现顺序排列的章节；识别不出任何标题时返回空列表
     */
    public static List<DetectedChapter> detect(String text, int maxChapters) {
        if (text == null || text.isBlank() || maxChapters <= 0) {
            return List.of();
        }
        List<RawHeading> headings = scan(text, false);
        if (headings.size() < 2) {
            // 四类标题都没成规模，降级允许「1. 标题」这种只靠序号分段的写法。
            List<RawHeading> lenient = scan(text, true);
            if (lenient.size() > headings.size()) {
                headings = lenient;
            }
        }
        if (headings.isEmpty()) {
            return List.of();
        }
        if (headings.size() > maxChapters) {
            headings = new ArrayList<>(headings.subList(0, maxChapters));
        }
        return toChapters(headings, text.length());
    }

    /** 原始层级各不相同的文档（只用 ## 的、只用 1.1 的）都要能分出顶层章，所以把出现过的层级压缩成 1..N。 */
    private static List<DetectedChapter> toChapters(List<RawHeading> headings, int textLength) {
        TreeSet<Integer> distinct = new TreeSet<>();
        headings.forEach(h -> distinct.add(h.rawLevel));
        List<Integer> ranks = new ArrayList<>(distinct);

        int[] levels = new int[headings.size()];
        for (int i = 0; i < headings.size(); i++) {
            levels[i] = ranks.indexOf(headings.get(i).rawLevel) + 1;
        }

        List<DetectedChapter> chapters = new ArrayList<>(headings.size());
        for (int i = 0; i < headings.size(); i++) {
            // 结束位置取「下一个层级不深于自己的标题」，父章区间因此天然包含其子节区间。
            int end = textLength;
            for (int j = i + 1; j < headings.size(); j++) {
                if (levels[j] <= levels[i]) {
                    end = headings.get(j).lineStart;
                    break;
                }
            }
            RawHeading heading = headings.get(i);
            chapters.add(new DetectedChapter(levels[i], heading.title, heading.lineStart,
                    Math.max(end, heading.lineStart)));
        }
        return chapters;
    }

    /**
     * 逐行扫描找标题行。
     *
     * @param lenientNumbering 是否把单段序号行（{@code 1. 标题}）也当标题
     */
    private static List<RawHeading> scan(String text, boolean lenientNumbering) {
        List<RawHeading> headings = new ArrayList<>();
        boolean inFence = false;
        boolean prevWasHeading = false;
        int prevLineStart = -1;
        String prevLine = null;
        int pos = 0;
        int length = text.length();

        while (pos <= length) {
            int newline = text.indexOf('\n', pos);
            int lineEnd = newline < 0 ? length : newline;
            String raw = text.substring(pos, lineEnd);
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            String trimmed = line.trim();
            boolean currentIsHeading = false;

            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence;
            } else if (!inFence) {
                RawHeading heading = matchHeading(trimmed, pos, lenientNumbering);
                if (heading != null) {
                    headings.add(heading);
                    currentIsHeading = true;
                } else {
                    int underlineLevel = setextLevel(trimmed);
                    if (underlineLevel > 0 && !prevWasHeading && isSetextTitle(prevLine)) {
                        headings.add(new RawHeading(underlineLevel, normalizeTitle(prevLine), prevLineStart));
                        currentIsHeading = true;
                    }
                }
            }

            prevWasHeading = currentIsHeading;
            prevLineStart = pos;
            prevLine = trimmed;
            if (newline < 0) {
                break;
            }
            pos = newline + 1;
        }
        return headings;
    }

    private static RawHeading matchHeading(String trimmed, int lineStart, boolean lenientNumbering) {
        if (trimmed.isEmpty()) {
            return null;
        }
        Matcher atx = ATX.matcher(trimmed);
        if (atx.matches()) {
            return new RawHeading(atx.group(1).length(), normalizeTitle(atx.group(2)), lineStart);
        }
        // 中文与英文章节：标题保留整行原文，去掉序号只剩「勾股定理」反而丢了它是第几章。
        if (CN_CHAPTER.matcher(trimmed).matches() || EN_CHAPTER.matcher(trimmed).matches()) {
            return new RawHeading(1, normalizeTitle(trimmed), lineStart);
        }
        if (CN_SECTION.matcher(trimmed).matches()) {
            return new RawHeading(2, normalizeTitle(trimmed), lineStart);
        }
        if (endsLikeSentence(trimmed)) {
            // 「3.14 是圆周率。」这种是正文句子而不是标题，编号类规则到此为止。
            return null;
        }
        Matcher multi = MULTI_NUMBERED.matcher(trimmed);
        if (multi.matches()) {
            int level = multi.group(1).split("\\.").length;
            return new RawHeading(level, normalizeTitle(trimmed), lineStart);
        }
        if (lenientNumbering && SINGLE_NUMBERED.matcher(trimmed).matches()) {
            return new RawHeading(1, normalizeTitle(trimmed), lineStart);
        }
        return null;
    }

    /** {@code ===} 下划线视为 1 级，{@code ---} 视为 2 级。 */
    private static int setextLevel(String trimmed) {
        if (trimmed.length() < 3) {
            return 0;
        }
        if (trimmed.chars().allMatch(c -> c == '=')) {
            return 1;
        }
        return trimmed.chars().allMatch(c -> c == '-') ? 2 : 0;
    }

    /** 下划线上一行要像标题：非空、够短、不是列表项/引用/表格行。 */
    private static boolean isSetextTitle(String previousLine) {
        if (previousLine == null || previousLine.isBlank() || previousLine.length() > 120) {
            return false;
        }
        String head = previousLine.stripLeading();
        return !head.startsWith("#") && !head.startsWith("|") && !head.startsWith(">")
                && !head.startsWith("- ") && !head.startsWith("* ") && !head.startsWith("+ ")
                && setextLevel(previousLine) == 0;
    }

    private static boolean endsLikeSentence(String trimmed) {
        char last = trimmed.charAt(trimmed.length() - 1);
        return "。！？；，,;!?".indexOf(last) >= 0;
    }

    private static String normalizeTitle(String raw) {
        String cleaned = raw.replaceAll("^#+", "").replaceAll("#+$", "")
                .replaceAll("\\s+", " ").trim();
        return cleaned.length() > MAX_TITLE_LENGTH ? cleaned.substring(0, MAX_TITLE_LENGTH) : cleaned;
    }

    private record RawHeading(int rawLevel, String title, int lineStart) {
    }
}

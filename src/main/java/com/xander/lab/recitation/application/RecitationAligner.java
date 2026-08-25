package com.xander.lab.recitation.application;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 以确定性编辑距离对齐原文与转写文本，并生成可解释的错漏统计。 */
@Component
public class RecitationAligner {

    /**
     * 参与对齐的单侧最大码点数。
     *
     * <p>原文侧受创建接口的 10000 字符限制约束，但转写文本来自语音识别、长度不受控，
     * 所以这里必须自己设一道上限，否则一段异常长的录音就能让定时任务线程分配巨量内存。
     * 超出部分直接截断而不是抛错：这种输入本身就是坏录音，截断后仍会得到一个低分。</p>
     */
    private static final int MAX_ALIGN_CODE_POINTS = 12000;

    private static final byte MOVE_MATCH = 0;
    private static final byte MOVE_MISSING = 1;
    private static final byte MOVE_EXTRA = 2;
    private static final byte MOVE_WRONG = 3;

    /** 去除标点和空白并统一英文大小写，保留中文、字母和数字。 */
    public String normalize(String text) {
        if (text == null) return "";
        StringBuilder result = new StringBuilder(text.length());
        text.codePoints()
                .filter(Character::isLetterOrDigit)
                .map(Character::toLowerCase)
                .forEach(result::appendCodePoint);
        return result.toString();
    }

    /** 对齐用户背诵结果；额外内容半权计入准确度扣分。 */
    public AlignmentResult align(String expectedText, String actualText) {
        String expected = normalize(expectedText);
        String actual = normalize(actualText);
        int[] left = limit(expected.codePoints().toArray());
        int[] right = limit(actual.codePoints().toArray());
        int width = right.length + 1;
        // 距离矩阵只保留滚动两行，回溯改为读取每格当初选中的方向（每格 2 bit）。
        // 完整 int 矩阵在 10000×10000 时要 400MB，方向表只要 25MB，且回溯结果与原实现逐格一致。
        byte[] moves = new byte[(int) ((((long) left.length + 1) * width + 3) / 4)];
        int[] previous = new int[width];
        int[] current = new int[width];
        for (int j = 0; j <= right.length; j++) {
            previous[j] = j;
            // 首行只能来自“多读了一个字”，与原回溯在 i==0 时的判定一致。
            setMove(moves, width, 0, j, MOVE_EXTRA);
        }
        for (int i = 1; i <= left.length; i++) {
            current[0] = i;
            setMove(moves, width, i, 0, MOVE_MISSING);
            for (int j = 1; j <= right.length; j++) {
                boolean same = left[i - 1] == right[j - 1];
                int substitution = previous[j - 1] + (same ? 0 : 1);
                int deletion = previous[j] + 1;
                int insertion = current[j - 1] + 1;
                int best = Math.min(substitution, Math.min(deletion, insertion));
                current[j] = best;
                byte move;
                if (same && best == previous[j - 1]) move = MOVE_MATCH;
                else if (best == deletion) move = MOVE_MISSING;
                else if (best == insertion) move = MOVE_EXTRA;
                else move = MOVE_WRONG;
                setMove(moves, width, i, j, move);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }

        List<Difference> reversed = new ArrayList<>();
        int matches = 0;
        int missing = 0;
        int extra = 0;
        int wrong = 0;
        int i = left.length;
        int j = right.length;
        while (i > 0 || j > 0) {
            switch (getMove(moves, width, i, j)) {
                case MOVE_MATCH -> {
                    matches++;
                    i--;
                    j--;
                }
                case MOVE_MISSING -> {
                    missing++;
                    reversed.add(new Difference("MISSING", codePoint(left[i - 1]), "", i - 1));
                    i--;
                }
                case MOVE_EXTRA -> {
                    extra++;
                    reversed.add(new Difference("EXTRA", "", codePoint(right[j - 1]), i));
                    j--;
                }
                default -> {
                    wrong++;
                    reversed.add(new Difference("WRONG", codePoint(left[i - 1]), codePoint(right[j - 1]), i - 1));
                    i--;
                    j--;
                }
            }
        }
        Collections.reverse(reversed);
        List<Difference> differences = groupDifferences(reversed, left);
        double penalty = missing + wrong + extra * 0.5d;
        double rawScore = left.length == 0 ? 0d : Math.max(0d, 100d * (1d - penalty / left.length));
        BigDecimal score = BigDecimal.valueOf(rawScore).setScale(2, RoundingMode.HALF_UP);
        return new AlignmentResult(expected, actual, matches, missing, wrong, extra, score, differences);
    }

    private int[] limit(int[] codePoints) {
        return codePoints.length <= MAX_ALIGN_CODE_POINTS
                ? codePoints
                : java.util.Arrays.copyOf(codePoints, MAX_ALIGN_CODE_POINTS);
    }

    private void setMove(byte[] moves, int width, int i, int j, byte move) {
        long cell = (long) i * width + j;
        int index = (int) (cell >> 2);
        int shift = (int) ((cell & 3L) * 2L);
        moves[index] = (byte) ((moves[index] & ~(0b11 << shift)) | (move << shift));
    }

    private byte getMove(byte[] moves, int width, int i, int j) {
        long cell = (long) i * width + j;
        int shift = (int) ((cell & 3L) * 2L);
        return (byte) ((moves[(int) (cell >> 2)] >> shift) & 0b11);
    }

    /** 将连续的单字编辑合并为片段，并补充原文上下文，避免报告按字逐行展示。 */
    private List<Difference> groupDifferences(List<Difference> source, int[] expected) {
        if (source.isEmpty()) return List.of();
        List<Difference> grouped = new ArrayList<>();
        for (Difference current : source) {
            if (!grouped.isEmpty() && canMerge(grouped.get(grouped.size() - 1), current)) {
                Difference previous = grouped.remove(grouped.size() - 1);
                grouped.add(new Difference(previous.type(), previous.expected() + current.expected(),
                        previous.actual() + current.actual(), previous.expectedIndex(), "", "", false));
            } else {
                grouped.add(current);
            }
        }

        List<Difference> enriched = new ArrayList<>(grouped.size());
        for (Difference difference : grouped) {
            int expectedLength = difference.expected().codePointCount(0, difference.expected().length());
            int endIndex = Math.min(expected.length, difference.expectedIndex() + expectedLength);
            boolean tailOmission = "MISSING".equals(difference.type()) && endIndex == expected.length;
            enriched.add(new Difference(difference.type(), difference.expected(), difference.actual(),
                    difference.expectedIndex(), codePointSlice(expected, Math.max(0, difference.expectedIndex() - 8),
                    difference.expectedIndex()), codePointSlice(expected, endIndex,
                    Math.min(expected.length, endIndex + 8)), tailOmission));
        }
        return enriched;
    }

    private boolean canMerge(Difference previous, Difference current) {
        if (!previous.type().equals(current.type())) return false;
        int previousExpectedLength = previous.expected().codePointCount(0, previous.expected().length());
        if ("EXTRA".equals(previous.type())) {
            return previous.expectedIndex() == current.expectedIndex();
        }
        return previous.expectedIndex() + previousExpectedLength == current.expectedIndex();
    }

    private String codePointSlice(int[] codePoints, int start, int end) {
        StringBuilder result = new StringBuilder(Math.max(0, end - start));
        for (int index = start; index < end; index++) result.appendCodePoint(codePoints[index]);
        return result.toString();
    }

    private String codePoint(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    /** 一处非匹配对齐。 */
    public record Difference(
            String type,
            String expected,
            String actual,
            int expectedIndex,
            String contextBefore,
            String contextAfter,
            boolean tailOmission) {
        private Difference(String type, String expected, String actual, int expectedIndex) {
            this(type, expected, actual, expectedIndex, "", "", false);
        }
    }

    /** 一次背诵的完整确定性对齐结果。 */
    public record AlignmentResult(
            String normalizedExpected,
            String normalizedActual,
            int correctCount,
            int missingCount,
            int wrongCount,
            int extraCount,
            BigDecimal score,
            List<Difference> differences) {
    }
}

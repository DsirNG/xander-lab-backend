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
        int[] left = expected.codePoints().toArray();
        int[] right = actual.codePoints().toArray();
        int[][] distance = new int[left.length + 1][right.length + 1];
        for (int i = 0; i <= left.length; i++) distance[i][0] = i;
        for (int j = 0; j <= right.length; j++) distance[0][j] = j;
        for (int i = 1; i <= left.length; i++) {
            for (int j = 1; j <= right.length; j++) {
                int substitution = distance[i - 1][j - 1] + (left[i - 1] == right[j - 1] ? 0 : 1);
                distance[i][j] = Math.min(substitution,
                        Math.min(distance[i - 1][j] + 1, distance[i][j - 1] + 1));
            }
        }

        List<Difference> reversed = new ArrayList<>();
        int matches = 0;
        int missing = 0;
        int extra = 0;
        int wrong = 0;
        int i = left.length;
        int j = right.length;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && left[i - 1] == right[j - 1]
                    && distance[i][j] == distance[i - 1][j - 1]) {
                matches++;
                i--;
                j--;
            } else if (i > 0 && distance[i][j] == distance[i - 1][j] + 1) {
                missing++;
                reversed.add(new Difference("MISSING", codePoint(left[i - 1]), "", i - 1));
                i--;
            } else if (j > 0 && distance[i][j] == distance[i][j - 1] + 1) {
                extra++;
                reversed.add(new Difference("EXTRA", "", codePoint(right[j - 1]), i));
                j--;
            } else {
                wrong++;
                reversed.add(new Difference("WRONG", codePoint(left[i - 1]), codePoint(right[j - 1]), i - 1));
                i--;
                j--;
            }
        }
        Collections.reverse(reversed);
        List<Difference> differences = groupDifferences(reversed, left);
        double penalty = missing + wrong + extra * 0.5d;
        double rawScore = left.length == 0 ? 0d : Math.max(0d, 100d * (1d - penalty / left.length));
        BigDecimal score = BigDecimal.valueOf(rawScore).setScale(2, RoundingMode.HALF_UP);
        return new AlignmentResult(expected, actual, matches, missing, wrong, extra, score, differences);
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

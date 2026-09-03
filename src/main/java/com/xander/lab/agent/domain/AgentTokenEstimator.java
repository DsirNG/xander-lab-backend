package com.xander.lab.agent.domain;

import java.util.List;

/**
 * 上下文压力的字符级估算。
 *
 * <p>为什么要估：压缩必须在发请求<b>之前</b>决定，那时候还没有任何真实用量；
 * 而模型服务是管理员配置的第三方 OpenAI 兼容端点，我们既拿不到它的分词器，
 * 也不能为了数几个 token 先打一次网络请求。</p>
 *
 * <p>估算规则按 BPE 的实际表现分两档：中日韩文字基本一字一 token，
 * 拉丁字母和数字大约四字符一 token。再给每一轮加一点 role 包装的固定开销。
 * 它当然不精确——但压缩只需要判断"是不是快满了"，
 * 阈值本身留了两成余量，估偏一点不影响这个判断。</p>
 */
public final class AgentTokenEstimator {

    /** 每轮消息的 role/分隔符包装开销，按常见 chat 模板取值。 */
    private static final int PER_TURN_OVERHEAD = 4;

    private AgentTokenEstimator() {
    }

    /** 估算一段文本的 token 数，不含每轮包装开销。 */
    public static int estimateText(String text) {
        if (text == null || text.isEmpty()) return 0;
        int wide = 0;
        int narrow = 0;
        for (int index = 0; index < text.length(); index++) {
            if (isWide(text.charAt(index))) wide++;
            else narrow++;
        }
        // 窄字符向上取整：一段 3 个字母的短词也要算 1 个 token，不能算 0。
        return wide + (narrow + 3) / 4;
    }

    /** 估算一轮消息占用的 token 数，含 role 包装开销。 */
    public static int estimateTurn(String text) {
        return PER_TURN_OVERHEAD + estimateText(text);
    }

    /** 估算若干轮的总占用。 */
    public static int estimateTurns(List<AgentInputTurn> turns) {
        if (turns == null) return 0;
        int total = 0;
        for (AgentInputTurn turn : turns) total += estimateTurn(turn.text());
        return total;
    }

    /**
     * 中日韩文字与全角标点算宽字符。
     *
     * <p>这些区段在常见 BPE 词表里基本是一字一 token（有时不止一个），按 1 计是这个方向上的下限；
     * 它与窄字符 1/4 的比例关系是对的，足够支撑阈值判断。</p>
     */
    private static boolean isWide(char character) {
        return (character >= '　' && character <= '〿')   // CJK 标点
                || (character >= '぀' && character <= 'ヿ')   // 平假名、片假名
                || (character >= '㐀' && character <= '䶿')   // 汉字扩展 A
                || (character >= '一' && character <= '鿿')   // 基本汉字
                || (character >= '가' && character <= '힯')   // 谚文
                || (character >= '豈' && character <= '﫿')   // 兼容汉字
                || (character >= '＀' && character <= '｠');  // 全角形式
    }
}

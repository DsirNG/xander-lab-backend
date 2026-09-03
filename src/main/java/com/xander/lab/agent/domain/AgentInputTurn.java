package com.xander.lab.agent.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条 role 化的模型输入：对话记录不再压成一整段"【用户】…【智能体】…"的纯文本。
 *
 * <p>压成一段的写法有两处代价。一是模型看到的全部是"用户说的话"，包括它自己上一轮的回复和
 * 工具结果——谁说的要靠正文里的方括号标记去猜，而那些标记和用户真能打出来的字并无区别。
 * 二是缓存与截断都只能按字符切，切在哪儿完全没有语义边界。</p>
 *
 * <p>只允许 user 和 assistant 两种 role：系统侧的话都在 instructions 里，
 * 工具结果没有原生 tool role 可用（那要等原生 tool calling），所以作为观察挂在 user 轮里，
 * 正文用【工具结果】标出来源。</p>
 */
public record AgentInputTurn(String role, String text) {

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";

    public AgentInputTurn {
        if (!USER.equals(role) && !ASSISTANT.equals(role)) {
            throw new IllegalArgumentException("模型输入只支持 user / assistant 两种 role：" + role);
        }
        text = text == null ? "" : text;
    }

    public static AgentInputTurn user(String text) {
        return new AgentInputTurn(USER, text);
    }

    public static AgentInputTurn assistant(String text) {
        return new AgentInputTurn(ASSISTANT, text);
    }

    public boolean isUser() {
        return USER.equals(role);
    }

    /**
     * 合并相邻同 role 的轮次，保证输出严格 user/assistant 交替。
     *
     * <p>Responses 接口本身不要求交替，但这里的端点是可配置的 OpenAI 兼容服务，
     * 其中一部分只接受交替序列。合并只动文本拼接，不改语义，代价远小于换一家模型就崩。
     * 空文本轮直接丢掉：它对模型没有信息量，却会占掉一个交替位。</p>
     */
    public static List<AgentInputTurn> merged(List<AgentInputTurn> turns) {
        List<AgentInputTurn> merged = new ArrayList<>();
        for (AgentInputTurn turn : turns) {
            if (turn == null || turn.text().isBlank()) continue;
            AgentInputTurn last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && last.role().equals(turn.role())) {
                merged.set(merged.size() - 1,
                        new AgentInputTurn(turn.role(), last.text() + "\n\n" + turn.text()));
                continue;
            }
            merged.add(turn);
        }
        return List.copyOf(merged);
    }
}

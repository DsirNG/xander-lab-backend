package com.xander.lab.agent.application;

import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/**
 * 把模型 {@code thought} 字段收敛成一句可以直接展示给用户的进度说明，收不住就丢掉。
 *
 * <p>{@code thought} 是每步都会实时推给前端的，所以它是"面向用户的一句进度"，不是模型的内部推理。
 * 提示词已经这么要求了（见 {@code AgentModelClient#instructions}），但提示词是请求不是保证：
 * 模型偶尔仍会把整段思维链写进去。真实事故是用户发"给我一个sse代码"，界面上出现了
 * "用户需要可运行的 SSE 示例，且是多文件代码，必须通过 artifact 工具交付……"
 * ——原始推理加内部工具契约，全都摊给了用户看。</p>
 *
 * <p>这里的判据是"这句话像不像进度"，而不是"这句话对不对"：
 * 提到工具名或"工具"二字、复述内部协议字段、或者长到不像一行状态，都当作泄漏丢弃。
 * 丢弃是安全的——这一步紧接着就会发出工具调用事件，前端本来就会渲染在做什么，
 * 少一句进度只是少一句，多一段思维链才是错。</p>
 */
final class AgentThoughtSanitizer {

    /** 一句进度的字数上限；提示词要求不超过 30 字，留一倍余量，超了就当它写的是推理而不是进度。 */
    static final int MAX_STATUS_CHARS = 60;

    /** 内部协议词：出现在进度里说明模型在复述协议而不是在说人话。 */
    private static final Set<String> PROTOCOL_TOKENS = Set.of(
            "工具", "参数", "字段", "调用", "协议", "准则", "json", "schema",
            "done=", "done 为", "action", "args", "reply", "thought", "tool_call");

    private AgentThoughtSanitizer() {
    }

    /**
     * @param thought   模型这一步给出的原始 thought
     * @param toolNames 本轮真实存在的工具名；命中任意一个即视为泄漏
     * @return 可展示的一行进度，或空串表示这一步不展示进度
     */
    static String sanitize(String thought, Collection<String> toolNames) {
        if (!StringUtils.hasText(thought)) return "";
        String single = thought.replaceAll("\\s+", " ").trim();
        if (single.isEmpty()) return "";
        if (single.length() > MAX_STATUS_CHARS) return "";
        String lower = single.toLowerCase(Locale.ROOT);
        for (String token : PROTOCOL_TOKENS) {
            if (lower.contains(token)) return "";
        }
        if (toolNames != null) {
            for (String name : toolNames) {
                if (StringUtils.hasText(name) && lower.contains(name.toLowerCase(Locale.ROOT))) return "";
            }
        }
        return single;
    }
}

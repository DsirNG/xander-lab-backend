package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把模型的一次决策翻译成"这一步到底要调哪几个工具"。
 *
 * <p>协议里有两个字段，{@code actions}（数组，一步多个）与 {@code action}（单个，旧写法）。
 * 两者都认，{@code actions} 非空时以它为准，否则回落到 {@code action}——这样既有的单工具
 * 决策、以及不认新字段的中转网关，行为逐字不变。</p>
 *
 * <p>校验在这里一次做完，而不是在执行过程中边跑边发现，原因是**一批要么整体执行、要么整体
 * 拒绝**：半个批次跑完、半个批次被拒，模型拿到的结果与它报的计划对不上，它无法判断哪一半生效，
 * 只会重复调用已经成功的那部分。</p>
 *
 * <p>三种拒绝情形：</p>
 * <ul>
 *   <li><b>未知工具</b>——名字不在当轮允许集合里。</li>
 *   <li><b>批内完全重复</b>——同一个工具带着**完全相同的参数**在一批里出现两次。
 *       这不是"顺手禁掉同名工具"：同名但参数不同的两次调用是合法且有用的（一次查两个主题的
 *       知识点），前后端现在都按 {@code invocationId} 配对（见 {@code agentTrace.js}），
 *       同名不再有歧义。真正该拒的是"同一步里把同一件事说了两遍"——它要么是模型的笔误，
 *       要么是写操作会被执行两次（重复发帖、重复扣款）。</li>
 *   <li><b>空批次</b>——{@code done=false} 却没说调什么，按旧的"未知工具（空工具名）"处理。</li>
 * </ul>
 *
 * @param calls         本步要执行的调用，保持模型给的顺序（并发执行时也靠这个顺序落库）
 * @param skipped       因超过单步上限而未执行的调用；调用方**必须**把它们的"未执行"结果
 *                      写回历史，否则模型会以为自己问过、只是没收到答案
 * @param rejection     非 null 表示整批拒绝执行，内容是要回灌给模型的纠正说明
 * @param rejectedTools 被拒的工具名，用于组装终止轮次时给用户看的那句话
 */
public record AgentActionPlan(List<AgentToolCall> calls, List<AgentToolCall> skipped,
                              String rejection, List<String> rejectedTools) {

    /** 单个工具名在反馈里的兜底写法，与 {@code AgentConversationService.describeTool} 保持一致。 */
    private static String describe(String toolName) {
        return StringUtils.hasText(toolName) ? toolName : "（空工具名）";
    }

    private static AgentActionPlan reject(String reason, List<String> tools) {
        return new AgentActionPlan(List.of(), List.of(), reason, List.copyOf(tools));
    }

    /**
     * @param decision         模型决策；{@code actions} 优先，为空则回落到 {@code action}
     * @param allowedTools     当轮允许的工具名集合
     * @param maxParallelTools 单步最多执行几个调用；超出部分进 {@link #skipped()}
     */
    public static AgentActionPlan from(JsonNode decision, Set<String> allowedTools, int maxParallelTools) {
        List<AgentToolCall> parsed = parse(decision);
        if (parsed.isEmpty()) {
            parsed = List.of(new AgentToolCall("", com.fasterxml.jackson.databind.node.MissingNode.getInstance()));
        }

        List<String> unknown = parsed.stream()
                .map(AgentToolCall::toolName)
                .filter(name -> !allowedTools.contains(name))
                .toList();
        if (!unknown.isEmpty()) {
            return reject("未知工具 " + String.join("、", unknown.stream().map(AgentActionPlan::describe).toList())
                    + "，请从可用工具中选择", unknown);
        }

        // 完全重复（同名**且**同参数）才是错：同一步里把同一件事说了两遍，要么是笔误，
        // 要么会让写操作真的执行两次。同名但参数不同是合法用法（一次查两个主题），
        // 前后端都按 invocationId 配对，不能连它一起拒掉。
        Set<AgentToolCall> seen = new LinkedHashSet<>();
        List<String> duplicates = parsed.stream()
                .filter(call -> !seen.add(call))
                .map(AgentToolCall::toolName)
                .toList();
        if (!duplicates.isEmpty()) {
            return reject("同一步里重复了完全相同的调用（" + String.join("、", duplicates)
                    + "）。如果是同一件事，只保留一次即可；如果参数不同，请把参数写全", duplicates);
        }

        int limit = Math.max(1, maxParallelTools);
        if (parsed.size() <= limit) {
            return new AgentActionPlan(List.copyOf(parsed), List.of(), null, List.of());
        }
        return new AgentActionPlan(List.copyOf(parsed.subList(0, limit)),
                List.copyOf(parsed.subList(limit, parsed.size())), null, List.of());
    }

    private static List<AgentToolCall> parse(JsonNode decision) {
        if (decision == null) return List.of();
        JsonNode actions = decision.path("actions");
        if (actions.isArray()) {
            List<AgentToolCall> batch = new ArrayList<>();
            for (JsonNode entry : actions) {
                if (entry == null || !entry.isObject()) continue;
                batch.add(new AgentToolCall(entry.path("tool").asText("").trim(), entry.path("args")));
            }
            if (!batch.isEmpty()) return batch;
        }
        JsonNode action = decision.path("action");
        if (!action.isObject()) return List.of();
        String name = action.path("tool").asText("").trim();
        if (!StringUtils.hasText(name)) return List.of();
        return List.of(new AgentToolCall(name, action.path("args")));
    }

    /** 整批是否被拒。 */
    public boolean rejected() {
        return rejection != null;
    }
}

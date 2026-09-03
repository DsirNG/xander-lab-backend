package com.xander.lab.agent.application;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves a user goal into a bounded, deterministic composition of trusted skills.
 *
 * <p>技能只负责<b>加宽</b>工具面，永远不负责收窄到比 {@link #BASELINE_TOOLS} 更小的集合。
 * 早先的实现让命中的技能整体替换工具表，于是关键词决定了模型有什么能力：一句"给我一个 sse 代码"
 * 只命中 artifact-delivery，模型当轮就只剩 emit_artifact 一个工具，除了开交付卡别无选择；
 * 反过来"帮我写个服务端"一个词都不命中，就连交付卡都拿不到。行动准则里写着每轮都该维护计划、
 * 该记的就记，可这些工具当轮往往不在表里——提示词承诺的能力和工具表不一致，模型只能二选一。</p>
 */
@Component
public class AgentSkillRegistry {
    private static final double GENERAL_CONFIDENCE = 0.35D;
    /**
     * 每一轮都必须在表里的工具，与用户说了什么无关。
     *
     * <p>三类：只读查询；效果只落在当前会话界面上的交付与判分；以及行动准则要求持续维护的
     * 智能体自身状态（任务计划、长期记忆）。它们都不产生外部副作用，所以"给不给"从来不是一个
     * 权限问题，只是"这一轮答案长什么样"的问题，那个判断该由模型自己做。</p>
     */
    static final Set<String> BASELINE_TOOLS = Set.of(
            "query_knowledge", "query_posts", "query_plans", "query_email_reminders",
            "query_unsynced_posts", "recall_memory",
            "emit_artifact", "emit_quiz", "grade_answer",
            "plan_tasks", "update_plan", "remember");

    private final List<AgentSkill> skills;
    private final AgentToolRegistry toolRegistry;
    private final boolean legacyAllTools;

    @Autowired
    public AgentSkillRegistry(List<AgentSkill> skills, AgentToolRegistry toolRegistry) {
        this(skills, toolRegistry, false);
    }

    private AgentSkillRegistry(List<AgentSkill> skills, AgentToolRegistry toolRegistry,
                               boolean legacyAllTools) {
        this.skills = skills.stream().sorted(Comparator.comparing(AgentSkill::id)).toList();
        this.toolRegistry = toolRegistry;
        this.legacyAllTools = legacyAllTools;
    }

    /** Compatibility registry for embedded callers that intentionally use the pre-Skill tool set. */
    static AgentSkillRegistry legacy(AgentToolRegistry toolRegistry) {
        return new AgentSkillRegistry(List.of(), toolRegistry, true);
    }

    /** Selects at most two close candidates so compound requests can compose compatible skills. */
    public AgentSkillSelection resolve(String userMessage) {
        String message = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT);
        if (legacyAllTools) {
            AgentSkill general = new DefaultGeneralSkill();
            return new AgentSkillSelection(List.of(general), GENERAL_CONFIDENCE,
                    "legacy compatibility tool profile", toolRegistry.allowedNames(), general.instructions());
        }
        List<ScoredSkill> candidates = skills.stream()
                .map(skill -> new ScoredSkill(skill, skill.matchScore(message)))
                .filter(candidate -> candidate.score() > 0)
                .sorted(Comparator.comparingInt(ScoredSkill::score).reversed()
                        .thenComparing(candidate -> candidate.skill().id()))
                .toList();
        if (candidates.isEmpty()) return generalSelection();

        int highest = candidates.get(0).score();
        List<AgentSkill> selected = candidates.stream()
                .filter(candidate -> candidate.score() >= highest - 1)
                .limit(2)
                .map(ScoredSkill::skill)
                .toList();
        Set<String> requested = new LinkedHashSet<>(BASELINE_TOOLS);
        selected.forEach(skill -> requested.addAll(skill.toolNames()));
        Set<String> toolNames = registered(requested);
        String reason = selected.stream().map(skill -> skill.matchReason(message)).filter(StringUtils::hasText)
                .distinct().reduce((left, right) -> left + "; " + right).orElse("matched skill");
        String instructions = selected.stream().map(AgentSkill::instructions)
                .filter(StringUtils::hasText).reduce((left, right) -> left + "\n" + right).orElse("");
        double confidence = Math.min(0.95D, 0.55D + highest * 0.12D);
        return new AgentSkillSelection(selected, confidence, reason, toolNames, instructions);
    }

    private AgentSkillSelection generalSelection() {
        AgentSkill general = skills.stream().filter(skill -> "general-chat".equals(skill.id()))
                .findFirst().orElse(new DefaultGeneralSkill());
        return new AgentSkillSelection(List.of(general), GENERAL_CONFIDENCE,
                "no domain skill matched; use clarification or direct answer",
                registered(BASELINE_TOOLS), general.instructions());
    }

    /**
     * 与注册表求交：技能声明的工具名可能压根没注册（拼错，或那个 Bean 被裁掉了），
     * 留在选择结果里会渲染出一个模型调不动的工具。
     *
     * <p>渲染顺序不靠这里保证——{@link AgentToolRegistry#describeTools(Set)} 是遍历按名字排序的
     * 注册表再按成员过滤的，所以工具表的前缀每轮都一致，上游 prompt 缓存不会因集合顺序失效。</p>
     */
    private Set<String> registered(Set<String> requested) {
        Set<String> resolved = new LinkedHashSet<>(toolRegistry.names());
        resolved.retainAll(requested);
        return resolved;
    }

    private record ScoredSkill(AgentSkill skill, int score) {
    }

    private static final class DefaultGeneralSkill implements AgentSkill {
        @Override public String id() { return "general-chat"; }
        @Override public String version() { return "1"; }
        @Override public int matchScore(String userMessage) { return 0; }
        @Override public Set<String> toolNames() { return Set.of(); }
        @Override public String instructions() {
            return "当前没有明确的业务技能。直接回答；缺少关键信息时先澄清，不要猜测并执行写入操作。";
        }
        @Override public String matchReason(String userMessage) { return "general fallback"; }
    }
}

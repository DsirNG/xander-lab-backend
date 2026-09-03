package com.xander.lab.agent.application;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Resolves a user goal into a bounded, deterministic composition of trusted skills. */
@Component
public class AgentSkillRegistry {
    private static final double GENERAL_CONFIDENCE = 0.35D;
    private static final Set<String> GENERAL_TOOLS = Set.of(
            "query_knowledge", "query_posts", "query_plans", "query_email_reminders",
            "query_unsynced_posts", "recall_memory");

    private final List<AgentSkill> skills;
    private final AgentToolRegistry toolRegistry;
    private final boolean legacyAllTools;

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
        Set<String> toolNames = new LinkedHashSet<>();
        selected.forEach(skill -> toolNames.addAll(skill.toolNames()));
        toolNames.retainAll(toolRegistry.names());
        String reason = selected.stream().map(skill -> skill.matchReason(message)).filter(StringUtils::hasText)
                .distinct().reduce((left, right) -> left + "; " + right).orElse("matched skill");
        String instructions = selected.stream().map(AgentSkill::instructions)
                .filter(StringUtils::hasText).reduce((left, right) -> left + "\n" + right).orElse("");
        double confidence = Math.min(0.95D, 0.55D + highest * 0.12D);
        return new AgentSkillSelection(selected, confidence, reason, toolNames, instructions);
    }

    private AgentSkillSelection generalSelection() {
        Set<String> tools = new LinkedHashSet<>(GENERAL_TOOLS);
        tools.retainAll(toolRegistry.names());
        AgentSkill general = skills.stream().filter(skill -> "general-chat".equals(skill.id()))
                .findFirst().orElse(new DefaultGeneralSkill());
        return new AgentSkillSelection(List.of(general), GENERAL_CONFIDENCE,
                "no domain skill matched; use clarification or direct answer", tools, general.instructions());
    }

    private record ScoredSkill(AgentSkill skill, int score) {
    }

    private static final class DefaultGeneralSkill implements AgentSkill {
        @Override public String id() { return "general-chat"; }
        @Override public String version() { return "1"; }
        @Override public int matchScore(String userMessage) { return 0; }
        @Override public Set<String> toolNames() { return GENERAL_TOOLS; }
        @Override public String instructions() {
            return "当前没有明确的业务技能。直接回答；缺少关键信息时先澄清，不要猜测并执行写入操作。";
        }
        @Override public String matchReason(String userMessage) { return "general fallback"; }
    }
}

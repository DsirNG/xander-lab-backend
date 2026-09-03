package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Set;

/** Immutable result of resolving a user goal into the active skill composition. */
public record AgentSkillSelection(
        List<AgentSkill> skills,
        double confidence,
        String reason,
        Set<String> toolNames,
        String instructions) {

    public AgentSkillSelection {
        skills = List.copyOf(skills);
        toolNames = Set.copyOf(toolNames);
        reason = reason == null ? "" : reason;
        instructions = instructions == null ? "" : instructions;
    }

    public List<String> skillIds() {
        return skills.stream().map(AgentSkill::id).toList();
    }

    /** Stores classification facts without persisting the original user prompt. */
    public String intentJson() {
        ObjectNode intent = JsonNodeFactory.instance.objectNode();
        intent.put("confidence", confidence);
        intent.put("reason", reason);
        intent.putArray("skills").addAll(skillIds().stream().map(JsonNodeFactory.instance::textNode).toList());
        return intent.toString();
    }

    /** Stores exact skill versions and the narrowed capability set for this run. */
    public String skillsJson() {
        ArrayNode active = JsonNodeFactory.instance.arrayNode();
        for (AgentSkill skill : skills) {
            active.addObject().put("id", skill.id()).put("version", skill.version());
        }
        ObjectNode snapshot = JsonNodeFactory.instance.objectNode();
        snapshot.set("active", active);
        snapshot.putArray("toolNames").addAll(toolNames.stream().sorted()
                .map(JsonNodeFactory.instance::textNode).toList());
        return snapshot.toString();
    }

    public String executionSnapshotJson() {
        return JsonNodeFactory.instance.objectNode()
                .put("skillCount", skills.size())
                .put("confidence", confidence)
                .put("toolCount", toolNames.size())
                .toString();
    }
}

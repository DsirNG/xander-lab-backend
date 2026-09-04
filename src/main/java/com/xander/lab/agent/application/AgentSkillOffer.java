package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Set;

/**
 * 这一轮给模型的技能供给：一份固定的工具表，加一份它可以自己去读的目录。
 *
 * <p>取代了早先的 {@code AgentSkillSelection}。那条记录里有 {@code confidence} 和
 * {@code reason}——服务端按关键词猜出的意图和它的置信度。现在服务端不猜了，
 * 所以这两个字段没有对应的事实可写，与其填一个恒定的 0.95 不如让它们消失。</p>
 */
public record AgentSkillOffer(List<AgentSkill> skills, Set<String> toolNames, String catalog) {

    public AgentSkillOffer {
        skills = List.copyOf(skills);
        toolNames = Set.copyOf(toolNames);
        catalog = catalog == null ? "" : catalog;
    }

    public List<String> skillIds() {
        return skills.stream().map(AgentSkill::id).toList();
    }

    /** 这一轮怎么决定技能的：写下机制本身，而不是一次判定结果。 */
    public String intentJson() {
        return JsonNodeFactory.instance.objectNode()
                .put("routing", "model-loaded-catalog")
                .put("catalogSize", skills.size())
                .toString();
    }

    /** 目录里都有什么、当轮工具表是什么，供排查"模型当时能读到哪些技能"。 */
    public String skillsJson() {
        ArrayNode offered = JsonNodeFactory.instance.arrayNode();
        for (AgentSkill skill : skills) {
            offered.addObject().put("id", skill.id()).put("version", skill.version())
                    .put("source", skill.builtin() ? "builtin" : "user");
        }
        ObjectNode snapshot = JsonNodeFactory.instance.objectNode();
        snapshot.set("offered", offered);
        snapshot.putArray("toolNames").addAll(toolNames.stream().sorted()
                .map(JsonNodeFactory.instance::textNode).toList());
        return snapshot.toString();
    }

    public String executionSnapshotJson() {
        return JsonNodeFactory.instance.objectNode()
                .put("catalogSize", skills.size())
                .put("toolCount", toolNames.size())
                .toString();
    }
}

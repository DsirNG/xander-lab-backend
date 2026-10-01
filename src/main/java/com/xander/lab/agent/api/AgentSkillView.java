package com.xander.lab.agent.api;

import java.util.List;

/** Client-facing Skill catalog entry used by the Agent plugin picker and preview. */
public record AgentSkillView(String id, String skillKey, String name, String description,
                             String instructions, String version, String source,
                             List<String> toolNames) {
}

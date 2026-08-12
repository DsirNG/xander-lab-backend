package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Registers the tools an agent conversation may invoke, keyed by name. */
@Component
public class AgentToolRegistry {

    private final Map<String, AgentTool> tools;

    public AgentToolRegistry(List<AgentTool> availableTools) {
        this.tools = availableTools.stream()
                .collect(Collectors.toUnmodifiableMap(AgentTool::name, Function.identity()));
    }

    public AgentTool get(String name) {
        return tools.get(name);
    }

    public List<String> names() {
        return List.copyOf(tools.keySet());
    }

    /** Renders name/description/schema so the model can choose a tool. */
    public String describeTools() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode root = factory.objectNode();
        ObjectNode toolsNode = root.putObject("tools");
        tools.forEach((name, tool) -> {
            ObjectNode entry = toolsNode.putObject(name);
            entry.put("description", tool.description());
            entry.set("parameters", tool.parametersSchema());
        });
        return root.toString();
    }
}
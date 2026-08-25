package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 智能体可用的工具表。
 *
 * <p>这里刻意不做"按关键词猜用户想干什么，再决定给不给工具"的筛选。那种做法把决策提前到了
 * 规则层：命中不了词表的诉求（"帮我记住这段话"）会连工具都看不到，模型只能空口答应，
 * 于是像工作流而不像智能体。现在每一轮都把完整工具表交给模型，用不用、用哪个由它自己判断。</p>
 *
 * <p>按名字排序输出，保证同一套工具每轮渲染出的前缀完全一致，便于上游做 prompt 缓存。</p>
 */
@Component
public class AgentToolRegistry {

    private final Map<String, AgentTool> tools;

    public AgentToolRegistry(List<AgentTool> availableTools) {
        Map<String, AgentTool> sorted = new TreeMap<>();
        availableTools.forEach(tool -> sorted.put(tool.name(), tool));
        this.tools = Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    public AgentTool get(String name) {
        return tools.get(name);
    }

    public List<String> names() {
        return List.copyOf(tools.keySet());
    }

    /** 本轮允许调用的工具集合：全部。保留独立方法便于将来按用户权限收窄。 */
    public Set<String> allowedNames() {
        return Set.copyOf(tools.keySet());
    }

    /** Renders name/description/schema so the model can choose a tool. */
    public String describeTools() {
        return describeTools(tools.keySet());
    }

    /** Renders the selected tool schemas; an empty selection produces no schema block. */
    public String describeTools(Set<String> selectedNames) {
        if (selectedNames == null || selectedNames.isEmpty()) return "";
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode root = factory.objectNode();
        ObjectNode toolsNode = root.putObject("tools");
        tools.forEach((name, tool) -> {
            if (!selectedNames.contains(name)) return;
            ObjectNode entry = toolsNode.putObject(name);
            entry.put("description", tool.description());
            entry.set("parameters", tool.parametersSchema());
        });
        return root.toString();
    }
}

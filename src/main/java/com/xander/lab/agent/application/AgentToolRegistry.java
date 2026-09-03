package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 智能体已注册的全部工具。
 *
 * <p>这里只做登记，不做"这一轮该给哪些"的判断——那是 {@link AgentSkillRegistry} 的事，
 * 而它的规则是：{@link AgentSkillRegistry#BASELINE_TOOLS} 每轮无条件在表里，命中的技能只往上加。
 * 反过来的做法（关键词没命中就连工具都看不到）会把决策提前到规则层：一句"帮我记住这段话"
 * 若没命中词表，模型就只能空口答应，于是像工作流而不像智能体。</p>
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

    /**
     * 本轮工具面 = schema 表 + 在表工具自带的使用准则。
     *
     * <p>准则跟着工具走，所以一个当轮不在表里的工具不会在提示词里被规定行为——
     * 全局行动准则做不到这件事，而它做不到正是"给我一个 sse 代码也被开成交付卡"的成因之一。</p>
     *
     * <p>整块只取决于工具集合，与用户说了什么无关，因此可以整段进上游 prompt 缓存。</p>
     */
    public String describeToolSurface(Set<String> selectedNames) {
        String descriptions = describeTools(selectedNames);
        if (descriptions.isEmpty()) return "";
        String policies = renderPolicies(selectedNames);
        return policies.isEmpty() ? descriptions : descriptions + "\n" + policies;
    }

    /** 按 (order, 工具名) 排序编号；顺序固定，提示词前缀才不会每轮变化。 */
    private String renderPolicies(Set<String> selectedNames) {
        List<AgentPromptSection> sections = tools.entrySet().stream()
                .filter(entry -> selectedNames.contains(entry.getKey()))
                .flatMap(entry -> entry.getValue().promptSection().stream()
                        .filter(section -> !section.isEmpty())
                        .map(section -> new NamedSection(entry.getKey(), section)))
                .sorted(Comparator.<NamedSection>comparingInt(named -> named.section().order())
                        .thenComparing(NamedSection::name))
                .map(NamedSection::section)
                .toList();
        if (sections.isEmpty()) return "";
        StringBuilder builder = new StringBuilder("工具使用准则（只对本轮在表里的工具有效）：\n");
        for (int index = 0; index < sections.size(); index++) {
            builder.append("T").append(index + 1).append(". ").append(sections.get(index).text()).append('\n');
        }
        return builder.toString();
    }

    private record NamedSection(String name, AgentPromptSection section) {
    }
}

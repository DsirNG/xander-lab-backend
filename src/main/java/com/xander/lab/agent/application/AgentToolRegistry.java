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
 * <p>这里只做登记。"这一轮该给哪些"曾经是一个真问题——早先由一张关键词表挑技能、
 * 技能再决定工具表，于是一句"帮我记住这段话"没命中词表，模型就只能空口答应；
 * 一句"给我一个 sse 代码"只命中交付类技能，当轮只剩 emit_artifact，除了开交付卡别无选择。
 * 现在工具表每轮固定（{@link #allowedNames()}），要不要用、怎么用由模型自己判断，
 * 带外部副作用的调用由审批策略拦，而不是靠"看不见就调不动"。</p>
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
        return describeToolSurface(selectedNames, "");
    }

    /**
     * 工具面再加一份技能目录（{@link AgentSkillCatalog}）。
     *
     * <p>目录跟着工具面走而不是进对话记录：这里渲染出的整块字符串落在提示词的
     * {@code instructions} 前缀里，压缩碰不到它，也不需要"压缩后重新建立目录"那套补救。</p>
     *
     * <p>同一份 {@code (selectedNames, catalog)} 必须渲染出逐字节相同的结果：
     * 摘要调用与主调用各自算一次，只有两次结果一致，摘要请求才是主请求的真前缀。</p>
     */
    public String describeToolSurface(Set<String> selectedNames, String catalog) {
        String descriptions = describeTools(selectedNames);
        if (descriptions.isEmpty()) return "";
        StringBuilder surface = new StringBuilder(descriptions);
        String policies = renderPolicies(selectedNames);
        if (!policies.isEmpty()) surface.append('\n').append(policies);
        if (catalog != null && !catalog.isEmpty()) surface.append('\n').append(catalog);
        return surface.toString();
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

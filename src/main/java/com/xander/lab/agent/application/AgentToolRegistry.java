package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 智能体已注册的全部工具。
 *
 * <p>这里只做登记。"这一轮该给哪些"曾经是一个真问题——早先由一张关键词表挑技能、
 * 技能再决定工具表，于是一句"帮我记住这段话"没命中词表，模型就只能空口答应；
 * 一句"给我一个 sse 代码"只命中交付类技能，当轮只剩 emit_artifact，除了开交付卡别无选择。
 * 现在工具表每轮固定（{@link #allowedNames(Long)}），要不要用、怎么用由模型自己判断，
 * 带外部副作用的调用由审批策略拦，而不是靠"看不见就调不动"。</p>
 *
 * <p>按名字排序输出，保证同一套工具每轮渲染出的前缀完全一致，便于上游做 prompt 缓存。</p>
 *
 * <p>工具分两层：内置工具在启动期固定，动态工具（远端 MCP 等）由
 * {@link AgentDynamicToolSource} 提供、随配置变化。合并结果按用户缓存，
 * 配置变更时由来源方调用 {@link #invalidate()} 丢弃——这样同一轮里的多次渲染读到的是
 * 同一份 map，{@link #describeToolSurface} 的"逐字节一致"前提才成立。</p>
 */
@Component
public class AgentToolRegistry {

    /** 内置工具，启动期固定，永不变化。 */
    private final Map<String, AgentTool> tools;

    private final List<AgentDynamicToolSource> dynamicSources = new CopyOnWriteArrayList<>();

    /** 内置 + 全部动态工具的合并视图；null 表示需要重建。不区分用户的场景读它。 */
    private volatile Map<String, AgentTool> allToolsCache;

    /** 按用户缓存的合并视图：内置 + 平台级动态工具 + 该用户自己的动态工具。 */
    private final Map<Long, Map<String, AgentTool>> perUserTools = new ConcurrentHashMap<>();

    public AgentToolRegistry(List<AgentTool> availableTools) {
        Map<String, AgentTool> sorted = new TreeMap<>();
        availableTools.forEach(tool -> sorted.put(tool.name(), tool));
        this.tools = Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    /**
     * 挂上动态工具来源。
     *
     * <p>可选依赖：没有动态来源时（例如单元测试直接 new 注册表），所有方法只看到内置工具。</p>
     */
    @Autowired(required = false)
    public void setDynamicToolSources(List<AgentDynamicToolSource> sources) {
        dynamicSources.clear();
        if (sources != null) dynamicSources.addAll(sources);
        invalidate();
    }

    /** 配置变更后丢弃合并快照；下一次读取时重建。 */
    public void invalidate() {
        this.allToolsCache = null;
        this.perUserTools.clear();
    }

    public AgentTool get(String name) {
        return allTools().get(name);
    }

    /** 全部工具名，不区分用户。用于日志与文本清洗这类没有归属的场景。 */
    public List<String> names() {
        return List.copyOf(allTools().keySet());
    }

    /** 该用户可用的工具名，按字典序。用于需要稳定顺序的展示场景。 */
    public List<String> names(Long userId) {
        return List.copyOf(mergedFor(userId).keySet());
    }

    /**
     * 该用户可见的工具对象，按名字排序。
     *
     * <p>要拿工具对象就用这个方法，别用 {@code names(userId)} 配 {@link #get(String)}：
     * {@code get} 查的是全量表（合并了 {@code dynamicTools(null)} 那一支），
     * 一旦某个动态来源在"不限用户"这一支上给不出全部工具，两者就会对不上——
     * 名字列得出来，工具对象却是 null，于是调用方在取 description 时 NPE。</p>
     */
    public Collection<AgentTool> tools(Long userId) {
        return mergedFor(userId).values();
    }

    /** 本轮允许调用的工具集合：全部。保留独立方法便于将来按用户权限收窄。 */
    public Set<String> allowedNames() {
        return Set.copyOf(allTools().keySet());
    }

    /**
     * 该用户本轮允许调用的工具集合。
     *
     * <p>这是收窄真正生效的地方：平台级动态工具人人可见，用户自己配的只对自己可见。
     * 校验仍然发生在 {@link AgentToolExecutionPipeline}（它拿这份集合比对工具名），
     * 所以 {@link #get(String)} 走全量表并不构成越权——越权在比对那一步就被挡住了。</p>
     */
    public Set<String> allowedNames(Long userId) {
        if (userId == null) return allowedNames();
        return Set.copyOf(mergedFor(userId).keySet());
    }

    /** Renders name/description/schema so the model can choose a tool. */
    public String describeTools() {
        return describeTools(allTools().keySet());
    }

    /** Renders the selected tool schemas; an empty selection produces no schema block. */
    public String describeTools(Set<String> selectedNames) {
        if (selectedNames == null || selectedNames.isEmpty()) return "";
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode root = factory.objectNode();
        ObjectNode toolsNode = root.putObject("tools");
        allTools().forEach((name, tool) -> {
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

    /**
     * 合并内置工具与全部动态工具，结果缓存到 {@link #allTools}。
     *
     * <p>动态工具不得顶掉同名内置工具：内置工具的名字是既有行为的一部分，
     * 被一台远端服务器顶掉会让老会话的能力无声改变。所以用 putIfAbsent。</p>
     */
    private Map<String, AgentTool> allTools() {
        Map<String, AgentTool> current = allToolsCache;
        if (current != null) return current;
        Map<String, AgentTool> built = merge(tools, null);
        allToolsCache = built;
        return built;
    }

    /** 按用户缓存的合并视图；{@code userId} 为 null 时等价于全量表。 */
    private Map<String, AgentTool> mergedFor(Long userId) {
        if (userId == null) return allTools();
        return perUserTools.computeIfAbsent(userId, id -> merge(tools, id));
    }

    private Map<String, AgentTool> merge(Map<String, AgentTool> builtin, Long userId) {
        Map<String, AgentTool> merged = new TreeMap<>(builtin);
        for (AgentDynamicToolSource source : dynamicSources) {
            List<AgentTool> dynamic;
            try {
                dynamic = source.dynamicTools(userId);
            } catch (RuntimeException exception) {
                // 动态来源取不到工具不该让整轮对话失败：少几个工具，对话照常。
                continue;
            }
            if (dynamic == null) continue;
            for (AgentTool tool : dynamic) {
                if (tool == null || tool.name() == null) continue;
                merged.putIfAbsent(tool.name(), tool);
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(merged));
    }

    /** 按 (order, 工具名) 排序编号；顺序固定，提示词前缀才不会每轮变化。 */
    private String renderPolicies(Set<String> selectedNames) {
        List<AgentPromptSection> sections = allTools().entrySet().stream()
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

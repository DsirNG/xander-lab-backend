package com.xander.lab.agent.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.application.AgentDynamicToolSource;
import com.xander.lab.agent.application.AgentTool;
import com.xander.lab.agent.spi.RemoteTool;
import com.xander.lab.agent.spi.RemoteToolCatalog;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 把全部 {@link RemoteToolCatalog} 契约实现收成一个动态工具来源。
 *
 * <p>它是 agent 侧的<b>入站适配器</b>：契约那一侧（integration 的 MCP 客户端）只知道
 * {@code RemoteToolCatalog}，agent 的注册表只认识 {@link AgentDynamicToolSource}，
 * 本类把前者翻成后者。每取回一个 {@link RemoteTool} 就包一层
 * {@link RemoteToolAdapter}。</p>
 *
 * <h2>为什么版本要在这里折叠一次</h2>
 * <p>{@link AgentDynamicToolSource#version()} 是一个 {@code long}，而这里可能有多个提供方。
 * 折叠用的是 {@code v * 31 + next}，它在这里是<b>单射</b>的，理由值得写下来：每个
 * {@code catalogVersion()} 都单调不减（契约要求），而 {@code catalogs} 是构造期注入的，
 * 长度在 bean 存活期间不变。于是若 {@code a1 * 31 + b1 == a2 * 31 + b2} 且 {@code a1 &lt; a2}，
 * 则 {@code 31 * (a2 - a1) == b1 - b2}，即 {@code b1 &gt; b2}——与单调性矛盾。所以两个不同的
 * 版本向量不会折成同一个值。</p>
 *
 * <p>（{@code AgentToolRegistry} 那边反而用 {@code List<Long>} 逐元素比较，因为它的
 * {@code dynamicSources} 可以在装配期被整个换掉、长度会变。两处的取舍不同，是因为约束不同。）</p>
 */
@Component
public class RemoteToolDynamicSource implements AgentDynamicToolSource {

    private final List<RemoteToolCatalog> catalogs;
    private final ObjectMapper objectMapper;

    public RemoteToolDynamicSource(List<RemoteToolCatalog> catalogs, ObjectMapper objectMapper) {
        this.catalogs = catalogs == null ? List.of() : List.copyOf(catalogs);
        this.objectMapper = objectMapper;
    }

    @Override
    public List<AgentTool> dynamicTools(Long userId) {
        List<AgentTool> tools = new ArrayList<>();
        for (RemoteToolCatalog catalog : catalogs) {
            List<RemoteTool> provided;
            try {
                provided = catalog.tools(userId);
            } catch (RuntimeException exception) {
                // 逐个提供方兜底，而不是让整个来源一起消失：注册表是按"来源"兜的
                // （它捕获 dynamicTools 抛出的异常并跳过整个来源），所以这里不接住的话，
                // 一个提供方抖动会连带把另外几个提供方的工具也从工具面里抹掉。
                continue;
            }
            if (provided == null) continue;
            for (RemoteTool tool : provided) {
                if (tool == null || tool.name() == null) continue;
                tools.add(new RemoteToolAdapter(tool, objectMapper));
            }
        }
        return tools;
    }

    @Override
    public long version() {
        long version = 0L;
        for (RemoteToolCatalog catalog : catalogs) {
            version = version * 31L + catalog.catalogVersion();
        }
        return version;
    }
}

package com.xander.lab.integration.mcp.client.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.application.AgentDynamicToolSource;
import com.xander.lab.agent.application.AgentTool;
import com.xander.lab.integration.mcp.client.McpClientProperties;
import com.xander.lab.integration.mcp.client.domain.AgentMcpServer;
import com.xander.lab.integration.mcp.client.domain.McpRemoteTool;
import com.xander.lab.integration.mcp.client.infrastructure.AgentMcpServerMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把启用的远端 MCP 服务器上的工具，变成智能体工具面里的一份动态工具。
 *
 * <p>只读缓存，不碰网络：工具清单来自 {@code agent_mcp_server.tools_json}，
 * 那是上次探测的结果。所以渲染工具面永远不发请求——探测只发生在用户点「测试连接」时。</p>
 *
 * <p>可见性：平台级配置（owner 0）人人可用，用户自己配的只对自己可见。
 * 这条规则在这里生效，是「甲的服务器不该出现在乙的工具面里」的唯一实现点。</p>
 */
@Component
@RequiredArgsConstructor
public class McpToolSource implements AgentDynamicToolSource {

    private final McpClientProperties properties;
    private final AgentMcpServerMapper mapper;
    private final McpConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;

    private volatile List<AgentTool> allCached;

    private final Map<Long, List<AgentTool>> perUserCached = new ConcurrentHashMap<>();

    @Override
    public List<AgentTool> dynamicTools(Long userId) {
        if (!properties.isEnabled()) return List.of();
        if (userId == null) {
            List<AgentTool> current = allCached;
            if (current != null) return current;
            List<AgentTool> loaded = List.copyOf(load(null));
            allCached = loaded;
            return loaded;
        }
        return perUserCached.computeIfAbsent(userId, id -> List.copyOf(load(id)));
    }

    /**
     * 配置变更后丢弃缓存。
     *
     * <p>调用方还需要同时让 {@link com.xander.lab.agent.application.AgentToolRegistry#invalidate()}
     * 失效——注册表缓存的是合并视图，本类只缓存自己这一层。两处都清才算真正生效。</p>
     */
    public void invalidate() {
        allCached = null;
        perUserCached.clear();
    }

    /** 读出某台服务器上次探测到的工具目录；缓存损坏时按"没有工具"处理，不让整轮对话失败。 */
    public List<McpRemoteTool> readCatalog(AgentMcpServer server) {
        String json = server.getToolsJson();
        if (json == null || json.isBlank()) return List.of();
        try {
            List<McpRemoteTool> tools = objectMapper.readValue(json, new TypeReference<>() { });
            return tools == null ? List.of() : tools;
        } catch (Exception exception) {
            return List.of();
        }
    }

    /** 把探测到的工具目录序列化成 {@code tools_json} 的存储形态。 */
    public String writeCatalog(List<McpRemoteTool> tools) {
        try {
            return objectMapper.writeValueAsString(tools == null ? List.of() : tools);
        } catch (Exception exception) {
            throw new IllegalStateException("无法序列化远端工具目录", exception);
        }
    }

    private List<AgentTool> load(Long userId) {
        List<AgentMcpServer> servers = mapper.selectList(new LambdaQueryWrapper<AgentMcpServer>()
                .eq(AgentMcpServer::getEnabled, true)
                .orderByAsc(AgentMcpServer::getOwnerUserId)
                .orderByAsc(AgentMcpServer::getServerKey));
        List<AgentTool> tools = new ArrayList<>();
        for (AgentMcpServer server : servers) {
            if (!visibleTo(server, userId)) continue;
            tools.addAll(toolsOf(server));
        }
        return tools;
    }

    /**
     * 可见性规则：平台级配置（owner 0）人人可用，用户自己配的只对自己可见。
     *
     * <p>刻意放在 Java 里而不是只写成 SQL 条件：这是「甲的服务器不能出现在乙的工具面里」的
     * 唯一实现点。一行能读的代码比藏进拼出来的 where 子句更容易守住，也才测得到。
     * 服务器配置行数是个位数，全量取回再筛没有性能问题。</p>
     *
     * @param userId {@code null} 表示不限定用户（{@code names()} 这类场景），全部可见
     */
    static boolean visibleTo(AgentMcpServer server, Long userId) {
        if (userId == null) return true;
        return server.isPlatformOwned() || userId.equals(server.getOwnerUserId());
    }

    private List<AgentTool> toolsOf(AgentMcpServer server) {
        List<McpRemoteTool> remotes = readCatalog(server);
        if (remotes.isEmpty()) return List.of();
        List<AgentTool> tools = new ArrayList<>();
        for (McpRemoteTool remote : remotes) {
            if (remote == null || remote.name() == null || remote.name().isBlank()) continue;
            String name = RemoteMcpTool.composeName(server.getServerKey(), remote.name());
            // agent_tool_invocation.tool_name 与 agent_approval.tool_name 都是 VARCHAR(64)。
            // 名字过长会在落库那一刻炸掉整轮执行，所以这里就把它挡在外面，而不是等调用时才发现。
            if (name.length() > properties.getMaxToolNameLength()) continue;
            tools.add(new RemoteMcpTool(server, remote, name, describe(server, remote), connectionFactory));
        }
        return tools;
    }

    /**
     * 给远端描述加上来源标注并截断。
     *
     * <p>截断是必须的：这段文字直接进提示词，而它的长度由第三方决定。不截断等于把
     * 提示词预算的控制权交给别人。标注来源则让模型知道这段说明的权威性低于本平台自己的工具。</p>
     */
    private String describe(AgentMcpServer server, McpRemoteTool remote) {
        String raw = remote.description() == null ? "" : remote.description().strip();
        int limit = Math.max(1, properties.getMaxDescriptionLength());
        if (raw.length() > limit) raw = raw.substring(0, limit) + "…";
        String origin = "（第三方 MCP 服务器「" + server.getDisplayName() + "」提供）";
        return raw.isEmpty() ? origin + "远端未提供说明" : raw + " " + origin;
    }
}

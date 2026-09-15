package com.xander.lab.integration.mcp.client.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.application.AgentToolRegistry;
import com.xander.lab.integration.mcp.client.McpClientProperties;
import com.xander.lab.integration.mcp.client.api.AgentMcpServerRequest;
import com.xander.lab.integration.mcp.client.api.AgentMcpServerView;
import com.xander.lab.integration.mcp.client.domain.AgentMcpServer;
import com.xander.lab.integration.mcp.client.domain.McpRemoteTool;
import com.xander.lab.integration.mcp.client.infrastructure.AgentMcpServerMapper;
import com.xander.lab.integration.mcp.client.infrastructure.McpEndpointGuard;
import com.xander.lab.integration.mcp.client.infrastructure.McpHeaderCipher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 远端 MCP 服务器配置的增删改查与连通性探测。
 *
 * <p>归属是这里唯一的权限边界：{@code ownerUserId} 为 0 的行是平台级配置（管理员维护），
 * 其余行属于某个用户。所有写操作都先 {@link #require(Long, long)} 校验归属，
 * 调用方传进来的 ownerUserId 就是它能操作的范围——管理员传 0，普通用户传自己的 ID。</p>
 */
@Service
public class AgentMcpServerService {

    /** 请求头名称允许的字符集，与 HTTP 令牌的定义一致。 */
    private static final String HEADER_NAME_PATTERN = "[A-Za-z0-9!#$%&'*+.^_`|~-]+";

    private final AgentMcpServerMapper mapper;
    private final McpConnectionFactory connectionFactory;
    private final McpToolSource toolSource;
    private final McpHeaderCipher headerCipher;
    private final McpClientProperties properties;
    private final AgentToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public AgentMcpServerService(AgentMcpServerMapper mapper, McpConnectionFactory connectionFactory,
                                 McpToolSource toolSource, McpHeaderCipher headerCipher,
                                 McpClientProperties properties, AgentToolRegistry toolRegistry,
                                 ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.connectionFactory = connectionFactory;
        this.toolSource = toolSource;
        this.headerCipher = headerCipher;
        this.properties = properties;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    /** 列出某个归属下的全部服务器：管理员传 0 看平台级，用户传自己看自己的。 */
    @Transactional(readOnly = true)
    public List<AgentMcpServerView> list(long ownerUserId) {
        return mapper.selectList(new LambdaQueryWrapper<AgentMcpServer>()
                        .eq(AgentMcpServer::getOwnerUserId, ownerUserId)
                        .orderByAsc(AgentMcpServer::getServerKey)).stream()
                .map(this::toView).toList();
    }

    @Transactional
    public AgentMcpServerView create(long ownerUserId, AgentMcpServerRequest request) {
        requireEnabled();
        if (mapper.selectCount(new LambdaQueryWrapper<AgentMcpServer>()
                .eq(AgentMcpServer::getOwnerUserId, ownerUserId)
                .eq(AgentMcpServer::getServerKey, request.getServerKey())) > 0) {
            throw new IllegalArgumentException("已存在同名标识的服务器：" + request.getServerKey());
        }
        AgentMcpServer server = new AgentMcpServer();
        server.setOwnerUserId(ownerUserId);
        server.setServerKey(request.getServerKey());
        server.setDisplayName(request.getDisplayName().strip());
        server.setEndpointUrl(normalizeEndpoint(request.getEndpointUrl()));
        server.setHeadersEncrypted(encryptHeaders(request.getHeaders(), null));
        server.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
        if (mapper.insert(server) != 1) throw new IllegalStateException("MCP 服务器创建失败");
        changed();
        return toView(server);
    }

    @Transactional
    public AgentMcpServerView update(Long id, long ownerUserId, AgentMcpServerRequest request) {
        requireEnabled();
        AgentMcpServer server = require(id, ownerUserId);
        // server_key 是工具名前缀的一部分。改了它，已写好的 Skill 与历史审批记录会指向不存在的工具，
        // 而历史记录无法回溯修正——所以标识一旦定下就不许改，要换就新建一台。
        if (!server.getServerKey().equals(request.getServerKey())) {
            throw new IllegalArgumentException("服务器标识不可修改，请新建一台服务器");
        }
        server.setDisplayName(request.getDisplayName().strip());
        server.setEndpointUrl(normalizeEndpoint(request.getEndpointUrl()));
        server.setHeadersEncrypted(encryptHeaders(request.getHeaders(), server.getHeadersEncrypted()));
        server.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
        mapper.updateById(server);
        // 端点或凭据变了，缓存里的旧连接已经指向错误目标。
        connectionFactory.evictServer(id);
        changed();
        return toView(server);
    }

    @Transactional
    public void delete(Long id, long ownerUserId) {
        AgentMcpServer server = require(id, ownerUserId);
        mapper.deleteById(server.getId());
        connectionFactory.evictServer(id);
        changed();
    }

    /**
     * 探测连通性并刷新工具目录。
     *
     * <p>失败不抛异常，而是记进 {@code last_status}/{@code last_error} 并照常返回视图：
     * 界面要展示的是"这台连不上，原因是 X"，一个 500 只会把它变成一句无用的错误提示。</p>
     *
     * <p>失败时**保留**上一次的工具目录。清掉会让这台服务器的能力从工具面里静默消失，
     * 而对方可能只是临时不可达；保留则最坏是模型调到一个不存在的工具、拿到一条明确报错。</p>
     */
    @Transactional
    public AgentMcpServerView probe(Long id, long ownerUserId) {
        requireEnabled();
        AgentMcpServer server = require(id, ownerUserId);
        try {
            List<McpRemoteTool> tools = connectionFactory.listTools(server);
            int limit = Math.max(1, properties.getMaxToolsPerServer());
            List<McpRemoteTool> kept = tools.size() > limit ? List.copyOf(tools.subList(0, limit)) : tools;
            server.setToolsJson(toolSource.writeCatalog(kept));
            server.setLastStatus(AgentMcpServer.STATUS_OK);
            server.setLastError(tools.size() > limit
                    ? "远端工具共 " + tools.size() + " 个，超过上限，只并入前 " + limit + " 个" : null);
            server.setLastConnectedAt(LocalDateTime.now());
        } catch (RuntimeException exception) {
            connectionFactory.evictServer(id);
            server.setLastStatus(AgentMcpServer.STATUS_FAILED);
            server.setLastError(safeMessage(exception));
        }
        mapper.updateById(server);
        changed();
        return toView(server);
    }

    /**
     * 配置变更后让工具面重新计算。
     *
     * <p>两处缓存都要清：本类上游的 {@link McpToolSource} 缓存"有哪些远端工具"，
     * 注册表缓存"内置 + 动态合并后长什么样"。只清一处，改动不会生效。</p>
     */
    private void changed() {
        toolSource.invalidate();
        toolRegistry.invalidate();
    }

    private AgentMcpServer require(Long id, long ownerUserId) {
        AgentMcpServer server = id == null ? null : mapper.selectById(id);
        if (server == null || server.getOwnerUserId() == null || server.getOwnerUserId() != ownerUserId) {
            // 归属不符时与"不存在"给同一个回复，避免用它探测别人有哪些服务器。
            throw new IllegalArgumentException("MCP 服务器不存在");
        }
        return server;
    }

    /** 存归一化后的地址，顺带把 SSRF 校验提前到保存那一刻——存一个永远连不上的地址没有意义。 */
    private String normalizeEndpoint(String raw) {
        return McpEndpointGuard.validate(raw, InetAddress::getAllByName).toString();
    }

    /** {@code null} 表示不改；空对象表示显式清空。 */
    private String encryptHeaders(Map<String, String> headers, String existing) {
        if (headers == null) return existing;
        if (headers.isEmpty()) return null;
        validateHeaders(headers);
        try {
            return headerCipher.encrypt(objectMapper.writeValueAsString(new LinkedHashMap<>(headers)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法加密 MCP 服务器请求头", exception);
        }
    }

    /**
     * 拦截非法的请求头名与值。
     *
     * <p>值里的换行必须挡掉：{@code java.net.http} 会拒绝它，但那是在调用那一刻抛出的、
     * 离配置动作很远的异常；更要紧的是换行是请求头注入的经典载体。在这里挡下来，
     * 用户看到的是"这个请求头填错了"，而不是某个工具突然调不通。</p>
     */
    private void validateHeaders(Map<String, String> headers) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) throw new IllegalArgumentException("请求头名称不能为空");
            if (!name.matches(HEADER_NAME_PATTERN)) throw new IllegalArgumentException("请求头名称含非法字符：" + name);
            String value = entry.getValue();
            if (value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)) {
                throw new IllegalArgumentException("请求头值不能包含换行：" + name);
            }
        }
    }

    private AgentMcpServerView toView(AgentMcpServer server) {
        return new AgentMcpServerView(server.getId(), server.getOwnerUserId(), server.getServerKey(),
                server.getDisplayName(), server.getEndpointUrl(), Boolean.TRUE.equals(server.getEnabled()),
                server.isPlatformOwned(), server.getHeadersEncrypted() != null && !server.getHeadersEncrypted().isBlank(),
                toolSource.readCatalog(server), server.getLastStatus(), server.getLastError(),
                server.getLastConnectedAt());
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) throw new IllegalStateException("MCP 客户端功能未开启，无法配置远端服务器");
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) message = throwable.getClass().getSimpleName();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}

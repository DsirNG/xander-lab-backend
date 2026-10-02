package com.xander.lab.integration.mcp.client.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.integration.mcp.client.McpClientProperties;
import com.xander.lab.integration.mcp.client.domain.AgentMcpServer;
import com.xander.lab.integration.mcp.client.domain.McpRemoteTool;
import com.xander.lab.integration.mcp.client.infrastructure.McpEndpointGuard;
import com.xander.lab.integration.mcp.client.infrastructure.McpHeaderCipher;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 建立并复用通往远端 MCP 服务器的连接。
 *
 * <p>只支持 Streamable HTTP 传输。stdio 能让配置者在服务器上起子进程，等于凭空开一个远程执行面；
 * SSE 已被协议废弃。这两样的维护成本都换不来相应的覆盖率，所以不做。</p>
 *
 * <p>连接按「服务器 ID + 建连相关配置指纹」缓存：握手不便宜，不能每次工具调用都重来一遍；
 * 但换了端点或换了凭据就必须重连，所以指纹只取真正影响连接的那几个字段——
 * 刷新工具目录不会导致重连。</p>
 */
@Component
@RequiredArgsConstructor
public class McpConnectionFactory {

    private static final String CLIENT_NAME = "xander-lab";
    private static final String CLIENT_VERSION = "1.0.0";

    private final McpClientProperties properties;
    private final McpHeaderCipher headerCipher;
    private final ObjectMapper objectMapper;

    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();

    /** 取（必要时建立）通往该服务器的连接。 */
    public McpSyncClient clientFor(AgentMcpServer server) {
        requireEnabled();
        String key = cacheKey(server);
        McpSyncClient cached = clients.get(key);
        if (cached != null) return cached;
        synchronized (this) {
            cached = clients.get(key);
            if (cached != null) return cached;
            McpSyncClient opened = open(server);
            // 同一台服务器换过端点或凭据后，旧连接已经指向错误的目标，留着只会误用。
            evictServer(server.getId());
            clients.put(key, opened);
            return opened;
        }
    }

    /** 探测远端工具目录。这是唯一一次为了「知道对方有什么」而发起的网络请求。 */
    public List<McpRemoteTool> listTools(AgentMcpServer server) {
        McpSchema.ListToolsResult result = clientFor(server).listTools();
        if (result == null || result.tools() == null) return List.of();
        List<McpRemoteTool> tools = new ArrayList<>();
        for (McpSchema.Tool tool : result.tools()) {
            if (tool == null || tool.name() == null || tool.name().isBlank()) continue;
            tools.add(new McpRemoteTool(tool.name(), tool.description(),
                    objectMapper.valueToTree(tool.inputSchema())));
        }
        return tools;
    }

    /** 调用远端工具，并把 MCP 的返回体压成模型好读的 JSON。 */
    public JsonNode callTool(AgentMcpServer server, String remoteToolName, JsonNode args) {
        Map<String, Object> arguments = args == null || args.isNull()
                ? Map.of()
                : objectMapper.convertValue(args, new TypeReference<Map<String, Object>>() { });
        McpSchema.CallToolResult result =
                clientFor(server).callTool(new McpSchema.CallToolRequest(remoteToolName, arguments));
        return renderResult(result);
    }

    /** 丢弃某台服务器的所有缓存连接，用于配置变更或删除之后。 */
    public void evictServer(Long serverId) {
        if (serverId == null) return;
        String prefix = serverId + "@";
        for (Map.Entry<String, McpSyncClient> entry : clients.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            if (clients.remove(entry.getKey()) != null) closeQuietly(entry.getValue());
        }
    }

    @PreDestroy
    public void closeAll() {
        for (String key : List.copyOf(clients.keySet())) {
            McpSyncClient client = clients.remove(key);
            if (client != null) closeQuietly(client);
        }
    }

    private McpSyncClient open(AgentMcpServer server) {
        URI endpoint = McpEndpointGuard.validate(server.getEndpointUrl(), InetAddress::getAllByName);
        Map<String, String> headers = headersOf(server);
        Duration connectTimeout = Duration.ofSeconds(Math.max(1, properties.getConnectTimeoutSeconds()));

        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(endpoint.toString())
                .jsonMapper(McpJsonDefaults.getMapper())
                .connectTimeout(connectTimeout)
                .httpRequestCustomizer(new HeaderCustomizer(headers))
                .build();

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(Math.max(1, properties.getRequestTimeoutSeconds())))
                .initializationTimeout(connectTimeout)
                .clientInfo(new McpSchema.Implementation(CLIENT_NAME, CLIENT_VERSION))
                .build();
        try {
            client.initialize();
        } catch (RuntimeException exception) {
            closeQuietly(client);
            throw new IllegalStateException("连接远端 MCP 服务器失败：" + safeMessage(exception), exception);
        }
        return client;
    }

    private Map<String, String> headersOf(AgentMcpServer server) {
        String encrypted = server.getHeadersEncrypted();
        if (encrypted == null || encrypted.isBlank()) return Map.of();
        try {
            Map<String, String> headers = objectMapper.readValue(
                    headerCipher.decrypt(encrypted), new TypeReference<LinkedHashMap<String, String>>() { });
            return headers == null ? Map.of() : headers;
        } catch (Exception exception) {
            throw new IllegalStateException("MCP 服务器认证请求头无法解密，请重新编辑并保存认证请求头", exception);
        }
    }

    /**
     * 把 MCP 的返回体压成模型好读的形状。
     *
     * <p>远端可能返回结构化内容、纯文本，或图片音频这类本平台不消费的内容块。前两者原样透出，
     * 第三类只报个数——静默丢掉会让模型以为"对方什么都没说"，从而做出错误推断。</p>
     */
    private JsonNode renderResult(McpSchema.CallToolResult result) {
        ObjectNode node = objectMapper.createObjectNode();
        if (result == null) {
            node.put("isError", true);
            node.put("text", "远端 MCP 服务器没有返回结果");
            return node;
        }
        node.put("isError", Boolean.TRUE.equals(result.isError()));
        if (result.structuredContent() != null) {
            node.set("structuredContent", objectMapper.valueToTree(result.structuredContent()));
        }
        List<String> texts = new ArrayList<>();
        int otherBlocks = 0;
        if (result.content() != null) {
            for (McpSchema.Content content : result.content()) {
                if (content instanceof McpSchema.TextContent textContent) {
                    if (textContent.text() != null) texts.add(textContent.text());
                } else if (content != null) {
                    otherBlocks++;
                }
            }
        }
        if (!texts.isEmpty()) {
            ArrayNode array = node.putArray("text");
            texts.forEach(text -> array.add(truncate(text)));
        }
        if (otherBlocks > 0) {
            node.put("unsupportedContentBlocks", otherBlocks);
        }
        return node;
    }

    /**
     * 截断超长文本并留下显式标记。
     *
     * <p>标记是必要的：静默截断会让模型把半截结果当成完整结果，进而基于缺失信息给出结论。</p>
     */
    private String truncate(String text) {
        int limit = Math.max(1, properties.getMaxResultLength());
        if (text == null || text.length() <= limit) return text;
        return text.substring(0, limit) + "\n…（结果过长已截断，原始长度 " + text.length() + " 字符）";
    }

    private String cacheKey(AgentMcpServer server) {
        // 指纹只覆盖真正决定连接目标的字段：换端点或换凭据要重连，刷新工具目录不必。
        int fingerprint = Objects.hash(server.getEndpointUrl(), server.getHeadersEncrypted());
        return server.getId() + "@" + Integer.toHexString(fingerprint);
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) throw new IllegalStateException("MCP 客户端未启用");
    }

    private void closeQuietly(McpSyncClient client) {
        try {
            client.close();
        } catch (RuntimeException ignored) {
            // 关闭失败不影响调用方：连接已经不再被引用，由对端超时回收。
        }
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) return throwable.getClass().getSimpleName();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    /** 把配置好的请求头逐条挂到每一个出站请求上，远端凭据不落在 URL 里。 */
    private record HeaderCustomizer(Map<String, String> headers) implements McpSyncHttpClientRequestCustomizer {
        @Override
        public void customize(HttpRequest.Builder builder, String method, URI uri, String body,
                              McpTransportContext context) {
            headers.forEach(builder::header);
        }
    }
}

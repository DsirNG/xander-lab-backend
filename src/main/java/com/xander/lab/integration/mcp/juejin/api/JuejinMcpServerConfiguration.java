package com.xander.lab.integration.mcp.juejin.api;

import com.xander.lab.integration.mcp.juejin.application.JuejinMcpApplicationService;
import com.xander.lab.integration.mcp.security.McpOAuthRequestContext;
import com.xander.lab.integration.mcp.security.McpScopeErrors;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** MCP adapter exposing the Juejin account authorization state and owned-post publication. */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mcp.juejin", name = "enabled", havingValue = "true")
public class JuejinMcpServerConfiguration {
    private final JuejinMcpApplicationService juejinMcpService;

    @Bean
    HttpServletStreamableServerTransportProvider juejinMcpTransport() {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(McpJsonDefaults.getMapper()).mcpEndpoint("/api/mcp/juejin").build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> juejinMcpServlet(
            @Qualifier("juejinMcpTransport") HttpServletStreamableServerTransportProvider transport) {
        return new ServletRegistrationBean<>(transport, "/api/mcp/juejin");
    }

    @Bean
    McpSyncServer juejinMcpServer(@Qualifier("juejinMcpTransport") HttpServletStreamableServerTransportProvider transport) {
        return McpServer.sync(transport).serverInfo("xander-juejin", "1.0.0")
                .tools(authStatusTool(), publishArticleTool(), syncPostTool())
                .build();
    }

    private McpServerFeatures.SyncToolSpecification authStatusTool() {
        return tool("get_juejin_auth_status",
                "Returns whether the current user has an active Juejin account binding.",
                emptySchema(), ignored -> {
                    McpOAuthRequestContext.requireScope("juejin:status");
                    return result(Map.of("ok", true, "status", juejinMcpService.authorizationStatus()));
                });
    }

    private McpServerFeatures.SyncToolSpecification publishArticleTool() {
        return tool("publish_article_to_juejin",
                "Creates a local blog draft and publishes it to the bound Juejin account. The juejin category id and tag ids are Juejin-side numeric ids; a tag list of at least one id helps the review pass.",
                Map.of("type", "object", "properties", Map.of(
                        "title", Map.of("type", "string"),
                        "content", Map.of("type", "string"),
                        "summary", Map.of("type", "string"),
                        "juejinCategoryId", Map.of("type", "string", "description", "掘金分类 ID，缺省为前端分类"),
                        "juejinTagIds", Map.of("type", "array", "items", Map.of("type", "string"), "description", "掘金标签 ID 数组")),
                        "required", List.of("title", "content")), args -> {
            McpOAuthRequestContext.requireScope("juejin:publish");
            McpOAuthRequestContext.requireScope("blog:write");
            var result = juejinMcpService.publishArticle(
                    String.valueOf(args.get("title")),
                    String.valueOf(args.get("content")),
                    args.get("summary") == null ? null : String.valueOf(args.get("summary")),
                    args.get("juejinCategoryId") == null ? null : String.valueOf(args.get("juejinCategoryId")),
                    stringList(args.get("juejinTagIds")));
            return result(Map.of("ok", true, "externalId", result.externalId(), "url", result.url()));
        });
    }

    private McpServerFeatures.SyncToolSpecification syncPostTool() {
        return tool("sync_post_to_juejin",
                "Synchronizes one owned local blog post to the bound Juejin account.",
                Map.of("type", "object", "properties", Map.of(
                        "postId", Map.of("type", "integer"),
                        "juejinCategoryId", Map.of("type", "string", "description", "掘金分类 ID，缺省为前端分类"),
                        "juejinTagIds", Map.of("type", "array", "items", Map.of("type", "string"), "description", "掘金标签 ID 数组")),
                        "required", List.of("postId")), args -> {
            McpOAuthRequestContext.requireScope("juejin:publish");
            var result = juejinMcpService.syncOwnedPost(longValue(args, "postId"),
                    args.get("juejinCategoryId") == null ? null : String.valueOf(args.get("juejinCategoryId")),
                    stringList(args.get("juejinTagIds")));
            return result(Map.of("ok", true, "externalId", result.externalId(), "url", result.url()));
        });
    }

    private McpServerFeatures.SyncToolSpecification tool(String name, String description, Map<String, Object> schema,
                                                         Function<Map<String, Object>, McpSchema.CallToolResult> handler) {
        return new McpServerFeatures.SyncToolSpecification(McpSchema.Tool.builder(name).description(description).inputSchema(schema).build(),
                (exchange, request) -> McpScopeErrors.guard(() -> handler.apply(request.arguments())));
    }

    private McpSchema.CallToolResult result(Map<String, Object> data) {
        return McpSchema.CallToolResult.builder().structuredContent(data).addTextContent(data.toString()).build();
    }

    private Map<String, Object> emptySchema() { return Map.of("type", "object", "properties", Map.of()); }

    private Long longValue(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private List<String> stringList(Object value) {
        if (value == null) return null;
        if (!(value instanceof List<?> values)) throw new IllegalArgumentException("juejinTagIds must be an array");
        return values.stream().map(String::valueOf).toList();
    }
}
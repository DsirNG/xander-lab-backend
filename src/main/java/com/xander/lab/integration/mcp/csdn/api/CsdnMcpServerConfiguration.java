package com.xander.lab.integration.mcp.csdn.api;

import com.xander.lab.integration.mcp.csdn.application.CsdnMcpApplicationService;
import com.xander.lab.integration.mcp.security.McpOAuthRequestContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mcp.csdn", name = "enabled", havingValue = "true")
public class CsdnMcpServerConfiguration {
    private final CsdnMcpApplicationService csdnMcpService;

    @Bean
    HttpServletStreamableServerTransportProvider csdnMcpTransport() {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(McpJsonDefaults.getMapper()).mcpEndpoint("/api/mcp/csdn").build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> csdnMcpServlet(
            @Qualifier("csdnMcpTransport") HttpServletStreamableServerTransportProvider transport) {
        return new ServletRegistrationBean<>(transport, "/api/mcp/csdn");
    }

    @Bean
    McpSyncServer csdnMcpServer(@Qualifier("csdnMcpTransport") HttpServletStreamableServerTransportProvider transport) {
        return McpServer.sync(transport).serverInfo("xander-csdn", "1.0.0")
                .tools(authStatusTool(), startCsdnLoginTool(), syncPostTool())
                .build();
    }

    private McpServerFeatures.SyncToolSpecification authStatusTool() {
        return tool("get_csdn_auth_status", "Returns whether the current user has authorized CSDN sync.", emptySchema(),
                ignored -> {
                    McpOAuthRequestContext.requireScope("csdn:status");
                    return result(Map.of("ok", true, "authorized", csdnMcpService.authorizationStatus().status().equals("AUTHORIZED")));
                });
    }

    private McpServerFeatures.SyncToolSpecification startCsdnLoginTool() {
        return tool("start_csdn_login", "Starts CSDN QR authorization and returns the QR image data URL.", emptySchema(),
                ignored -> {
                    McpOAuthRequestContext.requireScope("csdn:authorize");
                    var view = csdnMcpService.startAuthorization();
                    return result(Map.of("ok", true, "status", view.status(), "qrCodeDataUrl", view.qrCodeDataUrl()));
                });
    }

    private McpServerFeatures.SyncToolSpecification syncPostTool() {
        return tool("sync_post_to_csdn", "Synchronizes one owned blog post to CSDN.", Map.of(
                "type", "object", "properties", Map.of("postId", Map.of("type", "integer")), "required", List.of("postId")), args -> {
            McpOAuthRequestContext.requireScope("csdn:publish");
            Long postId = longValue(args, "postId");
            var result = csdnMcpService.syncOwnedPost(postId);
            return result(Map.of("ok", true, "platform", result.platform(), "externalId", result.externalId(), "url", result.url()));
        });
    }

    private McpServerFeatures.SyncToolSpecification tool(String name, String description, Map<String, Object> schema,
                                                         java.util.function.Function<Map<String, Object>, McpSchema.CallToolResult> handler) {
        return new McpServerFeatures.SyncToolSpecification(McpSchema.Tool.builder(name).description(description).inputSchema(schema).build(),
                (exchange, request) -> handler.apply(request.arguments()));
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

}

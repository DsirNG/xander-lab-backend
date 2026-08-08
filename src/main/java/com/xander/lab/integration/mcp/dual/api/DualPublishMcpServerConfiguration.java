package com.xander.lab.integration.mcp.dual.api;

import com.xander.lab.publishing.api.BlogPostDTO;
import com.xander.lab.integration.mcp.dual.application.DualPublishMcpApplicationService;
import com.xander.lab.integration.mcp.security.McpOAuthRequestContext;
import com.xander.lab.integration.mcp.security.McpScopeErrors;
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

/** MCP adapter exposing the composite local-blog plus CSDN publication flow. */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mcp.dual", name = "enabled", havingValue = "true")
public class DualPublishMcpServerConfiguration {
    private final DualPublishMcpApplicationService dualService;

    @Bean
    HttpServletStreamableServerTransportProvider dualMcpTransport() {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(McpJsonDefaults.getMapper()).mcpEndpoint("/api/mcp/dual").build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> dualMcpServlet(
            @Qualifier("dualMcpTransport") HttpServletStreamableServerTransportProvider transport) {
        return new ServletRegistrationBean<>(transport, "/api/mcp/dual");
    }

    @Bean
    McpSyncServer dualMcpServer(@Qualifier("dualMcpTransport") HttpServletStreamableServerTransportProvider transport) {
        return McpServer.sync(transport).serverInfo("xander-dual-publish", "1.0.0")
                .tools(startCsdnLoginTool(), publishTool()).build();
    }

    private McpServerFeatures.SyncToolSpecification startCsdnLoginTool() {
        return tool("start_csdn_login", "Starts CSDN QR authorization for the bound Xander Lab user.", emptySchema(), ignored -> {
            McpOAuthRequestContext.requireScope("csdn:authorize");
            var view = dualService.startCsdnAuthorization();
            return result(Map.of("ok", true, "status", view.status(), "qrCodeDataUrl", view.qrCodeDataUrl()));
        });
    }

    private McpServerFeatures.SyncToolSpecification publishTool() {
        return tool("publish_to_both", "Publishes one article to the local blog and its bound CSDN account.",
                Map.of("type", "object", "properties", Map.of(
                        "title", Map.of("type", "string"), "content", Map.of("type", "string"),
                        "summary", Map.of("type", "string"), "categoryId", Map.of("type", "string"),
                        "tags", Map.of("type", "array", "items", Map.of("type", "string"))),
                        "required", List.of("title", "content")), args -> {
            McpOAuthRequestContext.requireScope("publish:dual");
            McpOAuthRequestContext.requireScope("blog:write");
            McpOAuthRequestContext.requireScope("csdn:publish");
            BlogPostDTO request = new BlogPostDTO();
            request.setTitle(String.valueOf(args.get("title")));
            request.setContent(String.valueOf(args.get("content")));
            request.setSummary(args.get("summary") == null ? null : String.valueOf(args.get("summary")));
            request.setCategoryId(args.get("categoryId") == null ? "backend" : String.valueOf(args.get("categoryId")));
            if (args.get("tags") instanceof List<?> tags) request.setTags(tags.stream().map(String::valueOf).toList());
            var publication = dualService.publish(request);
            return result(Map.of("ok", publication.csdnPublished(), "localPostId", publication.localPostId(),
                    "csdnPublished", publication.csdnPublished(), "csdnExternalId", String.valueOf(publication.csdnExternalId()),
                    "csdnUrl", String.valueOf(publication.csdnUrl()), "csdnError", String.valueOf(publication.csdnError())));
        });
    }

    private McpServerFeatures.SyncToolSpecification tool(String name, String description, Map<String, Object> schema,
                                                          java.util.function.Function<Map<String, Object>, McpSchema.CallToolResult> handler) {
        return new McpServerFeatures.SyncToolSpecification(McpSchema.Tool.builder(name).description(description).inputSchema(schema).build(),
                (exchange, request) -> McpScopeErrors.guard(() -> handler.apply(request.arguments())));
    }

    private McpSchema.CallToolResult result(Map<String, Object> data) {
        return McpSchema.CallToolResult.builder().structuredContent(data).addTextContent(data.toString()).build();
    }


    private Map<String, Object> emptySchema() { return Map.of("type", "object", "properties", Map.of()); }

}

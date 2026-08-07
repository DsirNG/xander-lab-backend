package com.xander.lab.integration.mcp.dual.api;

import com.xander.lab.dto.BlogPostDTO;
import com.xander.lab.integration.mcp.application.McpClientAuthorizationService;
import com.xander.lab.integration.mcp.dual.McpDualProperties;
import com.xander.lab.integration.mcp.dual.application.DualPublishMcpApplicationService;
import com.xander.lab.integration.mcp.security.McpClientContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** MCP adapter exposing the composite local-blog plus CSDN publication flow. */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mcp.dual", name = "enabled", havingValue = "true")
public class DualPublishMcpServerConfiguration {
    private final McpDualProperties properties;
    private final McpClientAuthorizationService authorizationService;
    private final DualPublishMcpApplicationService dualService;

    @Bean
    HttpServletStreamableServerTransportProvider dualMcpTransport() {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(McpJsonDefaults.getMapper()).mcpEndpoint("/api/mcp/dual").build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> dualMcpServlet(
            HttpServletStreamableServerTransportProvider transport) {
        return new ServletRegistrationBean<>(transport, "/api/mcp/dual");
    }

    @Bean
    McpSyncServer dualMcpServer(@Qualifier("dualMcpTransport") HttpServletStreamableServerTransportProvider transport) {
        return McpServer.sync(transport).serverInfo("xander-dual-publish", "1.0.0")
                .tools(startLoginTool(), startCsdnLoginTool(), publishTool()).build();
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> dualMcpClientFilter() {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                       FilterChain chain) throws IOException, jakarta.servlet.ServletException {
                String clientId = request.getHeader("X-MCP-Client-Id");
                String clientSecret = request.getHeader("X-MCP-Client-Secret");
                if (!Objects.equals(properties.getServerToken(), request.getHeader("X-MCP-Server-Token"))
                        || clientId == null || !clientId.matches("[A-Za-z0-9._-]{16,128}")
                        || clientSecret == null || !clientSecret.matches("[A-Za-z0-9._~-]{32,256}")) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\":\"MCP_CLIENT_AUTH_REQUIRED\"}");
                    return;
                }
                McpClientContext.setClient(clientId, clientSecret);
                try { chain.doFilter(request, response); }
                finally { McpClientContext.clear(); }
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/mcp/dual");
        registration.setOrder(-101);
        return registration;
    }

    private McpServerFeatures.SyncToolSpecification startLoginTool() {
        return tool("start_login", "Returns a browser URL to authorize this MCP client as a Xander Lab user.",
                emptySchema(), ignored -> result(Map.of("ok", true, "authorizeUrl", authorizeUrl())));
    }

    private McpServerFeatures.SyncToolSpecification startCsdnLoginTool() {
        return tool("start_csdn_login", "Starts CSDN QR authorization for the bound Xander Lab user.", emptySchema(), ignored -> {
            if (!dualService.isAuthorized()) return authRequired();
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
            if (!dualService.isAuthorized()) return authRequired();
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
                (exchange, request) -> handler.apply(request.arguments()));
    }

    private McpSchema.CallToolResult result(Map<String, Object> data) {
        return McpSchema.CallToolResult.builder().structuredContent(data).addTextContent(data.toString()).build();
    }

    private McpSchema.CallToolResult authRequired() {
        return McpSchema.CallToolResult.builder().isError(true).structuredContent(Map.of(
                "ok", false, "error", "AUTH_REQUIRED", "message", "请先完成站内用户授权",
                "authorizeUrl", authorizeUrl())).addTextContent("AUTH_REQUIRED").build();
    }

    private Map<String, Object> emptySchema() { return Map.of("type", "object", "properties", Map.of()); }

    private String authorizeUrl() {
        String clientId = McpClientContext.getClientId();
        String clientSecret = McpClientContext.getClientSecret();
        String code = authorizationService.createBrowserAuthorizationRequest(clientId, clientSecret);
        return properties.getPublicBaseUrl() + "/api/mcp/auth/login?clientId=" + clientId + "&code=" + code;
    }
}

package com.xander.lab.integration.mcp.csdn.api;

import com.xander.lab.integration.mcp.application.McpClientAuthorizationService;
import com.xander.lab.integration.mcp.csdn.application.CsdnMcpApplicationService;
import com.xander.lab.integration.mcp.csdn.CsdnMcpProperties;
import com.xander.lab.integration.mcp.security.McpClientContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mcp.csdn", name = "enabled", havingValue = "true")
public class CsdnMcpServerConfiguration {
    private final CsdnMcpProperties properties;
    private final CsdnMcpApplicationService csdnMcpService;
    private final McpClientAuthorizationService mcpAuthorizationService;

    @Bean
    HttpServletStreamableServerTransportProvider csdnMcpTransport() {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(McpJsonDefaults.getMapper()).mcpEndpoint("/api/mcp/csdn").build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> csdnMcpServlet(
            HttpServletStreamableServerTransportProvider transport) {
        return new ServletRegistrationBean<>(transport, "/api/mcp/csdn");
    }

    @Bean
    McpSyncServer csdnMcpServer(@Qualifier("csdnMcpTransport") HttpServletStreamableServerTransportProvider transport) {
        return McpServer.sync(transport).serverInfo("xander-csdn", "1.0.0")
                .tools(authStatusTool(), startLoginTool(), syncPostTool())
                .build();
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> csdnMcpClientTokenFilter() {
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
                try {
                    chain.doFilter(request, response);
                } finally {
                    McpClientContext.clear();
                }
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/mcp/csdn");
        registration.setOrder(-101);
        return registration;
    }

    private McpServerFeatures.SyncToolSpecification authStatusTool() {
        return tool("get_csdn_auth_status", "Returns whether the current user has authorized CSDN sync.", emptySchema(),
                ignored -> {
                    Long userId = csdnMcpService.currentUserId();
                    if (userId == null) return authRequired();
                    return result(Map.of("ok", true, "authorized", csdnMcpService.authorizationStatus().status().equals("AUTHORIZED")));
                });
    }

    private McpServerFeatures.SyncToolSpecification startLoginTool() {
        return tool("start_csdn_login", "Starts CSDN QR authorization and returns the QR image data URL.", emptySchema(),
                ignored -> {
                    Long userId = csdnMcpService.currentUserId();
                    if (userId == null) return authRequired();
                    var view = csdnMcpService.startAuthorization();
                    return result(Map.of("ok", true, "status", view.status(), "qrCodeDataUrl", view.qrCodeDataUrl()));
                });
    }

    private McpServerFeatures.SyncToolSpecification syncPostTool() {
        return tool("sync_post_to_csdn", "Synchronizes one owned blog post to CSDN.", Map.of(
                "type", "object", "properties", Map.of("postId", Map.of("type", "integer")), "required", List.of("postId")), args -> {
            Long userId = csdnMcpService.currentUserId();
            if (userId == null) return authRequired();
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

    private McpSchema.CallToolResult authRequired() {
        return McpSchema.CallToolResult.builder().isError(true).structuredContent(Map.of(
                "ok", false,
                "error", "AUTH_REQUIRED",
                "message", "请先完成 CSDN 授权",
                "authorizeUrl", authorizeUrl())).addTextContent("AUTH_REQUIRED: 请先完成 CSDN 授权").build();
    }

    private Map<String, Object> emptySchema() { return Map.of("type", "object", "properties", Map.of()); }

    private Long longValue(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private String authorizeUrl() {
        String clientId = McpClientContext.getClientId();
        if (clientId == null) throw new IllegalStateException("MCP client identity is required");
        String code = mcpAuthorizationService.createBrowserAuthorizationRequest(clientId, McpClientContext.getClientSecret());
        return properties.getPublicBaseUrl() + "/api/mcp/auth/login?clientId=" + clientId + "&code=" + code;
    }
}

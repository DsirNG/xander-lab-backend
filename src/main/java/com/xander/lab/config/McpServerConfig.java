package com.xander.lab.config;

import com.xander.lab.dto.BlogPostDTO;
import com.xander.lab.dto.BlogPostVO;
import com.xander.lab.service.McpBlogService;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;

@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mcp.blog", name = "enabled", havingValue = "true")
public class McpServerConfig {
    private final McpBlogProperties properties;
    private final McpBlogService blogService;

    @Bean
    HttpServletStreamableServerTransportProvider mcpTransport() {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(McpJsonDefaults.getMapper()).mcpEndpoint("/mcp").build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transport) {
        return new ServletRegistrationBean<>(transport, "/mcp");
    }

    @Bean
    McpSyncServer blogMcpServer(HttpServletStreamableServerTransportProvider transport) {
        return McpServer.sync(transport).serverInfo("xander-blog", "1.0.0")
                .tools(authStatusTool(), startLoginTool(), postTool("create_draft", false), postTool("publish_post", true))
                .build();
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> mcpClientTokenFilter() {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                      FilterChain chain) throws IOException, jakarta.servlet.ServletException {
                if (!Objects.equals(properties.getServerToken(), request.getHeader("X-MCP-Server-Token"))) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\":\"MCP_CLIENT_AUTH_REQUIRED\"}");
                    return;
                }
                chain.doFilter(request, response);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/mcp");
        registration.setOrder(-101);
        return registration;
    }

    private McpServerFeatures.SyncToolSpecification authStatusTool() {
        return tool("get_auth_status", "Returns whether the MCP server has an active blog authorization.", emptySchema(),
                ignored -> result(Map.of("ok", true, "authorized", blogService.isAuthorized())));
    }

    private McpServerFeatures.SyncToolSpecification startLoginTool() {
        return tool("start_login", "Returns a browser URL for one-time blog authorization. Never ask for passwords in chat.", emptySchema(),
                ignored -> result(Map.of("ok", true, "authorizeUrl", properties.getPublicBaseUrl() + "/mcp/auth/login")));
    }

    private McpServerFeatures.SyncToolSpecification postTool(String name, boolean publish) {
        return tool(name, publish ? "Publishes a blog post." : "Creates an unpublished blog draft.", postSchema(), args -> {
            if (!blogService.isAuthorized()) return authRequired();
            BlogPostDTO dto = new BlogPostDTO();
            dto.setTitle(value(args, "title"));
            dto.setContent(value(args, "content"));
            dto.setSummary(optionalValue(args, "summary", summarize(dto.getContent())));
            dto.setCategoryId(optionalValue(args, "categoryId", "backend"));
            Object rawTags = args.get("tags");
            if (rawTags instanceof List<?> tags) dto.setTags(tags.stream().map(String::valueOf).toList());
            BlogPostVO post = blogService.create(dto, publish);
            return result(Map.of("ok", true, "postId", post.getId(), "published", publish));
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
        return McpSchema.CallToolResult.builder().isError(true).structuredContent(Map.of("ok", false, "error", "AUTH_REQUIRED", "message", "请先完成博客后台授权", "authorizeUrl", properties.getPublicBaseUrl() + "/mcp/auth/login")).addTextContent("AUTH_REQUIRED: 请先完成博客后台授权").build();
    }
    private Map<String, Object> emptySchema() { return Map.of("type", "object", "properties", Map.of()); }
    private Map<String, Object> postSchema() {
        return Map.of("type", "object", "properties", Map.of("title", Map.of("type", "string"), "content", Map.of("type", "string"), "summary", Map.of("type", "string"), "categoryId", Map.of("type", "string"), "tags", Map.of("type", "array", "items", Map.of("type", "string"))), "required", List.of("title", "content"));
    }
    private String value(Map<String, Object> args, String key) { return String.valueOf(args.get(key)); }
    private String optionalValue(Map<String, Object> args, String key, String defaultValue) { return args.containsKey(key) && args.get(key) != null ? String.valueOf(args.get(key)) : defaultValue; }
    private String summarize(String content) { return content.length() <= 160 ? content : content.substring(0, 157) + "..."; }
}

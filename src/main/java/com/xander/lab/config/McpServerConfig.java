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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
                .jsonMapper(McpJsonDefaults.getMapper()).mcpEndpoint("/api/mcp").build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transport) {
        return new ServletRegistrationBean<>(transport, "/api/mcp");
    }

    @Bean
    McpSyncServer blogMcpServer(HttpServletStreamableServerTransportProvider transport) {
        return McpServer.sync(transport).serverInfo("xander-blog", "1.0.0")
                .tools(authStatusTool(), startLoginTool(), postTool("create_draft", false), postTool("publish_post", true),
                        updatePostTool(), deletePostTool())
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
        registration.addUrlPatterns("/api/mcp");
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
            dto.setContent(content(args));
            dto.setSummary(optionalValue(args, "summary", summarize(dto.getContent())));
            dto.setCategoryId(optionalValue(args, "categoryId", "backend"));
            Object rawTags = args.get("tags");
            if (rawTags instanceof List<?> tags) dto.setTags(tags.stream().map(String::valueOf).toList());
            BlogPostVO post = blogService.create(dto, publish);
            return result(Map.of("ok", true, "postId", post.getId(), "published", publish));
        });
    }

    private McpServerFeatures.SyncToolSpecification updatePostTool() {
        return tool("update_post", "Updates supplied fields of an existing blog post. Providing tags replaces all tags.", updateSchema(), args -> {
            if (!blogService.isAuthorized()) return authRequired();
            Long postId = longValue(args, "postId");
            String title = textOrBase64(args, "title", "titleBase64");
            String summary = textOrBase64(args, "summary", "summaryBase64");
            String content = hasAny(args, "content", "contentBase64") ? content(args) : null;
            String categoryId = optionalValue(args, "categoryId", null);
            List<String> tags = tags(args);
            BlogPostVO post = blogService.update(postId, title, summary, content, categoryId, tags);
            return result(Map.of("ok", true, "postId", post.getId(), "updated", true));
        });
    }

    private McpServerFeatures.SyncToolSpecification deletePostTool() {
        return tool("delete_post", "Permanently deletes a blog post. Requires confirm=true.", deleteSchema(), args -> {
            if (!blogService.isAuthorized()) return authRequired();
            if (!Boolean.TRUE.equals(args.get("confirm"))) {
                return McpSchema.CallToolResult.builder().isError(true).structuredContent(Map.of("ok", false, "error", "CONFIRMATION_REQUIRED", "message", "Set confirm=true to permanently delete this post.")).addTextContent("CONFIRMATION_REQUIRED").build();
            }
            Long postId = longValue(args, "postId");
            blogService.delete(postId);
            return result(Map.of("ok", true, "postId", postId, "deleted", true));
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
        return Map.of("type", "object", "properties", Map.of("title", Map.of("type", "string"), "content", Map.of("type", "string"), "contentBase64", Map.of("type", "string", "description", "UTF-8 article content encoded as Base64. Use only when an upstream proxy blocks literal content."), "summary", Map.of("type", "string"), "categoryId", Map.of("type", "string"), "tags", Map.of("type", "array", "items", Map.of("type", "string"))), "required", List.of("title"));
    }
    private Map<String, Object> updateSchema() {
        return Map.of("type", "object", "properties", Map.of("postId", Map.of("type", "integer"), "title", Map.of("type", "string"), "titleBase64", Map.of("type", "string"), "summary", Map.of("type", "string"), "summaryBase64", Map.of("type", "string"), "content", Map.of("type", "string"), "contentBase64", Map.of("type", "string"), "categoryId", Map.of("type", "string"), "tags", Map.of("type", "array", "items", Map.of("type", "string"))), "required", List.of("postId"));
    }
    private Map<String, Object> deleteSchema() {
        return Map.of("type", "object", "properties", Map.of("postId", Map.of("type", "integer"), "confirm", Map.of("type", "boolean")), "required", List.of("postId", "confirm"));
    }
    private String value(Map<String, Object> args, String key) { return String.valueOf(args.get(key)); }
    private Long longValue(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }
    private boolean hasAny(Map<String, Object> args, String... keys) {
        return java.util.Arrays.stream(keys).anyMatch(key -> args.get(key) != null);
    }
    private String textOrBase64(Map<String, Object> args, String plainKey, String base64Key) {
        if (args.get(plainKey) != null) return String.valueOf(args.get(plainKey));
        if (args.get(base64Key) == null) return null;
        try {
            return new String(Base64.getDecoder().decode(String.valueOf(args.get(base64Key))), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(base64Key + " must be valid Base64-encoded UTF-8 text");
        }
    }
    private String content(Map<String, Object> args) {
        Object rawContent = args.get("content");
        if (rawContent != null) return String.valueOf(rawContent);
        Object rawBase64 = args.get("contentBase64");
        if (rawBase64 == null) throw new IllegalArgumentException("content or contentBase64 is required");
        try {
            return new String(Base64.getDecoder().decode(String.valueOf(rawBase64)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("contentBase64 must be valid Base64-encoded UTF-8 text");
        }
    }
    private List<String> tags(Map<String, Object> args) {
        Object rawTags = args.get("tags");
        if (rawTags == null) return null;
        if (!(rawTags instanceof List<?> tagValues)) throw new IllegalArgumentException("tags must be an array");
        return tagValues.stream().map(String::valueOf).toList();
    }
    private String optionalValue(Map<String, Object> args, String key, String defaultValue) { return args.containsKey(key) && args.get(key) != null ? String.valueOf(args.get(key)) : defaultValue; }
    private String summarize(String content) { return content.length() <= 160 ? content : content.substring(0, 157) + "..."; }
}

package com.xander.lab.integration.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;

/**
 * MCP 传输层共用的 JSON mapper（4 个服务端 + 出站客户端）。
 *
 * <h2>为什么不直接用 {@code McpJsonDefaults.getMapper()}</h2>
 * <p>SDK 的“默认”mapper 就是 {@code JacksonMcpJsonMapperSupplier#get()} 里的
 * {@code new ObjectMapper()} —— <b>裸的</b>，一个模块都没注册，尤其没有 JSR-310。
 * 于是任何带 {@code java.time} 字段的工具返回值，在序列化整个 JSON-RPC 响应时直接抛：</p>
 *
 * <pre>
 * InvalidDefinitionException: Java 8 date/time type `java.time.LocalDate` not supported by default:
 *   add Module "com.fasterxml.jackson.datatype:jackson-datatype-jsr310" to enable handling
 *   (through reference chain: ... PageData["records"]-&gt; BlogPostVO["date"])
 * </pre>
 *
 * <p>{@code BlogPostVO.date} 正是 {@code LocalDate}，所以 {@code mcp.blog} 的
 * {@code list_posts} / {@code get_post} 一旦开启（{@code MCP_BLOG_ENABLED=true}）就必然失败 ——
 * 而且失败点在<b>传输层序列化</b>，不在工具处理器里，日志看起来像协议错误而不是字段问题。
 * 出站客户端同理：第三方 MCP 服务器返回 ISO 日期字符串时同样反序列化不了。</p>
 *
 * <p>注册 JSR-310 并关掉 {@code WRITE_DATES_AS_TIMESTAMPS}，日期序列化成
 * {@code "2026-09-17"} 而不是时间戳数组 —— 与 Spring Boot 自己的 ObjectMapper 默认一致，
 * 前端和 MCP 客户端看到的是同一种日期表示。</p>
 *
 * <p><b>⚠️ 这里刻意不留给每个配置类各写一遍</b>：五个使用点各写一遍迟早漏一个，
 * 而漏掉的那个只在对应 MCP 路径被启用时才暴露 —— 恰恰是最不容易被测到的那条路径。</p>
 */
public final class McpJsonMapperFactory {

    private McpJsonMapperFactory() {
    }

    /**
     * 返回一个注册了 JSR-310 的 MCP JSON mapper。
     *
     * <p>每次调用返回<b>新实例</b>：mapper 会被 SDK 拿去当传输层的可变配置载体，
     * 共享单例会让某一条 MCP 链路的调整影响其余四条。</p>
     */
    public static McpJsonMapper jsonMapper() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new JacksonMcpJsonMapper(mapper);
    }
}

package com.xander.lab.integration.mcp.security;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Maps a missing OAuth tool scope to a structured MCP tool error the client can act on, instead of
 * letting the unchecked exception surface as a generic JSON-RPC internal error.
 */
public final class McpScopeErrors {
    private McpScopeErrors() { }

    public static McpSchema.CallToolResult guard(Supplier<McpSchema.CallToolResult> call) {
        try {
            return call.get();
        } catch (McpInsufficientScopeException exception) {
            return McpSchema.CallToolResult.builder().isError(true)
                    .structuredContent(Map.of("ok", false, "error", "INSUFFICIENT_SCOPE", "message", exception.getMessage()))
                    .addTextContent(exception.getMessage())
                    .build();
        }
    }
}

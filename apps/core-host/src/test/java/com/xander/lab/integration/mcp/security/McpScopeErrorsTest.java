package com.xander.lab.integration.mcp.security;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Error mapping for missing MCP tool scopes, without touching a real MCP server. */
class McpScopeErrorsTest {

    @Test
    void missingScopeBecomesStructuredToolError() {
        McpSchema.CallToolResult result = McpScopeErrors.guard(() -> {
            throw new McpInsufficientScopeException("csdn:publish");
        });

        assertTrue(result.isError());
        assertEquals("MCP scope is required: csdn:publish",
                ((McpSchema.TextContent) result.content().get(0)).text());
        assertEquals(Map.of("ok", false, "error", "INSUFFICIENT_SCOPE", "message", "MCP scope is required: csdn:publish"),
                result.structuredContent());
    }

    @Test
    void successfulHandlerPassesThrough() {
        McpSchema.CallToolResult result = McpScopeErrors.guard(() -> McpSchema.CallToolResult.builder()
                .structuredContent(Map.of("ok", true)).build());

        assertFalse(result.isError());
        assertEquals(Map.of("ok", true), result.structuredContent());
    }
}

package com.xander.lab.integration.mcp.security;

/** Raised when a valid MCP access token lacks the capability required by a tool. */
public class McpInsufficientScopeException extends RuntimeException {
    public McpInsufficientScopeException(String scope) { super("MCP scope is required: " + scope); }
}

package com.xander.lab.integration.mcp.security;

/** OAuth protocol error with the HTTP status required by the remote MCP client. */
public class McpOAuthException extends RuntimeException {
    private final String error;
    private final int status;

    public McpOAuthException(String error, int status, String message) {
        super(message);
        this.error = error;
        this.status = status;
    }

    public String error() { return error; }
    public int status() { return status; }
}

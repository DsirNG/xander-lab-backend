package com.xander.lab.integration.mcp.security;

/** Holds the authenticated MCP client identifier for the lifetime of one request. */
public final class McpClientContext {
    private static final ThreadLocal<String> CLIENT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CLIENT_SECRET = new ThreadLocal<>();

    private McpClientContext() { }

    public static void setClient(String clientId, String clientSecret) {
        CLIENT_ID.set(clientId);
        CLIENT_SECRET.set(clientSecret);
    }

    public static String getClientId() { return CLIENT_ID.get(); }

    public static String getClientSecret() { return CLIENT_SECRET.get(); }

    public static void clear() { CLIENT_ID.remove(); CLIENT_SECRET.remove(); }
}

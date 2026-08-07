package com.xander.lab.integration.mcp.security;

import java.util.Set;

/** Request-local OAuth principal for one authenticated remote MCP call. */
public final class McpOAuthRequestContext {
    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    private McpOAuthRequestContext() { }

    public static void set(Long userId, String clientId, Set<String> scopes) {
        CURRENT.set(new Principal(userId, clientId, Set.copyOf(scopes)));
    }

    public static Principal current() { return CURRENT.get(); }

    public static Long userId() { return CURRENT.get() == null ? null : CURRENT.get().userId(); }

    public static boolean hasScope(String scope) {
        Principal principal = CURRENT.get();
        return principal != null && principal.scopes().contains(scope);
    }

    public static void requireScope(String scope) {
        if (!hasScope(scope)) throw new McpInsufficientScopeException(scope);
    }

    public static void clear() { CURRENT.remove(); }

    public record Principal(Long userId, String clientId, Set<String> scopes) { }
}

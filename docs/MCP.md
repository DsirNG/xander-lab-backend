# MCP Client Authorization

Both MCP endpoints require three headers from an external desktop client:

```text
X-MCP-Server-Token: configured server token
X-MCP-Client-Id: stable opaque installation identifier (16-128 characters)
X-MCP-Client-Secret: random per-installation secret (32-256 URL-safe characters)
```

The first `start_login` call returns a one-time browser URL. The user logs in on
that page and the server stores an encrypted refresh-token pair for that client
installation. Subsequent calls resolve the bound Xander Lab user from the
client ID and secret. CSDN publication then uses only that user's CSDN
credential.

Never share a client secret. To revoke a desktop installation, mark its
`mcp_client_authorization` row as `REVOKED` until a dedicated management API is
added.

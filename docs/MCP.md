# Remote MCP Authorization

Xander Lab exposes standard Streamable HTTP MCP resources at `/api/mcp`,
`/api/mcp/csdn`, and `/api/mcp/dual`. An external MCP host needs only an
endpoint URL. It discovers OAuth requirements from the HTTP 401 challenge and
the `/.well-known/oauth-protected-resource` metadata endpoint.

The OAuth server implements dynamic public-client registration and Authorization
Code with mandatory S256 PKCE. After the user logs into Xander Lab and approves
scopes in the profile consent page, the host receives an opaque bearer token.
All MCP requests use:

```text
Authorization: Bearer <access_token>
```

No global MCP server token, user-visible client secret, or MCP-specific password
form exists. Users can review and revoke approved MCP clients from their profile.
See `MCP_OAUTH_DESIGN.md` for scopes, persistence, and rollout details.

# Remote MCP OAuth Design

## 1. Goal

DinQorAI must expose interoperable remote MCP servers. A user can give an
external MCP client one endpoint URL, and the client can discover tools and
complete user authorization without knowing DinQorAI source code, receiving
a shared server secret, or manually creating a client secret.

The server keeps three independent product capabilities:

| Capability | Endpoint | Ownership |
| --- | --- | --- |
| Blog MCP | `/api/mcp` | Owned blog CRUD and publication |
| CSDN MCP | `/api/mcp/csdn` | CSDN account authorization and owned-post sync |
| Dual publish MCP | `/api/mcp/dual` | Composition of the two capabilities |
| Juejin MCP | `/api/mcp/juejin` | Juejin account status and owned-post publish |

The dual server orchestrates the blog and CSDN application services. It never
reads CSDN credentials. CSDN credentials remain encrypted and scoped by the
DinQorAI user ID in `integration.mcp.csdn`. The Juejin module stores its own
per-user encrypted credentials in `integration.mcp.juejin` and never touches
CSDN credentials.

## 2. Non-goals

- Do not merge the blog, CSDN, and dual business modules into one module.
- Do not expose a global `MCP_SERVER_TOKEN` to end users or third-party clients.
- Do not ask users for a DinQorAI password in an MCP-specific HTML page.
- Do not make an MCP token a replacement for the existing DinQorAI web JWT.

## 3. External Contract

Each endpoint uses Streamable HTTP and accepts the standard bearer header:

```text
Authorization: Bearer <opaque_access_token>
Content-Type: application/json
Accept: application/json, text/event-stream
```

An unauthenticated MCP request returns HTTP 401 with a `WWW-Authenticate`
header that points to protected-resource metadata. It must not return the
current `MCP_CLIENT_AUTH_REQUIRED` JSON body and must not require
`X-MCP-Server-Token`, `X-MCP-Client-Id`, or `X-MCP-Client-Secret`.

The public discovery surface is:

| Route | Purpose |
| --- | --- |
| `/.well-known/oauth-protected-resource` | Identifies the MCP resources and their authorization server |
| `/.well-known/oauth-authorization-server` | OAuth authorization server metadata |
| `POST /api/mcp/oauth/register` | Dynamic client registration for MCP hosts without a pre-registered client ID |
| `GET /api/mcp/oauth/authorize` | Starts Authorization Code + PKCE consent flow |
| `POST /api/mcp/oauth/token` | Exchanges an authorization code and rotates refresh tokens |
| `POST /api/mcp/oauth/revoke` | Revokes one access or refresh token |

Discovery metadata declares the supported scopes and requires `S256` PKCE.
The authorization server supports public clients only; no client secret is
issued or required. Dynamic registration validates HTTPS redirect URIs,
rate-limits registrations, and persists the client ID and redirect URI set.

## 4. Authorization Flow

1. An MCP host receives one of the endpoint URLs from the user.
2. The host calls the endpoint. The 401 challenge directs it to protected
   resource metadata and OAuth authorization-server metadata.
3. The host dynamically registers when it has no known client ID, then sends
   the user to `/api/mcp/oauth/authorize` with `response_type=code`,
   `client_id`, `redirect_uri`, `scope`, `state`, `code_challenge`, and
   `code_challenge_method=S256`.
4. The authorization endpoint redirects to a DinQorAI frontend consent page.
   That page reuses the normal login experience. It never collects passwords
   itself.
5. The logged-in user approves the requested scopes. The frontend calls a
   protected approval API using the existing DinQorAI web JWT.
6. The backend issues a short-lived, single-use authorization code and redirects
   the browser to the registered client redirect URI.
7. The MCP host exchanges the code and PKCE verifier for an opaque access token
   and rotating refresh token.
8. Every MCP request resolves the DinQorAI user from the access-token grant.
   Tool discovery uses `tools/list`; tool behavior is learned from schemas and
   descriptions, not from source code.

For CSDN work, the MCP host first receives DinQorAI OAuth authorization. The
user's CSDN account is then checked or bound through CSDN MCP's existing QR
authorization flow. A token can never select another user's CSDN credential.
The Juejin MCP follows the same pattern: the Juejin credential is bound per
DinQorAI user (QR authorization handoff planned) and publish tools always
resolve the credential of the calling user.

## 5. Scopes

Scopes are capability-based and granted per external OAuth client:

| Scope | Allows |
| --- | --- |
| `blog:read` | Read the current user's posts and post detail |
| `blog:write` | Create, update, publish, unpublish, and delete owned posts |
| `csdn:status` | Read the current user's CSDN authorization state |
| `csdn:authorize` | Start CSDN QR authorization for the current user |
| `csdn:publish` | Synchronize owned posts to CSDN |
| `publish:dual` | Publish an article to the local blog and then CSDN |
| `juejin:status` | Read the current user's Juejin authorization state |
| `juejin:publish` | Publish articles or owned posts to Juejin |

`/api/mcp` accepts the blog scopes, `/api/mcp/csdn` accepts CSDN scopes,
`/api/mcp/juejin` accepts the Juejin scopes, and `/api/mcp/dual` requires
`publish:dual` plus the effective blog and CSDN write permissions. The dual
endpoint may request these as one consent bundle but the token persists the
individual scopes for auditability.

## 6. Persistence and Security

Replace `mcp_client_authorization` with OAuth-owned records:

| Table | Key fields | Purpose |
| --- | --- | --- |
| `mcp_oauth_client` | `client_id`, registered redirect URIs, metadata, status | Registered public MCP clients |
| `mcp_oauth_consent` | `user_id`, `client_id`, scopes, granted/revoked time | User consent and revocation |
| `mcp_oauth_grant` | `user_id`, `client_id`, scopes, token-family ID, expiry, revoked time | Durable authorization grant |
| `mcp_oauth_token` | hashed token, grant ID, type, expiry, used/revoked time | Opaque access/refresh token lifecycle |

Authorization codes are stored as hashes in Redis for five minutes and deleted
atomically on exchange. Access tokens are short-lived (for example 60 minutes).
Refresh tokens rotate on every use; replay of a rotated token revokes the whole
token family. Raw authorization codes, access tokens, refresh tokens, and
authorization headers are never logged or stored plaintext.

The token filter creates an immutable request context containing `userId`,
`clientId`, and granted scopes. Application services receive only the user ID
and requested operation; they keep ownership checks as a second line of defense.

## 7. Backend Boundaries

`integration.mcp.security` owns OAuth metadata, registration, authorization
transactions, token issuance, bearer-token validation, consent, and MCP request
context. It may call `identity` only to validate the already logged-in web JWT.

`integration.mcp.blog`, `integration.mcp.csdn`, and `integration.mcp.dual` own
their transport registration and tool schemas. They declare required scopes on
each tool and call their application services. They do not parse tokens, query
OAuth tables, or create authorization URLs.

The legacy `McpClientAuthorizationService`, `McpClientAuthorization` entity,
mapper, `McpAuthorizationProperties`, custom client headers, and browser HTML
handoff are removed. The current three MCP endpoint paths remain unchanged for
compatibility.

## 8. Frontend Contract

The profile page shows the three endpoint URLs with copy controls. It also gets
two additional areas:

- **Authorized MCP clients**: client name, scopes, authorized time, last used
  time, and a revoke action.
- **OAuth consent page**: requested client identity, endpoint, scopes, and
  approve/reject actions after normal DinQorAI login.

The page never displays a global server token, generated client secret, CSDN
cookies, or any OAuth token.

## 9. Migration Plan

1. Add OAuth data schema, configuration, metadata endpoints, DCR, PKCE, token
   lifecycle, rate limits, and security tests. Keep current MCP endpoints
   disabled in production while this is built.
2. Replace all three custom MCP filters with the common bearer-token filter.
   Add tool-level scope checks and ownership regression tests.
3. Replace the HTML login handoff with the frontend OAuth consent route and add
   client-management UI.
4. Deploy with MCP OAuth enabled. Verify an unconfigured external MCP client
   can add only the endpoint URL, complete browser consent, call `tools/list`,
   and use only the approved user's resources.
5. After existing private clients are migrated, archive the legacy
   `mcp_client_authorization` data according to retention policy and remove its
   obsolete schema script from deployment bundles.

## 10. Acceptance Criteria

- A fresh compatible MCP host connects using only `https://api.dinqor.cn/api/mcp`.
- The host discovers authorization requirements without proprietary headers.
- A user can log in, approve scopes, and revoke a client from the profile page.
- `tools/list` describes tools without source-code knowledge.
- Calls using another user's token, a revoked token, an expired token, a token
  without scope, or a token for a different resource return the correct OAuth
  error and cannot access content or CSDN credentials.
- Blog, CSDN, and dual MCP remain physically separate modules; dual publication
  continues to report partial CSDN failure without losing the local post.

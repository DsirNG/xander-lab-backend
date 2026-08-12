# Backend Architecture

The backend remains a modular monolith. Business capabilities are grouped by
feature; deployment and database topology stay unchanged.

## Business Modules

- `identity` (migrated): owns users, login, token refresh, JWT validation and request authorization.
- `blogagent` (migrated): generates and revises content, stores evidence and versions.
- `publishing` (migrated): owns blog posts, categories, tags, view records, publication commands, generic publication contracts, durable publication records, and blog media uploads.
- `automation` (migrated): owns email reminders, recurring content plans (`blog_content_plan`), durable plan runs (`blog_plan_run`), the plan poller with atomic claiming and stale-claim recovery, and user notifications (`blog_notification`). Runs a headless pipeline: generation via `blogagent`, a fail-closed compliance gate, local/CSDN publication via `publishing`/`integration`, then reschedules the plan. Per-user SSE pushes live updates; the notification table is the durable fallback for offline users.
- `integration` (migrated): owns MCP protocol adapters, OSS/file/mail delivery adapters, and vendor-specific integrations such as the complete CSDN MCP flow.
- `studio` (migrated): owns FlowCraft projects, canvases and snapshots, plus the image-to-3D task pipeline and model client.
- `components` (migrated): owns the UI component library catalog, detail pages, and community-shared submissions.
- `bloghtml` (new): owns short-lived blog HTML preview sessions. Session creation (`POST /api/blog-html/previews`) is public with per-IP Redis fixed-window rate limiting; code is stored in Redis with a TTL and served as a standalone sandbox page (`GET /blog-html/{id}/raw`) outside `/api` so iframes load it without auth, CORS or cookies. The page runs on an independent origin (per-session subdomain in production, a different port locally) and carries a strict CSP with `frame-ancestors` restricting embedders; this is the preview isolation pattern used by ChatGPT for its artifact pages.

## Module Documentation

Each business module has a root `package-info.java` that defines ownership and
allowed dependencies. Its `api`, `application`, `domain`, `infrastructure`, and
optional `security` package descriptions explain the internal roles. Public
business types require responsibility-oriented JavaDoc; state transitions,
authorization, idempotency, retries and external side effects require method or
block comments when they are not obvious from the signature.

## Dependency Direction

API adapters call application services. Application services coordinate domain
state through infrastructure adapters. Scheduling and publishing may invoke the
blog-agent application API, but the blog agent must not own schedules or
platform credentials.

`automation` consumes `publishing` only through application services
(`PublicationService` for publishing, `BlogService` for reading back titles of
published posts) and never through publishing mappers. `automation` owns
schedules, runs, and notifications; `publishing` owns articles.

The migration is incremental. Existing public HTTP routes, tables and runtime
configuration must remain compatible during package moves.

`integration.mcp.security` owns OAuth 2.1 discovery, PKCE authorization,
bearer-token validation, consent, and MCP request identity. It is distinct from
both browser JWT sessions and CSDN platform credentials. The `blog`, `csdn`,
and `dual` MCP modules own only their transport contracts and application flows.

## Migration Order

The migration is complete. Business code is grouped by capability and the
global `controller`, `service`, `entity`, `mapper`, and `dto` packages are
empty except for shared infrastructure:

1. `identity` and `blogagent` establish the feature-first package pattern.
2. Blog posts, categories, tags, view records, and media moved into `publishing`; the publication port and local adapter are in place.
3. MCP, CSDN, model, OSS, file, and mail adapters moved into `integration`.
4. Recurring content plans, durable runs, and the daily trigger pipeline live under `automation`; the poller claims due plans atomically and recovers stale claims so multiple instances cannot double-execute.
5. FlowCraft and Img2Three live under `studio`; the UI component library lives under `components`.
6. Shared cross-module contracts (`Result`, `PageData`, `HealthController`) live under `common`.

## Blog HTML Preview Deployment

Production serves preview pages on a dedicated wildcard domain so every session
is an independent origin. Example nginx server block (DNS must map
`*.blogHTML.xander.dsircity.top` to the host):

```nginx
server {
    listen 443 ssl;
    server_name ~^(?<sid>[0-9a-f]{16})\.blogHTML\.xander\.dsircity\.top$;
    # existing TLS config ...

    location /raw {
        proxy_pass http://127.0.0.1:30002/blog-html/$sid/raw;
        proxy_set_header Host $host;
    }
}
```

The blog site must allow embedding the preview origin, e.g. CSP
`frame-src 'self' https://*.blogHTML.xander.dsircity.top`; the sandbox page
itself restricts embedders via its own `frame-ancestors` header
(`BLOG_HTML_FRAME_ANCESTORS`).

# Backend Architecture

The backend remains a modular monolith. Business capabilities are grouped by
feature; deployment and database topology stay unchanged.

## Business Modules

- `identity` (migrated): owns users, login, token refresh, JWT validation and request authorization.
- `agent` (new): owns durable orchestration conversations, messages, replayable SSE events, model decisions, and the registry of user-scoped tools. It does not own the business state created by a tool.
- `blogagent` (migrated): generates and revises content, stores evidence and versions.
- `publishing` (migrated): owns blog posts, categories, tags, view records, publication commands, generic publication contracts, durable publication records, and blog media uploads.
- `automation` (migrated): owns email reminders, recurring content plans (`blog_content_plan`), durable plan runs (`blog_plan_run`), the plan poller with atomic claiming and stale-claim recovery, and user notifications (`blog_notification`). Runs a headless pipeline: generation via `blogagent`, a fail-closed compliance gate, local/CSDN publication via `publishing`/`integration`, then reschedules the plan. Per-user SSE pushes live updates; the notification table is the durable fallback for offline users.
- `integration` (migrated): owns MCP protocol adapters, OSS/file/mail delivery adapters, and vendor-specific integrations such as the complete CSDN MCP flow and the Juejin MCP flow.
- `studio` (migrated): owns FlowCraft projects, canvases and snapshots, plus the image-to-3D task pipeline and model client.
- `components` (migrated): owns the UI component library catalog, detail pages, and community-shared submissions.
- `bloghtml` (new): owns short-lived blog HTML preview sessions. Session creation (`POST /api/blog-html/previews`) is public with per-IP Redis fixed-window rate limiting; code is stored in Redis with a TTL and served as a standalone sandbox page (`GET /blog-html/{id}/raw`) outside `/api` so iframes load it without auth, CORS or cookies. The page runs on an independent origin (per-session subdomain in production, a different port locally) and carries a strict CSP with `frame-ancestors` restricting embedders; this is the preview isolation pattern used by ChatGPT for its artifact pages.
- `billing` (new): owns the points account (`sys_user_points`), the append-only points ledger (`points_ledger`) with a unique `(biz_type, biz_key)` idempotency key, priced model-usage charges, flat-price charges, balance pre-checks, and the `/api/points` endpoints. It must not own generation tasks, conversations, articles, reminders, or publications; feature modules call it only through `BillingService` ports and never through its mappers.
- `admin` (new): owns the backend admin console. It manages model providers (`model_provider`, AES-GCM encrypted API keys via `MODEL_PROVIDER_ENCRYPTION_KEY`), per-feature model routing (`feature_model_config` with primary + fallback endpoints), and exposes the read-side API for user bans; it never writes users or login sessions itself. `ModelGateway` is the shared contract that model clients consult for endpoint candidates; when the gateway is disabled (default), clients fall back to environment variables so existing behavior is preserved, but once the gateway is enabled every feature is driven solely by `feature_model_config` — an unconfigured feature fails with a clear "not configured" business error (HTTP 400) instead of falling back. User ban/enable mutations go through the `identity` application service (`UserAdminService`), which owns session revocation; `admin` owns neither identity tables nor Redis login keys.

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

`agent` invokes business capabilities only through `AgentTool` adapters. The
`blog_generate` adapter calls the `blogagent` application service and returns a
durable blog task ID; ownership of the generated task, sources, versions, and
publication hand-off remains in `blogagent`. Additional adapters delegate to
the owning modules without duplicating their state: `image_generate` renders an
illustration through the blog-agent image client and persists it via
`publishing` media storage; `publish_post` hands a task or post to the
`publishing`/`integration` sync services and charges the platform flat rate;
`query_posts` reads the user's own posts through `publishing`; `schedule_plan`
and `email_reminder` create durable records in `automation`. Agent tools never
own schedules, platform credentials, or publication records, and they must not
rely on a request thread context: every tool receives an explicit user id, and
tools that cross into `publishing`/`automation` services establish the
`UserContext` explicitly when the underlying service still reads it from the
thread. The orchestration UI may link to a tool result for review, but the blog
generation workflow is not a second agent or a source of orchestration
conversation state.

`automation` consumes `publishing` only through application services
(`PublicationService` for publishing, `BlogService` for reading back titles of
published posts) and never through publishing mappers. `automation` owns
schedules, runs, and notifications; `publishing` owns articles.

The migration is incremental. Existing public HTTP routes, tables and runtime
configuration must remain compatible during package moves.

`integration.mcp.security` owns OAuth 2.1 discovery, PKCE authorization,
bearer-token validation, consent, and MCP request identity. It is distinct from
both browser JWT sessions and platform credentials (CSDN, Juejin). The `blog`,
`csdn`, `dual`, and `juejin` MCP modules own only their transport contracts and
application flows. The juejin module also keeps a globally-shared category/tag
catalog (`juejin_category` / `juejin_tag`) synced from `tag_api` by
`JuejinCatalogService`; the publisher resolves local category/tag names to
Juejin-side IDs against it and falls back to defaults when unmatched.

`billing` is charge-on-success only: model clients return business payloads
together with token usage (`ModelCallResult`), and the owning application
service records the charge at its success sentinel (durable ready/failed
transition, successful publish, or completed send) with a unique biz key.
There is no refund flow because a charge is never recorded before success.
Balance pre-checks (`ensureAvailable`) sit at the start of a new consumption
request and fail fast with HTTP 402 until the user obtains points. All billing
I/O is app-service-scoped; `identity` grants the registration bonus through
`BillingService.grantRegister` and must not touch ledger tables.

`admin` routes live under `/api/admin/**`. `AuthInterceptor` enforces login plus
a JWT `role=ADMIN` claim for the whole prefix (403 for non-admin roles),
making it the single authorization point; unparseable tokens are treated as
logged out (401), never 403. `identity` owns JWT parsing and session
revocation; `admin` reads users only through `UserAdminService` (which bans by
flipping `sys_user.status`, forbids self-bans and ADMIN targets, and revokes
every Redis session of the banned user).

`ModelGateway.candidates(featureKey)` returns an ordered list of
`ModelEndpoint` (base URL, API key, model name). Feature keys are stable
constants (`agent`, `blog_agent`, `blog_agent_image`, `img2three_vision`). The
gateway is disabled by default and requires
`MODEL_PROVIDER_ENCRYPTION_KEY` (Base64 32 bytes) when enabled; the
`admin_model_gateway_migration.sql` marker key is `20260815_admin_model_gateway`
and must be applied before production enablement. When enabled, an unconfigured
feature throws `FeatureModelNotConfiguredException` (HTTP 400, no environment
fallback): configure all four features in the admin console before users rely
on them. Admin API key values are
never returned to clients; views expose only `apiKeyConfigured`.

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

## Agent HTTP and SSE Contract

Authenticated orchestration routes live under `/api/agent/conversations`.
Creating a conversation and reading its snapshot use the shared `Result`
envelope. A turn is delivered by `POST /{id}/messages/stream`; recovery uses
`GET /{id}/events` with `Last-Event-ID` or `afterEventId`. The durable event ID
is the resume cursor and clients must ignore an event ID they have already
applied.

SSE text events (`thought`, `answer`, and incremental answer events) carry
literal text, even when the text happens to be valid JSON. Tool and lifecycle
events (`tool_start`, `tool_progress`, `tool_end`, `tool_error`, `complete`)
carry JSON objects. Every response disables proxy transformation/buffering and
keeps the connection alive with comments; disconnecting a subscriber never
cancels committed agent or blog work.

The blog generation pipeline remains independently durable under
`/api/blog-agent/tasks`, but is exposed to orchestration as the
`blog_generate` tool. Its `stage` and `delta` events are tool progress/output,
not orchestration ownership. A generated `taskId` is the stable hand-off for
review and publication.

Each claimed conversation turn carries a monotonically increasing
`run_version`. Messages, events, terminal transitions, cancellation checks,
stale recovery, and replay all fence on that immutable generation so a delayed
worker cannot write into a newer turn. The worker renews its database lease on
a background interval even while an external model or tool is silent; client
SSE heartbeats are connection liveness only and never own the durable job.

## Agent Database Migration Order

Agent SQL is applied manually after the tables it references. For a fresh
database use this order:

1. `blog_schema.sql` (creates `sys_user` and publishing tables).
2. `blog_media_schema.sql`, then `blog_agent_schema.sql` (the baseline files
   already contain the current media and blog-generation columns/events).
3. `agent_conversation_migration.sql` (orchestration conversations, messages,
   replayable events, and the current `cancel_requested` baseline column).
4. `agent_cancel_request_migration.sql`. This remains in the deployment order
   for upgrades, but checks `information_schema` and is therefore a no-op on a
   fresh baseline instead of adding `cancel_requested` twice.
5. `agent_run_version_migration.sql`. This fences delayed workers and scopes
   resumable events to one execution generation; its guarded column/index
   checks are no-ops after the current fresh baseline.

The older `blog_agent_*_migration.sql` files are upgrade-only deltas for a
database that predates the current `blog_agent_schema.sql`; do not apply those
column-add migrations after the current fresh baseline. Migration markers are
audit records, while the guarded column check is the source of truth for the
cancel-request and execution-generation upgrades.

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

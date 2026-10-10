# Cloudflare Workers + D1 Migration Plan

Status: the seven TypeScript Workers are implemented on the
`cloudflare-workers-d1-migration` branch and verified for local operation.
Java/Spring Boot remains the production implementation until each account has
its D1/OSS configuration, data transfer, and a separately approved cutover.
The local seven-Worker stack and isolated D1 databases are in `workers/`.

## Decisions

- Backend runtime: TypeScript on Cloudflare Workers.
- Relational data: Cloudflare D1, using SQLite-compatible schemas and SQL.
- Redis: use the user's existing Upstash Redis REST database only for shared,
  short-lived authentication state and rate limits. Its URL and token are
  Worker secrets and must not be committed. D1 remains the durable source of
  truth. Upstash is a separate managed service, not a Redis binding included
  with Workers; budget its command quota separately.
- Large objects: Alibaba Cloud OSS. D1 stores object keys and metadata, not file
  bodies.
- Initial deployable count: seven Workers (one API Gateway and six business
  services). Six services own a D1 database; the Gateway is stateless.
- Deployment topology: every Worker is independently deployable to its own
  Cloudflare account. Do not depend on same-account Service Bindings; use
  versioned HTTPS contracts and service-to-service authentication so the same
  topology works when several Workers share an account.
- Studio and Components are out of scope and are not part of the target.
- Nacos is not part of the Worker target. Service endpoints are deployment
  configuration, and service discovery is not performed through database
  access.

## Worker services and ownership

| Worker | Owns | D1 |
|---|---|---|
| API Gateway | Public routing, request IDs, coarse rate limiting, common token verification, and response/error envelope | None |
| Core | Identity, login/session and user profile, admin-managed configuration, points and billing ledger | `core-db` |
| Agent | Conversations, messages, run/step state, tool invocation records, and replayable stream events | `agent-db` |
| Payment | Virtual-payment products, orders, provider notifications, and reconciliation state | `payment-db` |
| Platform Integration | External platform authorization and credential records, catalogs, and provider adapters | `integration-db` |
| Content Automation | Blog posts, taxonomy, publication idempotency records, public/private reads, BlogAgent workflows, schedules, runs, and notifications | `content-db` |
| BlogHTML Preview | Temporary preview sessions and expiry metadata; isolated preview origin and security headers | `preview-db` |

The Gateway does not become the owner of business data. Each business service
checks the caller identity and resource ownership itself, even when the Gateway
has already verified a user token.

## Current migration progress

- The target local Wrangler stack runs Gateway, Core, Agent, Payment, Platform
  Integration, Content Automation, and BlogHTML Preview. Knowledge, Studio, and
  Components are outside the migration target.
- The Gateway routes public traffic using the generated manifest. Studio,
  Components, and internal-only paths are excluded. Its runtime evaluates the
  generated `auth-policy.yaml` rules, verifies Java-compatible access JWTs,
  resolves private callers against Core's active-account contract, enforces
  admin access, handles CORS preflight, and replaces untrusted identity and
  forwarding headers. It propagates safe trace/request IDs and streams upstream
  responses. Its Cloudflare Rate Limiting binding is a coarse per-route limit;
  it is location-local and is not used for exact accounting. Wrangler local
  development simulates the binding, but those local counters are not
  production counters.
- Core now owns Java-compatible password/code login, registration, email
  verification, password reset/change, profile reads/updates, access-token
  validation/revocation, QR login, WeChat mini-program login/binding, and admin
  user list/status routes. HS256/HS384/HS512 access tokens use the configured
  `JWT_SECRET`; logout stores only a SHA-256 token fingerprint, while password
  changes and account status changes increment `auth_version`. Passwords use
  salted, peppered PBKDF2-HMAC-SHA-256 through Web Crypto; old BCrypt accounts
  must reset before password login. WeChat credentials can be managed through
  the admin API and stored encrypted in Core D1. Account linking that
  would move existing data across independently owned D1 databases returns a
  conflict until each owning service supports that migration. The newer
  stateful refresh now uses Core D1 sessions, bounded rotation history,
  AES-GCM successor replay, and family revocation on reuse; Java-compatible
  stateless refresh remains the default. Logout, password changes, and admin
  status changes revoke stateful sessions. Active
  token resolution, account/password use cases, profile updates, and
  administrator user listing/status changes now use Controller → Service →
  Interface → Mapper dependencies, backed by `D1IdentityMapper`; the Core
  composition entry has no direct D1 queries. Billing balance/history, model
  prices, ledger writes, registration grants, and balance transfers now use
  `BillingService` and `D1BillingMapper`. Login, registration, password flows,
  profile, session, QR, WeChat, and Agent-auth handlers now use dedicated
  controllers. QR state uses a Redis mapper; WeChat code exchange, QR images,
  verification mail, and Content notification diagnostics use remote-contract
  mappers. WeChat account-binding rules are in `WechatAccountBindingService`.
  The public Core route inventory was reviewed against its Worker controllers;
  Core/Agent/Content/Payment contract tests cover token, billing, recitation,
  and entitlement boundaries. Model-provider,
  feature-model, WeChat-credential, and
  virtual-pay settings now use their service/repository/mappers; Payment owns
  the live virtual-pay routes while the legacy Core table awaits data transfer
  and retirement. Recitation's 10 public routes now map to Core. Migration
  `0006_recitation.sql` adds its D1-owned materials, attempt metadata, and quiz
  history; material CRUD, filters, archive/restore, and attempt/quiz routes use
  Controller → Service → Interface → Mapper. Audio uploads store bytes in OSS
  and idempotent task metadata in D1. A scheduled worker submits/polls the
  configured DashScope ASR model, stores a deterministic alignment report, and
  advances mastery in an atomic D1 batch. Unit tests cover upload ownership,
  OSS signing, provider payloads, retries and score transitions; live OSS and
  DashScope credentials are not configured for external E2E. The feature stays
  disabled until per-account OSS and `recitation_asr` model configuration is
  supplied. Core's six private Agent-recitation contracts now provide
  owner-scoped material create/search/count/get and durable quiz open/record.
  Grading stores per-question evidence and advances mastery in an atomic D1
  batch; `pnpm smoke:core-recitation` verifies idempotent retries and
  cross-conversation rejection. Agent's `query_knowledge` and
  user-requested `create_knowledge` tools call Core's owner-scoped HTTPS
  contract; `smoke:agent-approvals` covers both with local Core D1. The
  `start_knowledge_quiz` binds Core quiz sessions to the current conversation
  and generates questions from the material through the configured model;
  `record_knowledge_quiz` writes per-question evidence through Core's atomic
  grading contract. `smoke:agent-approvals` checks the generated quiz,
  owner/conversation scope, and persisted attempt. The `emit_quiz` tool now
  normalizes bounded question/option payloads, persists answer cards as
  `kind=quiz`, and emits replayable `quiz` events; ad hoc cards and Core-backed
  quiz session IDs follow the Java contract. `emit_artifact` now validates
  project-relative file paths, bounds each file and total content, selects a
  safe entry file, and persists/emits `kind=artifact` cards. Agent unit tests
  cover both card contracts, and the local Agent smoke checks durable quiz-card
  storage.
- Core migration `0004_identity_billing.sql` adds registration-grant recovery,
  milli-point accounts, an idempotent points ledger, and model prices. Core now
  serves user balance/history/prices, administrator model-price management,
  registration grants, and the private Agent billing contract through a
  dedicated billing mapper. Worker D1
  triggers keep ledger insertion and balance snapshots atomic. Agent text turns
  now precheck Core balance and charge provider-reported usage after a successful
  terminal transition with an idempotent conversation/run key.
  `delegate_subtask` runs a bounded child loop with owner-scoped, read-only query
  tools; each child model call and the parent synthesis call use separate
  idempotent Core billing keys. Migration
  `0005_admin_settings.sql` adds model-provider and
  feature-model administration and encrypted WeChat credentials. It initially
  held virtual-pay settings as well; the live virtual-pay admin routes and
  settings now belong to Payment, while the legacy Core table remains until its
  data has an explicit transfer and retirement step. The
  `ADMIN_MODEL_GATEWAY_ENABLED` switch defaults off; model keys
  require `MODEL_PROVIDER_ENCRYPTION_KEY`, while WeChat and virtual-pay secrets
  require `WECHAT_CREDENTIAL_ENCRYPTION_KEY`. Credential responses expose only
  configured flags. Profile overview now composes Core identity/points with the
  protected Content Automation managed-blog-count contract. Live notification
  diagnostics persist a short-lived Content broadcast and report active SSE
  leases without adding a durable user notification.
- Agent now owns D1 conversation/message/event, tool-approval, and bounded
  long-term-memory tables and
  serves authenticated conversation create/list/detail/history, read-state, pin,
  share, cancellation, versioned personal Skill list/create/archive, model
  turns, SSE streaming, and event replay. Active Skills load into the model
  prompt; the registered `publish_post` tool is exposed only when selected by an
  active Skill. Its canonical arguments and SHA-256 fingerprint are stored in
  D1, and publication cannot begin before an owner decision. Approved calls go
  through Content Automation's protected contract; ambiguous outcomes become
  `UNKNOWN` and are not retried automatically. The Worker verifies Java-issued
  HMAC access tokens and uses protected Core contracts for active-user
  resolution, model candidates, and billing. New execution code follows
  Controller, Service, Interface, and Mapper layers. The `remember` and
  `recall_memory` tools use owner-scoped Agent D1 rows; relevant records are
  injected into future turn context, and per-user storage is capped at 200
  records with least-used eviction. Outbound MCP server configuration now uses
  owner-scoped D1 rows, AES-GCM encrypted request headers, HTTPS/private-address
  checks, and a bounded cached tool catalog. Third-party MCP calls create
  approval snapshots and are never sent until the owner approves; ambiguous
  execution outcomes are recorded `UNKNOWN` without retries. Admin and personal
  list/create/update/delete/probe routes are implemented. MCP is disabled by
  default and needs a stable per-deployment encryption key to enable. `pnpm
  smoke:agent-approvals` uses a local
  mock model to verify memory write/recall/context injection, owner-scoped
  approval/rejection, no publication on rejection, and approved hand-off to
  Content without external provider credentials. Agent's `schedule_plan` and
  `email_reminder` tools now create owner-scoped Content records only after an
  immutable approval; plan pause/resume/edit and reminder pause/resume use the
  same authenticated Content HTTPS contract. Plan cancel/delete and reminder
  delete also require approval. Create retries reuse the approval invocation ID
  as the Content idempotency key; uncertain non-idempotent updates are journaled
  as `UNKNOWN` and never retried. Unit tests cover the approval state, owner and
  service-auth headers, and retry boundary. Agent also serves the legacy
  `/ws/agent` stream by replaying D1 events and polling the active run. `waitUntil`
  keeps model execution running after the client closes its SSE stream. D1 events
  remain replayable after reconnect, while execution is not requeued if the
  Worker invocation exceeds its runtime lifetime.
  Single-file attachment upload validates the legacy extension allowlist and
  20 MiB cap, writes bytes to Aliyun OSS, and stores only owner-scoped metadata
  in Agent D1. Turns accept attachment IDs, reject cross-owner IDs, and refresh
  10-minute OSS V4 URLs before model submission; Responses receives image and
  document blocks while Chat Completions receives image blocks. Upload,
  ownership, multipart parsing, both model payload shapes, and signed URL
  construction have mocked unit coverage. Image attachments additionally call
  Content Automation's authenticated HTTPS media-upload contract, persist the
  owner-scoped Content asset ID in Agent D1, and use compensating cleanup if
  Agent metadata persistence fails. The `image_generate` tool obtains the
  `blog_agent_image` feature candidates from Core, stores the generated bytes
  in Content OSS and D1, and charges provider-reported usage through Core.
  Local tests cover the model, media, billing, and cross-Worker contracts;
  live OSS credentials are not configured, so external OSS E2E remains
  unverified. Responses API providers receive documents as file blocks;
  Chat Completions receives image blocks only, so local document text
  extraction/OCR is not implemented. Attachment-only
  conversation creation now matches the Java
  route's placeholder-title behavior; actual file IDs are supplied with the
  subsequent turn request.
- Payment migration `0001_payment_settings.sql` creates Payment-owned virtual
  payment settings and order tables. The virtual-pay admin settings routes now
  target Payment; AppKey is AES-GCM encrypted with a Payment-specific key and
  never returned. `GET /api/virtual-pay/products` reads the enabled D1 catalog.
  `POST /api/virtual-pay/orders` verifies a one-time WeChat login code against
  the account's bound OpenID through a protected Core contract, stores a pending
  order, and returns both HMAC signatures. Owner-scoped status reads are also
  migrated. Core's `0007_payment_entitlements.sql` applies an entitlement once
  per unique trade event; `0008_payment_entitlement_preserve_tier.sql` preserves
  the highest tier while extending expiry. `POST
  /api/virtual-pay/orders/{outTradeNo}/reconcile` queries WeChat's `order_id`,
  accepts only paid states 2/3/4, checks the paid amount, and grants through
  Core before marking the order delivered. The SHA-1-signed XML delivery
  callback supports URL verification and idempotent retries. Both delivery paths
  validate the Payment order and use the same Core event key. Controller →
  Service → Interface → Mapper is in place for this slice; unit tests cover the
  WeChat request signature and paid states, and the local Gateway smoke covers
  signed callback replay through Core D1. No live WeChat credentials are
  configured for login-code/query-order provider E2E testing.
- Platform Integration migration `0001_platform_credentials.sql` creates the
  per-user CSDN/Juejin credential table. Credentials are AES-GCM encrypted with
  owner/provider authenticated data; status APIs never expose the payload.
  Authenticated service import supports staged credential transfer, and public
  status/disconnect routes use Gateway's trusted user identity. Verification
  mail and Juejin catalog sync use Controller → Service → Interface → Mapper.
  Migration `0002_juejin_catalog.sql` stores categories, hot/global tags, and a
  successful-sync checkpoint. Sync uses the encrypted user's browser cookie and
  CSRF token, validates the API host, caps provider responses and pagination,
  retains 64-bit provider IDs as decimal text, and marks an expired session for
  reauthorization. The public `POST /api/publishing/juejin/catalog/sync` route
  uses Gateway-projected identity and requires that user's credential. Unit coverage includes provider request shape, pagination,
  stale sessions, and internal service-token protection. Its protected internal
  Juejin publication route resolves the category and up to three tags, creates
  a draft, publishes it, preserves long IDs, and marks expired credentials.
  The protected CSDN publisher signs the legacy CSDN API calls, converts
  Markdown with raw HTML and unsafe URLs disabled, uploads remote Markdown
  images, preserves long provider IDs, and marks an expired per-user cookie.
  Content owns the durable publication idempotency ledger. Its internal Agent
  publication route owner-checks the D1 article, reads OSS-backed content,
  calls either Platform publisher, and records a fenced attempt/result in
  Content D1. CSDN daily and 30-second per-user limits are also enforced in
  Content's D1. Repeated success is idempotent and active attempts are rejected.
  Mocked adapter tests cover CSDN signing, HTML handling, session expiry, and
  the Content publication limits; live provider credentials are not configured.
  The local
  `pnpm smoke:platform` exercises encrypted import and owner-scoped status and
  disconnect through Gateway. Public CSDN/Juejin sync routes now take the
  Gateway-projected owner and delegate to Content's owner-checked, fenced
  publication ledger; `find-succeeded` reads remain available to Agent through
  the same ledger. QR authorization uses a D1 FIFO browser-session queue,
  owner-bound encrypted WebDriver session IDs, and a bounded W3C adapter.
  OAuth 2.1 registration, PKCE consent, one-time codes, rotating refresh
  tokens, replay revocation, and scoped Streamable HTTP MCP tools are now
  implemented in Platform Integration. Provider credentials, Selenium, and
  production OAuth origins still require account-specific configuration.
  File upload/read/export use Controller → Service → Interface →
  Mapper, keep bytes in Alibaba OSS, and store owner-scoped metadata in Platform
  D1; Worker tests exercise those paths with an isolated storage adapter.
- Content Automation implements owner-authenticated `POST /api/blog/posts` with
  D1-backed idempotency and D1 storage for bodies up to 256 KiB; larger bodies
  are written to private Alibaba OSS using signed PUT/DELETE requests. Replayed
  keys return the existing article and a changed payload under the same key is
  rejected. The local publication smoke creates a draft through Gateway and
  retries the same key before checking the Platform failure ledger.
  Migration `0005` adds the per-owner idempotency key and request fingerprint.
  Public reads include `GET /api/blog/posts`,
  `GET /api/blog/posts/recent`, `GET /api/blog/categories`,
  `GET /api/blog/tags`, and `GET /api/blog/tags/popular` against D1. Published
  article detail is read from D1 for inline text or from the configured public
  HTTPS OSS content prefix for object-backed text. `POST
  /api/blog/posts/{id}/view` atomically records a view, increments the published
  article count, and suppresses duplicate anonymous visits for 24 hours.
  The protected `POST /internal/agent/publications/{postId}/sync/{platform}`
  route performs the Content → Platform CSDN or Juejin publication flow and stores
  success/failure attempts in Content's D1 with a time-limited fencing token.
  Migration `0004` adds the atomic CSDN per-user cooldown record; the daily
  count uses successful CSDN publication records.
  `pnpm smoke:publication` verifies Gateway article creation, D1 idempotency,
  the local cross-Worker call, and the durable failure record when no provider
  credential is configured. Public blog reads,
  taxonomy, view counting, Core managed-count, and scheduled dedup cleanup now
  use the Content Controller → Service → Interface → Mapper layers. Migration
  `0006_content_notifications.sql` adds owner-scoped notification persistence;
  list, unread count, read acknowledgements, the internal notification-create
  contract, and a cursor-based SSE stream use Controller → Service → Interface
  → Mapper. The local publication smoke exercises notification creation through
  Content and read/acknowledgement through Gateway. Migration
  `0007_blog_content_plans.sql` adds plan and durable run-history tables. Plan
  create/list/detail/update/status/delete, owner-scoped run reads, and timezone-
  aware next-run previews use the same layers; creation is idempotent per owner,
  rate-limited, and checks referenced Core recitation materials through a
  protected HTTPS contract. The publication smoke creates and retries a plan,
  pauses it, checks that Core-owned material cannot be referenced by another
  owner, and reads its next-run preview through Gateway. Scheduled execution
  and manual trigger call Core's protected `blog_agent` model feature, check
  and charge usage, pass review/compliance gates, create local posts, optionally
  publish to CSDN/Juejin, and persist run history and owner notifications in
  Content D1. AI topic-series generation and batch creation of one-shot plans
  use the same protected model feature. Migration 0010_email_reminders.sql adds
  owner-scoped reminders with timezone-aware ONCE/DAILY/WEEKLY/MONTHLY/CUSTOM
  schedules, idempotent creation, pause/resume, soft deletion, and quota checks.
  Content's scheduled handler fences due sends in D1, renders safe email HTML,
  retries provider failures, and charges successful deliveries through Core;
  mail delivery uses Platform's authenticated HTTPS contract and Resend adapter.
  Private article list/detail/edit/status/trash/stats and durable BlogAgent
  task/session routes now use Controller → Service → Interface → Mapper. Large
  updated Markdown remains in OSS. Local Gateway smokes cover article CRUD and
  reminder lifecycle; internal Agent BlogAgent calls are idempotent. The Core
  admin diagnostic now persists a two-minute broadcast event for active Content
  SSE connections, reports the active connection count, and leaves the durable
  user notification list unchanged. Migration `0012_blog_media_assets.sql`
  adds owner-scoped media metadata, user and Agent-image gallery reads, and
  multipart image uploads. Image bytes are written to Aliyun OSS; D1 stores
  only metadata. Protected internal routes support Agent asset registration
  and owner-scoped lookup, Agent attachment upload, generated-image recording,
  and compensating owner-scoped deletion.
- Content D1 migrations own the blog post, category, tag,
  post-tag, publication-record, view-history, view-dedup, and notification
  tables, including the scheduled email-reminder queue, active notification
  stream leases, expiring live-broadcast events, and blog media metadata. Inline
  article bodies are capped at 256 KiB; larger bodies use an OSS object key.
  Migration `0003` adds publication attempt fencing tokens and lease deadlines.
  Expired view-dedup records are removed hourly by the Content Automation
  Worker cron trigger. Gateway strips caller-supplied identity headers and
  projects identity only after validating the JWT and resolving the active
  account with Core. Content's public view-count handler still deduplicates by
  edge IP and stores no user ID.
- BlogHTML Preview implements `POST /api/blog-html/previews`,
  `GET /blog-html/{id}/raw`, and the isolated-host `GET /raw` page path. It
  preserves the Java-compatible 64-bit session ID, 256 KiB UTF-8 limit, 10-per-IP fixed-window
  rate limit, 30-minute expiry, preview-page scripts, CSP, no-store, and nosniff
  headers. Deployed Workers put HTML in private OSS and keep only metadata and a
  keyed rate-limit fingerprint in D1; local Wrangler uses explicit `local-inline`
  mode. An every-15-minute cleanup removes expired OSS objects and D1 metadata.
  Production DNS, OSS credentials/permissions, and the preview-origin rollout
  remain to be configured and verified per Cloudflare account.
- The public route inventory was audited against the seven Worker dispatchers.
  That review found the missing `/ws/agent` stream and public CSDN/Juejin sync
  handlers; both are now implemented and covered by tests. Knowledge, Studio,
  and Components remain excluded. Production D1 data transfer, per-account
  secrets, DNS/origins, and provider/OSS end-to-end checks are still required
  before Java services can be retired or public traffic can be cut over.

`pnpm smoke:core-refresh` applies Core migrations to an isolated local D1 and
exercises actual Worker endpoints for concurrent rotation, replay, reuse
revocation, and logout. The local smoke scripts cover Gateway forwarding and response envelopes,
Agent conversations and owner approvals, Payment settings/catalog and Core
entitlement idempotency, Platform credentials, Content publication idempotency,
and BlogHTML Preview creation/raw rendering. The package test suite also covers
Gateway policy, D1 migrations, route envelopes, OSS signing, and provider request
shapes. An actual OSS-backed request still needs an account/domain-specific
integration check.
Before public-traffic cutover, provision each account's D1 databases and
service tokens, import existing data with checksums, configure DNS/origins and
OSS permissions, and run provider-specific integration checks.

### Service interactions

- Core issues user tokens. The Gateway and business Workers verify the token;
  downstream authorization does not rely solely on the Gateway.
- Agent calls Content Automation through its protected API only after the
  `publish_post` request has been approved by the owner. The Agent records its
  approval and invocation state in Agent D1 and never writes Content's D1.
- Platform Integration owns provider-specific authorization and transport.
  Content owns the blog post and its publication state; integration results are
  returned through a documented service contract.
- Payment verifies provider notifications and owns payment order state. After a
  confirmed payment it sends an idempotent event/request to Core, which owns the
  points or entitlement ledger. There is no cross-D1 transaction.
- Preview stores large input/output objects in OSS and keeps only ownership,
  object key, size, checksum, type, and lifecycle metadata in D1.
- Cross-service retries use idempotency keys and durable state transitions.
  Network calls to another Worker or model/provider are never part of a D1
  transaction.

## Alibaba OSS object flow

1. The client asks the owning Worker to initialize an upload. The Worker
   authenticates the user, validates the folder, type, declared size, and
   idempotency key, starts an OSS multipart upload, and stores its session
   metadata in D1.
2. The owning service forwards large file writes to OSS and records object
   metadata in its D1; file bodies never enter D1.
3. Downloads use a short-lived signed URL or an authenticated delivery path.

Long-lived OSS credentials never go to the browser. Bucket CORS is limited to
the application's origins, and per-service OSS permissions are scoped to the
needed object prefixes and operations. Workers read the Aliyun credentials from
the `ALIYUN_OSS_ACCESS_KEY_ID` and `ALIYUN_OSS_ACCESS_KEY_SECRET` Secret bindings
on the Worker that owns the objects; credentials are configured independently
for every deployed Worker/account. The region, bucket, and endpoint are non-secret
Worker variables. Content Automation supports signed V4 reads for private
article objects and retains public HTTPS reads when no credentials are set.

## D1 and free-tier constraints

As checked on 2026-10-10, Cloudflare documents these Workers Free limits:

- Workers: 100,000 requests per account per day and 10 ms CPU per invocation.
- D1: 10 databases per account, 500 MB per database, 5 GB total storage per
  account, 5 million rows read per day, and 100,000 rows written per day.
- D1 row/blob size is limited to 2 MB. Files and large generated HTML belong in
  OSS, not D1.
- A D1 instance processes queries one at a time. Global read replicas can
  distribute reads, but are asynchronous and writes still go to the primary.

These quotas are per account, not per Worker. If every public request enters
one Gateway account, that account's request quota remains the front-door limit.
Multiple accounts can spread service workloads only when traffic reaches those
services; they do not remove a single Gateway's limit. Recheck Cloudflare limits
and OSS cost/region assumptions before production cutover.

### Identity CPU constraint

The Java identity provider currently stores BCrypt hashes. Existing users have
approved a forced password reset, so the Worker replaces them with salted,
peppered PBKDF2-HMAC-SHA-256 hashes generated through Web Crypto instead of
attempting to execute BCrypt. Cloudflare's production Web Crypto runtime caps
PBKDF2 at 100,000 iterations; that is below common password-hashing guidance,
so the Worker also requires a separate 32-byte `AUTH_PASSWORD_PEPPER` secret.
Free-plan invocations still have a 10 ms CPU limit, and local development does
not enforce deployed CPU limits. The pepper is defense in depth; it does not
make 100,000 iterations equivalent to OWASP's 600,000-iteration PBKDF2 baseline.
Before production cutover, validate CPU use on Cloudflare and make an explicit
security decision; if the free limit or accepted hash strength is insufficient,
move password hashing to a paid Worker or a separate identity service. Also
configure the email reset path and the same pepper for every Core deployment
that shares users. Existing BCrypt hashes intentionally do not verify in the
new Worker; the reset flow is the migration path.

For local password/code flows, provide `AUTH_PASSWORD_PEPPER` (at least 32
bytes), `UPSTASH_REDIS_REST_URL`, `UPSTASH_REDIS_REST_TOKEN`, and
`RESEND_API_KEY` in the environment before `pnpm dev`. The local stack script
forwards them through generated, ignored `.dev.vars` files and removes those
files when the stack stops; it does not persist a generated password pepper,
because local D1 data must keep using the same pepper across restarts.

## Migration sequence

1. Inventory public APIs, authentication rules, scheduled work, and SQL/table
   ownership for the six D1-owning services. The Gateway route manifest is
   generated from `docs/route-inventory.yaml`; internal-only routes and the
   excluded Studio/Components domains are omitted.
2. Define versioned TypeScript service contracts, common error shape, token
   verification, service authentication, idempotency, and OSS upload signing.
3. Create one independently deployable Worker package and D1 migration set per
   service. Wrangler migrations run against the owning D1; `migration-runner`
   is not a runtime service.
4. Convert MySQL schema and MyBatis persistence service by service. Validate
   SQLite behavior, query/index costs, data import, and ownership before
   routing production traffic.
5. Migrate and cut over one service at a time behind compatible Gateway routes;
   retain a rollback path until data and behavior are verified.

The initial implementation must preserve the current public routes and response
contracts unless a separately documented API migration changes them.

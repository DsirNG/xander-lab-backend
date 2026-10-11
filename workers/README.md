# DinQorAI Cloudflare Workers

This workspace is the actively maintained Cloudflare Workers reconstruction
of the backend. The Java/Spring Boot version is no longer maintained and is
retained as historical reference only. New backend features and fixes target
Workers; this maintenance status does not imply that every migration or
production deployment step has been completed.

## Start locally

From this directory:

```powershell
pnpm install
pnpm generate:routes
pnpm dev
```

`pnpm dev` applies pending local D1 migrations and starts Wrangler's local
runtime for the seven target Workers. It generates temporary service tokens and a
local JWT secret in
ignored `.dev.vars` files and removes files it created when stopped with
Ctrl+C. Each Worker exposes `/healthz` and `/readyz`.
With the stack running, `pnpm smoke:agent` creates and removes a temporary local
identity and verifies the Agent Gateway conversation CRUD/share path without
calling a model provider. `pnpm smoke:payment` creates and removes a temporary
local admin identity, then checks that Gateway routes virtual-pay settings and
product reads to Payment, applies a signed WeChat delivery callback twice, and
checks the owner-scoped order plus Core's one-time entitlement ledger.
With the stack running, `pnpm smoke:preview` checks Gateway creation, Preview
D1 metadata, the public raw page, and its CSP headers. `pnpm smoke:publication`
creates and removes a local
test user and draft through the Gateway, retries the same idempotency key,
calls Content Automation's internal publication route through Platform
Integration, and verifies the failed-attempt record in Content D1 when the
synthetic user has no Juejin credential.
The Gateway reads its route and access-policy manifests from the Java route
inventory and `docs/auth-policy.yaml`. It verifies Java-compatible access JWTs,
asks Core to confirm the account is active before forwarding a private request,
enforces the ADMIN policy, strips caller-supplied identity/forwarding headers,
and adds trusted identity, service-authentication, and correlation headers.
It supports HTTPS service URLs across Cloudflare accounts and streams upstream
responses without buffering. CORS defaults to `https://dinqor.cn`; set
`CORS_ALLOWED_ORIGINS` to a comma-separated origin list for each deployment.
The Gateway's Cloudflare Rate Limiting binding applies a coarse limit of 120
requests per route and actor per minute. It uses the verified user ID when
available and the Cloudflare edge IP for anonymous traffic. This limit is
eventually consistent and location-local, so it is not used for billing or
other exact accounting. Wrangler's local development runtime simulates the
binding; local counters do not represent production Cloudflare locations.
The Workers do not depend on Redis; durable application state belongs to the
owning service's D1 database.
For local Agent-to-Core and Agent-to-Content calls, the runner injects loopback
HTTPS-contract URLs and starts Core and Content before Agent. Production calls
use HTTPS URLs and service tokens, so the services can be deployed to different
Cloudflare accounts without Worker Service Bindings.
Content Automation serves public blog listing/recent and category/tag reads,
owner-scoped article creation, idempotent external publication, and view counts
from its own D1. Article detail reads inline text from D1 or fetches an OSS-backed
body through the configured public HTTPS base URL. A scheduled cleanup expires
view-dedup records. Owner-scoped notifications support list, unread count,
read acknowledgements, internal creation, and cursor-based SSE replay from
Content D1. Private article management and status/stat routes are owner-scoped,
and large article bodies remain in OSS. The Core admin diagnostic persists a
two-minute live-broadcast event for active Content SSE connections and reports
their leased connection count without adding a durable user notification.
The private media library stores owner metadata in Content D1 and uploads image
bytes up to 20 MiB to Aliyun OSS under `CONTENT_MEDIA_OSS_OBJECT_PREFIX` (default
`photos/blog`). New images are served through Content's public media proxy at
`CONTENT_MEDIA_PUBLIC_BASE_URL`; the OSS bucket can stay private. Existing image
URLs remain as stored. For this account migration, new objects use
`ALIYUN_OSS_BUCKET=dinqor`, while `ALIYUN_OSS_LEGACY_BUCKET=food-cinder` lets
workers read and clean up older objects. Local `pnpm dev` points the media URL
to its local Content Worker when OSS is configured.
Scheduled plan CRUD, owner-scoped run-history reads, and timezone-aware next-run
preview use a separate Content D1 schema; referenced Core
recitation material IDs are checked through `CORE_SERVICE_URL` and the protected
`CORE_SERVICE_TOKEN`. Blog plans also support Core-backed topic-series
generation, batch creation of one-shot schedules, scheduled and manual model
execution, review and compliance gates, local article creation, optional CSDN/
Juejin publication, usage billing, run history, and owner notifications. The
durable BlogAgent task/session API and email reminders use D1 and the same
Controller → Service → Interface → Mapper structure. Configure `MAIL_FROM`;
Content sends due reminders through Platform's authenticated Resend contract
and charges successful sends through Core. Knowledge, Studio, and
Components are outside this migration target.

Core serves password/code login, registration, email verification, password
reset/change, profile updates, access-token validation/revocation, QR login,
WeChat mini-program login/binding, administrator user listing/status, model
provider and feature-model configuration, and WeChat credential routes. It also
serves personal recitation material CRUD, filtered lists, archive/restore,
attempt uploads and history from its own D1 database. When explicitly enabled,
audio bytes go to Aliyun OSS and the scheduled DashScope ASR worker stores
transcripts, alignment reports, and mastery updates in D1; audio bytes never
enter D1. Configure OSS access and the `recitation_asr` model feature before
setting `RECITATION_ENABLED=true`. Model-provider management defaults off; enable it with
`ADMIN_MODEL_GATEWAY_ENABLED=true` and configure the `MODEL_PROVIDER_ENCRYPTION_KEY`
secret. Admin-managed WeChat credentials use the
`WECHAT_CREDENTIAL_ENCRYPTION_KEY` secret. Both Core keys must be Base64-encoded
32-byte AES keys and remain stable while encrypted values are stored. The local
runner forwards optional legacy WeChat `WECHAT_MINIAPP_APP_ID` and
`WECHAT_MINIAPP_APP_SECRET` shell variables without writing them to tracked
files when no D1-managed credential is set. Cross-account WeChat linking
returns a conflict when completing it would require moving records in another
Worker's D1; those services remain outside this migration slice.

Core owns points accounts, registration grants, the idempotent points ledger,
public balance/history/price reads, administrator model-price writes, and the
private `/internal/agent/billing/*` contract. Balances and prices use
milli-points. Registration grants default to 2,000,000 milli-points. Agent text
turns now precheck balance through Core and submit provider-reported token usage
after a successful turn using a deterministic idempotency key. The bounded
`delegate_subtask` child loop charges each model subcall and the parent synthesis
with distinct idempotency keys.

Core also exposes Agent-only material create/search/count/get and quiz open/record
contracts under `/internal/agent/recitation/*`. They use Core's recitation D1
tables, scope reads and writes by owner, bind quiz sessions to a conversation,
and write score evidence plus mastery changes in an atomic D1 batch. The local
smoke verifies ownership, scoring, retries, and cross-conversation rejection
with `pnpm smoke:core-recitation`. Agent now has owner-scoped
`query_knowledge` and user-requested `create_knowledge` tools over Core's
protected HTTPS contract; `pnpm smoke:agent-approvals` verifies both through
the local Gateway and D1. `start_knowledge_quiz` opens a Core-owned session
bound to the current conversation, then asks the configured model to generate
questions from the returned material; `record_knowledge_quiz` stores per-question
  answers and scores through Core's atomic grading contract. The same local smoke
  checks the generated session, conversation ownership, and D1 attempt. Structured
  quiz cards are persisted as replayable Agent messages/events; multi-file artifact
  cards validate paths and content limits before persistence. The local Agent
  smoke verifies both card types through Worker and D1.

Core also accepts owner-authenticated recitation audio uploads when
`RECITATION_ENABLED=true`. It stores recordings in Aliyun OSS, stores attempt
and provider state in D1, runs a scheduled DashScope ASR queue, and atomically
records deterministic transcript alignment and mastery updates. Configure
Core's OSS secrets and an enabled `recitation_asr` DashScope model before
turning the feature on. The default remains disabled.

Core and Agent verify Java-compatible access tokens;
production `JWT_SECRET` must match the Java identity service's secret. The
local runner creates one ephemeral secret shared by these Workers; local
authenticated requests need a token signed with that local value. POST
`/api/auth/refresh` uses Java-compatible stateless refresh JWTs while
`AUTH_REFRESH_PROTOCOL_ENABLED=false`. Setting it to `true` enables D1-backed
opaque refresh sessions with 25-second idempotent replay, 24-hour reuse
detection, and AES-GCM encrypted successor tokens. Provide a stable
Base64-encoded 32-byte `AUTH_REFRESH_ENCRYPTION_KEY`; password changes, admin
status changes, and logout revoke sessions. The local runner forwards these
settings from the shell for isolated local verification. `pnpm
smoke:core-refresh` starts an isolated Core Worker and D1 database to exercise
rotation, concurrent replay, reuse revocation, and logout through HTTP.

Agent owns D1-backed conversation creation, listing, detail and message
history reads, pin/read state, public sharing, model turns, SSE and WebSocket streaming,
cancellation, replayable events, and versioned personal Skills. An enabled
Skill may expose the registered `publish_post` tool. Agent records its canonical
arguments and fingerprint in D1, waits for the owner to approve, and only then
calls Content Automation; ambiguous publication outcomes are kept as `UNKNOWN`
and are not automatically retried. `remember` and `recall_memory` use a
user-scoped Agent D1 table and inject related memories into later turns. This
first tool set supports one-file upload up to 20 MiB to Aliyun OSS. Agent D1
stores only owner/object metadata; turns accept attachment IDs, enforce owner
scope, and refresh short-lived OSS URLs before model calls. Responses API models
receive image and document blocks; Chat Completions models receive image blocks.
Image uploads register an owner-scoped media asset in Content Automation over
its authenticated HTTPS contract; Agent D1 stores the returned media ID and
uses compensating cleanup if its attachment row cannot be saved. The
`image_generate` tool reads Core's `blog_agent_image` feature candidates,
registers the generated image in Content's OSS-backed gallery, and charges
reported image usage through Core. For local fallback configuration, pass
`AGENT_IMAGE_MODEL_BASE_URL`, `AGENT_IMAGE_MODEL_API_KEY`,
`AGENT_IMAGE_MODEL_NAME`, and `AGENT_IMAGE_MODEL_API_STYLE` to `pnpm dev`.
Responses API models receive document attachments as file blocks. Chat
Completions models receive image blocks; local document extraction/OCR is not
implemented.
Outbound MCP servers are owner-scoped in Agent D1, use AES-GCM encrypted
headers, cache a bounded tool catalog, and reject insecure/private destinations
and redirects. Every third-party MCP invocation waits for owner approval;
ambiguous outcomes are recorded as `UNKNOWN` and are not retried. Enable the
feature with `AGENT_MCP_CLIENT_ENABLED=true` and a stable Base64 32-byte
`AGENT_MCP_CREDENTIAL_ENCRYPTION_KEY`. `schedule_plan` and `email_reminder`
create Content-owned records only after an immutable owner approval. Their
create requests use the approval invocation ID as an idempotency key. Plan
pause/resume/edit and reminder pause/resume use owner-projected Content HTTPS
calls; plan cancellation/deletion and reminder deletion require approval.
`delegate_subtask` uses only owner-scoped read-only tools and returns bounded
findings to a parent synthesis call. Child and synthesis model usage are billed
separately. SSE disconnects do not cancel model execution while the Worker
invocation remains alive, and D1 events can be replayed after reconnect; a
runtime-terminated run is not automatically requeued.
The deployment needs the same
`JWT_SECRET` as Core, a private Core URL/token, and a Content Automation
URL/token. Core supplies decrypted managed model candidates only over the
service-token-protected `/internal/agent/model-config` contract. For local
approval verification, start `pnpm dev` with the four `AGENT_MODEL_*` variables
set to the deterministic mock configuration used by `pnpm smoke:agent-approvals`;
the mock never contacts an external model provider.

```powershell
$env:AGENT_MODEL_BASE_URL = "http://127.0.0.1:49871/v1"
$env:AGENT_MODEL_API_KEY = "agent-approval-smoke-only"
$env:AGENT_MODEL_NAME = "agent-approval-smoke"
$env:AGENT_MODEL_API_STYLE = "CHAT_COMPLETIONS"
pnpm dev
```

In another terminal, run `pnpm smoke:agent-approvals`. The runner exposes
these optional mock values only to the local Agent process; they are not
production-required secrets.

Payment owns virtual-pay settings, products, and orders in its D1. Its
`PAYMENT_SETTINGS_ENCRYPTION_KEY` is a Base64-encoded 32-byte AES key, stable
per deployment, and the admin API never returns the AppKey. Order creation asks
Core to validate the one-time WeChat login code against the account's bound
OpenID, then returns the WeChat HMAC signatures. Core's protected entitlement
contract extends a subscription once per trade number without downgrading an
existing tier. Payment status is owner-scoped. Reconciliation queries WeChat and
checks the paid amount before granting; the signed XML delivery callback is
idempotent and matches the local order before granting. The local runner creates
temporary Payment keys and uses a loopback Core URL; production uses HTTPS and
separate service tokens, including when the Workers run in different Cloudflare
accounts. Local delivery smoke tests use a synthetic pending order; live WeChat
login and query-order calls still need configured credentials.

Platform Integration now owns per-user CSDN/Juejin credential metadata and the
shared Juejin category/tag catalog in D1. The catalog sync command uses the
stored Juejin cookie and CSRF token, preserves provider IDs as text, and is
bounded to ten 100-tag pages per run. Its protected internal Juejin publisher
accepts an ownership-checked content projection, creates a provider draft, and
publishes it; Content Automation remains the owner of publication idempotency
records.
The protected internal CSDN publisher signs the legacy CSDN API requests,
converts Markdown while escaping raw HTML and unsafe links, uploads remote
Markdown images, and marks expired per-user cookies. Content Automation keeps
the idempotency record, a 30-second per-user cooldown, and a five-successful-
publications-per-UTC-day limit in D1. Mocked tests cover the provider adapter;
live CSDN requests still need credentials.
Public CSDN/Juejin post-sync routes take the Gateway-projected owner and call
Content's protected publication contract. Content checks ownership and keeps
the durable, fenced idempotency record. OAuth 2.1 supports dynamic client
registration, PKCE consent, one-time codes, rotating refresh tokens, and
replay-family revocation. Streamable HTTP MCP blog and publishing tools use
those OAuth grants and enforce the granted scope for every call. MCP servers
and provider tools stay disabled unless their per-deployment feature variables
are enabled. CSDN QR authorization uses a D1 browser-session queue and the
configured W3C WebDriver endpoint; local development returns a safe
configuration error when Selenium is absent.

Imported browser credentials are AES-GCM encrypted with
`PLATFORM_CREDENTIAL_ENCRYPTION_KEY`, bound to both provider and user, and never
returned by status APIs. The public status and disconnect routes are
owner-scoped; trusted service calls can import credentials during staged data
transfer. The public `POST /api/publishing/juejin/catalog/sync` command requires
the Gateway-projected owner and that user's Juejin credential. Catalog freshness defaults to 24 hours and can be changed with
`JUEJIN_CATALOG_FRESHNESS_HOURS`. Its verification-mail and catalog endpoints
follow the Controller → Service → Interface → Mapper layering.

Content Automation exposes the owner-authenticated `POST /api/blog/posts`
create route. Idempotency keys are stored with a request fingerprint in Content
D1; bodies up to 256 KiB stay inline and larger Markdown bodies use signed OSS
PUT/DELETE requests under the `article-content/posts/` prefix. The service
returns 409 if a key is reused with a different request. Content also exposes
the internal contract for Agent-initiated external publication attempts.
`POST /internal/agent/publications/{postId}/sync/{platform}` reads an
owner-scoped post from Content D1, loads OSS-backed text when needed, calls
Platform Integration over an authenticated service URL, and stores a fenced
attempt/result record in Content D1. Repeated successful requests return the
stored result; an active D1 lease rejects concurrent retries. Juejin and CSDN
are wired through the Platform Worker. CSDN publication has an atomic per-user
cooldown and UTC daily limit in Content D1.
The public blog list, detail, taxonomy, view-count, and Core managed-count
contracts now compose through `BlogPublicController` and `BlogPublicService`
with D1 and OSS adapters in the Mapper layer. Agent's Skill-gated
`publish_post` tool calls this contract only after a durable owner approval in
Agent D1.

For local text-model calls, either enable Core's admin model gateway, add an
enabled Agent feature-model configuration and set its encryption key, or set
`AGENT_MODEL_BASE_URL`, `AGENT_MODEL_API_KEY`, and `AGENT_MODEL_NAME` in the
shell before `pnpm dev`. The latter values are copied only into Agent's ignored
temporary `.dev.vars`. Use `AGENT_MODEL_API_STYLE=CHAT_COMPLETIONS` for that
protocol; the default is the OpenAI Responses API. Production model credentials
belong in Core's encrypted provider configuration or Agent Worker Secrets, never
in tracked Wrangler variables.

BlogHTML Preview now creates public, rate-limited preview sessions and serves
the isolated sandbox page with the legacy CSP, no-store, and nosniff headers.
Production mode writes the HTML body to a private `blog-html-preview/` OSS
prefix; D1 stores only its key, byte count, checksum, and expiry. Expired
objects and metadata are removed by a 15-minute cron. Local `pnpm dev` uses
`local-inline` mode and serves preview URLs through the Gateway.

For production, set a distinct `INTERNAL_SERVICE_TOKEN` Secret on each business
Worker and set the matching outgoing token Secret on Gateway (`AGENT_SERVICE_TOKEN`
for Agent, for example). Gateway, Core, and Agent need the Java
issuer's `JWT_SECRET`. Gateway also needs `CORE_SERVICE_URL` and
`CORE_SERVICE_TOKEN` so it can check that private-route callers are still
active. Configure each `*_SERVICE_URL` variable with that service's HTTPS base
URL; local Gateway URLs use `127.0.0.1` only for Wrangler development.
Configure each Secret on the Worker in every target Cloudflare account; these
bindings are per Worker and are not copied from another account.

```sh
pnpm exec wrangler secret put INTERNAL_SERVICE_TOKEN --config wrangler.jsonc
pnpm exec wrangler secret put JWT_SECRET --config wrangler.jsonc
```

Run the commands from each service directory. Add `JWT_SECRET`,
`CORE_SERVICE_TOKEN`, and each corresponding `*_SERVICE_TOKEN` Secret from the
Gateway directory. Agent and Payment also need `CORE_SERVICE_URL` and
`CORE_SERVICE_TOKEN` configured to reach Core across accounts; each outgoing
Core token must match Core's `INTERNAL_SERVICE_TOKEN`. Agent additionally needs
`CONTENT_AUTOMATION_SERVICE_URL` and `CONTENT_AUTOMATION_SERVICE_TOKEN`; that
token must match Content Automation's `INTERNAL_SERVICE_TOKEN`. Payment also needs
`PAYMENT_SETTINGS_ENCRYPTION_KEY` and `WECHAT_VIRTUAL_PAY_MESSAGE_TOKEN` as
Worker Secrets. Configure the same message token in the WeChat platform and set
its delivery URL to `https://<gateway-host>/api/virtual-pay/notify`. The Core
Worker must have its WeChat Mini Program credentials configured. These Secrets
are set separately on each target Worker/account. Internal contracts are not
included in the public Gateway route manifest.
Platform Integration needs its own stable `PLATFORM_CREDENTIAL_ENCRYPTION_KEY`
Secret in each target Cloudflare account; rotating it requires re-encrypting
that account's credential rows. Run `pnpm smoke:platform` with the local stack
to check encrypted import, Gateway status ownership, and disconnect.
Platform Integration also needs its Content and Core service URLs and matching
service tokens. Public post-sync requests delegate to Content's protected
publication endpoint; MCP publication billing calls Core's protected billing
contract. Set `MCP_OAUTH_PUBLIC_BASE_URL` to the public OAuth origin and
`MCP_OAUTH_ENABLED=true` to enable OAuth. Enable each tool surface with
`MCP_BLOG_ENABLED`, `MCP_CSDN_ENABLED`, `MCP_DUAL_ENABLED`, and
`MCP_JUEJIN_ENABLED`. QR login requires `PLATFORM_SELENIUM_URL` to point at a
reachable W3C WebDriver endpoint. Apply new local D1 schemas with
`pnpm exec wrangler d1 migrations apply dinqor-platform-integration-local --local --config wrangler.jsonc`
from `workers/services/platform-integration`.
For CSDN publishing, configure `PLATFORM_CSDN_API_KEY` and
`PLATFORM_CSDN_WEB_CONSOLE_SECRET` as Worker Secrets on Platform Integration in
each target Cloudflare account. From that service directory, run:

```sh
pnpm exec wrangler secret put PLATFORM_CSDN_API_KEY --config wrangler.jsonc
pnpm exec wrangler secret put PLATFORM_CSDN_WEB_CONSOLE_SECRET --config wrangler.jsonc
```

Content Automation also needs a `PLATFORM_INTEGRATION_SERVICE_URL` Worker
variable and a `PLATFORM_INTEGRATION_SERVICE_TOKEN` Secret whose value matches
Platform Integration's `INTERNAL_SERVICE_TOKEN`. Set the URL to the Platform
Worker's HTTPS endpoint in each deployment account; the contract supports the
Workers being in different Cloudflare accounts. Local `pnpm dev` generates a
temporary token pair and uses `http://127.0.0.1:8790`.

For local reads of legacy public OSS-backed article bodies without OSS keys, set
`CONTENT_OSS_PUBLIC_BASE_URL` to the old bucket's HTTPS base URL. Object keys are
appended by the service; do not include credentials or query strings in this
value. With OSS keys configured, article reads check `ALIYUN_OSS_BUCKET` first
and use `ALIYUN_OSS_LEGACY_BUCKET` only when the object is absent.

For private OSS objects, set `ALIYUN_OSS_ENDPOINT` to the OSS endpoint and, when
it is a regional endpoint such as `oss-cn-beijing.aliyuncs.com`, set
`ALIYUN_OSS_BUCKET` to the bucket name. The region is inferred from standard
regional endpoints; set `ALIYUN_OSS_REGION` explicitly for a custom endpoint.
Then provide `ALIYUN_OSS_ACCESS_KEY_ID` and `ALIYUN_OSS_ACCESS_KEY_SECRET` in
the shell before `pnpm dev`. The runner places these values only in ignored,
temporary `.dev.vars` files for Core, Agent, Content Automation, BlogHTML
Preview, and Platform Integration, then removes files it created on shutdown.
Each Worker reads them from its `env` binding (`env.ALIYUN_OSS_ACCESS_KEY_ID`
and `env.ALIYUN_OSS_ACCESS_KEY_SECRET`).
In Cloudflare, configure the AccessKey values as Worker Secrets on each of those
five Workers; endpoint, `ALIYUN_OSS_BUCKET=dinqor`,
`ALIYUN_OSS_LEGACY_BUCKET=food-cinder`, and region can be ordinary variables.
Set Content's `CONTENT_MEDIA_PUBLIC_BASE_URL` to
`https://dinqor-content-automation.dinqor.workers.dev/api/public/blog/media`.
Workers sign private object requests server-side, so credentials never reach
the Gateway response or browser. If neither AccessKey value is configured,
article reads use the configured public HTTPS base URL. Give the OSS RAM
principal the object read/write permissions required by the enabled service
prefixes.
The default private-object prefix is `article-content/`; override it with
`CONTENT_OSS_OBJECT_PREFIX` only when the bucket uses a different prefix.

Platform Integration also uses these bindings for file upload and download.
Set `PLATFORM_OSS_PREFIX` to the `platform-files` prefix (the default) and
grant that Worker `oss:PutObject`, `oss:GetObject`, and `oss:DeleteObject`
only under `platform-files/*`. Its files are delivered through the public
`/api/download/{filename}` route; the signed OSS request remains server-side.

BlogHTML Preview uses the same OSS endpoint, bucket, region, and Secret bindings
when it is deployed. Configure `ALIYUN_OSS_ACCESS_KEY_ID` and
`ALIYUN_OSS_ACCESS_KEY_SECRET` separately on the BlogHTML Preview Worker in each
Cloudflare account. Its RAM principal needs `oss:PutObject`, `oss:GetObject`,
and `oss:DeleteObject` only for the `blog-html-preview/*` prefix. Keep the
checked-in production storage mode set to `oss`; the local runner selects
`local-inline` unless `BLOG_HTML_PREVIEW_STORAGE_MODE=oss` is explicitly set.
Add an OSS lifecycle rule for this prefix so orphaned objects left by a failed
database write are eventually removed.
`BLOG_HTML_PREVIEW_PAGE_URL_PATTERN` and
`BLOG_HTML_PREVIEW_FRAME_ANCESTORS` must match the isolated preview domain and
the front-end origins allowed to embed it.

From the BlogHTML Preview service directory, add both AccessKey values as
Worker Secrets in each target account:

```sh
pnpm exec wrangler secret put ALIYUN_OSS_ACCESS_KEY_ID --config wrangler.jsonc
pnpm exec wrangler secret put ALIYUN_OSS_ACCESS_KEY_SECRET --config wrangler.jsonc
```

From the Content Automation service directory, add each production secret
separately while authenticated to each target Cloudflare account:

```sh
pnpm exec wrangler secret put ALIYUN_OSS_ACCESS_KEY_ID --config wrangler.jsonc
pnpm exec wrangler secret put ALIYUN_OSS_ACCESS_KEY_SECRET --config wrangler.jsonc
```

| Service | Local URL |
|---|---|
| Gateway | `http://127.0.0.1:8787` |
| Core | `http://127.0.0.1:8788` |
| Agent | `http://127.0.0.1:8789` |
| Payment | `http://127.0.0.1:8794` |
| Platform Integration | `http://127.0.0.1:8790` |
| Content Automation | `http://127.0.0.1:8791` |
| BlogHTML Preview | `http://127.0.0.1:8792` |

Run `pnpm check` to verify the generated public route manifest and TypeScript
types. Local D1 databases are emulated by Wrangler; Docker is not required for
this Worker runtime. Docker Desktop remains available for integrations that
need a separate container later.

The Content Automation D1 schema is in
`services/content-automation/migrations/`. `pnpm dev` applies all pending
migrations automatically, including publication fencing, CSDN rate limits, and
article creation idempotency, owner-scoped notifications, and blog plans. To apply
them manually, run this from
`services/content-automation`:

```powershell
pnpm exec wrangler d1 migrations apply dinqor-content-automation-local --local --config wrangler.jsonc
```

Production deployment is intentionally not configured yet: each service still
needs a real D1 database in its target account, production service URLs and
secrets, and migrated domain schemas/handlers.

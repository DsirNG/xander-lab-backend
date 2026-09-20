# Backend Architecture

The backend remains a modular monolith. Business capabilities are grouped by
feature; deployment and database topology stay unchanged.

## Business Modules

- `identity` (migrated): owns users, login, token refresh, JWT validation and request authorization. It also owns the WeChat Mini Program login channel: the jscode2session client, the `sys_user.openid` binding column (`sys_user_wechat_openid_migration.sql`), and the admin-managed login credential (`wechat_credential` single-row table, `wechat_credential_migration.sql`). WeChat AppID/AppSecret no longer come from environment variables: they are written once through the admin console, stored AES-GCM-encrypted under `WECHAT_CREDENTIAL_ENCRYPTION_KEY`, and never returned by any API; without a stored row the channel stays closed with a clear "not configured" business error. The channel uses a "bind-first, create-later" policy: an unknown openid is issued a short-lived bind token and is not auto-registered; the user either binds an email (`wechat-bind`, merging into a pre-existing PC account with that email) or skips binding to get a temporary WeChat account (`wechat-skip`). Binding or rebinding later (`wechat-bind-existing`) migrates a temporary account's business records to the target account and transfers its points balance. Because email binding makes mini-program and PC accounts one user, `identity` is the exclusive owner of this account-merge lifecycle and `AccountMergeMapper` is the single sanctioned cross-table reparenting point: it reparents rows owned by `agent`/`blogagent`/`publishing`/`automation`/`studio`/`components` and to no other mapper does `identity` write.
- `virtualpay` (new): owns personal-entity Mini Program virtual-payment products, orders, XPay signing, delivery notifications, and active order reconciliation. It uses a fresh `wx.login` code per checkout; `identity` exchanges and verifies the resulting openid/session_key, and session_key is never persisted. Delivery changes `sys_user` subscription fields only through `SubscriptionEntitlementService`; client payment callbacks never grant benefits. Apply `virtual_pay_migration.sql` and configure the MP delivery URL before enabling it.
- `agent` (new): owns durable orchestration conversations, messages, replayable SSE events, model decisions, execution journals (`agent_run`, `agent_step`, `agent_tool_invocation`), and the registry of user-scoped tools. `AgentRunLifecycle` owns fenced run claims, cancellation, lease renewal, terminal transitions and stale-run recovery; `AgentEventProjector` owns active-run message fencing and legacy event projection; `AgentTurnCoordinator` owns claimed-turn lease and terminal-failure coordination. The compatibility conversation facade delegates these boundaries. A `done=true` model decision is delivered directly: the module does not run a generic plan/card/failure self-check loop that issues hidden retry calls. The module does not own the business state created by a tool.
- `blogagent` (migrated): generates and revises content, stores evidence and versions.
- `publishing` (migrated): owns blog posts, categories, tags, view records, publication commands, generic publication contracts, durable publication records, and blog media uploads. The media library implements `content.api.MediaAssetPort`; its MyBatis entity `BlogMediaAsset` stays inside `publishing.domain` and is mapped to `content.api.MediaAssetRef` at the port boundary, so `agent`/`blogagent`/`studio` no longer import it. `BlogService` implements `content.api.ContentPostPort` and `PublicationService` implements `content.api.PublicationPort`, so the three deployment units that used to import the concrete `@Service` classes (`agent`, `integration`, `profile`) now depend on the contract instead; `BlogService` keeps its old public signatures as thin shells that read `UserContext` and delegate to private overloads that take the caller id explicitly, which leaves all 17 same-domain call sites in `BlogController` untouched. `publishing.api.BlogPostVO` / `BlogPostDTO` stay here: they are the frontend HTTP contract, a different audience from the service-to-service one, and `BlogPostVO` is simultaneously the MyBatis projection target of `BlogPostMapper`.
- `automation` (migrated): owns email reminders, recurring content plans (`blog_content_plan`), durable plan runs (`blog_plan_run`), the plan poller with atomic claiming and stale-claim recovery, and user notifications (`blog_notification`). Runs a headless pipeline: generation via `blogagent`, a fail-closed compliance gate, local/CSDN publication via `publishing`/`integration`, then reschedules the plan. Per-user SSE pushes live updates; the notification table is the durable fallback for offline users.
- `integration` (migrated): owns MCP protocol adapters, OSS/file/mail delivery adapters, and vendor-specific integrations such as the complete CSDN MCP flow and the Juejin MCP flow.
- `studio` (migrated): owns FlowCraft projects, canvases and snapshots, plus the image-to-3D task pipeline and model client.
- `components` (migrated): owns the UI component library catalog, detail pages, and community-shared submissions.
- `bloghtml` (new): owns short-lived blog HTML preview sessions. Session creation (`POST /api/blog-html/previews`) is public with per-IP Redis fixed-window rate limiting; code is stored in Redis with a TTL and served as a standalone sandbox page (`GET /blog-html/{id}/raw`) outside `/api` so iframes load it without auth, CORS or cookies. The page runs on an independent origin (per-session subdomain in production, a different port locally) and carries a strict CSP with `frame-ancestors` restricting embedders; this is the preview isolation pattern used by ChatGPT for its artifact pages.
- `billing` (new): owns the points account (`sys_user_points`), the append-only points ledger (`points_ledger`) with a unique `(biz_type, biz_key)` idempotency key, priced model-usage charges, flat-price charges, balance pre-checks, and the `/api/points` endpoints. It must not own generation tasks, conversations, articles, reminders, or publications; every other domain charges through the `payment.api.BillingPort` contract and never through its mappers. `BillingService` implements that port and additionally exposes the frontend views (`pageLedger` / `overview` / `priceList` / `listModelPrices` / `upsertModelPrice` / `deleteModelPrice`), which only its own two controllers call and which are deliberately not on the port.
- `profile` (new): owns the read-only `/api/profile/overview` composition used by the mini-program profile screen. It owns no persistence and reads account identity, point balance, and managed-blog count only through `identity`, the `payment.api.BillingPort` contract, and `publishing`.
- `recitation` (new): owns user-scoped Chinese recitation materials, durable audio transcription attempts, and deterministic source/transcript alignment reports. Audio is stored through `integration.oss`; the background worker calls only the admin `ModelGateway` contract for the `recitation_asr` feature and keeps external model calls outside transactions. Submission and polling use conditional state transitions plus run tokens so multiple backend instances cannot overwrite one another.
- `admin` (new): owns the backend admin console. It manages model providers (`model_provider`, AES-GCM encrypted API keys via `MODEL_PROVIDER_ENCRYPTION_KEY`), per-feature model routing (`feature_model_config` with primary + fallback endpoints), and exposes the read-side API for user bans; it never writes users or login sessions itself. `ModelGateway` is the shared contract that model clients consult for endpoint candidates; when the gateway is disabled (default), clients fall back to environment variables so existing behavior is preserved, but once the gateway is enabled every feature is driven solely by `feature_model_config` — an unconfigured feature fails with a clear "not configured" business error (HTTP 400) instead of falling back. User ban/enable mutations go through the `identity` application service (`UserAdminService`), which owns session revocation; the WeChat login credential is likewise managed through the `identity` application service (`WechatCredentialService`, via `/api/admin/wechat-credential`); `admin` owns neither identity tables nor Redis login keys.
- `knowledgebase` (new): owns the knowledge-base file center — the folder tree (`knowledge_folder`, materialized `path`), the user-private tag vocabulary and its file links (`knowledge_tag`, `knowledge_file_tag` with a `MANUAL`/`AUTO` source column), files and their extracted text (`knowledge_file`, `knowledge_file_content`), detected chapters (`knowledge_chapter`), generated knowledge-structure graphs (`knowledge_structure_node`), and resumable chunked uploads (`knowledge_upload_session`, `knowledge_upload_chunk`). Routes live under `/api/knowledge-base/**` and `AuthInterceptor` requires login for the whole prefix; there is no public route. The root folder holds folders only — uploading into it is rejected. Object storage goes through `integration.oss`; user notifications go through the `automation` application service (`BlogNotificationService.notifyResource`) and never through its mappers; `recitation`'s quiz/knowledge-mirror tables are a different capability and are not touched. Apply `knowledge_base_schema.sql` (fresh) or `20260901_knowledge_base_file_center_migration.sql` (existing databases; marker key `20260901_knowledge_base_file_center`), which also adds the generic `resource_type`/`resource_id` pair to the shared `blog_notification` table. Chunk staging writes to a local directory, so the expired-session reaper is disabled by default (`knowledge-base.reaper-enabled`). The HTTP contract is documented in `docs/KNOWLEDGE_BASE_API.md`.

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

Cross-domain calls follow two standing decisions, both recorded in
[`docs/migration/adr-contract-boundaries.md`](migration/adr-contract-boundaries.md):
contract modules must stay serialization-framework agnostic (Jackson belongs to
inbound/outbound adapters), and cross-domain communication must use
consumer-owned contracts — a domain entity must never cross a bounded-context
boundary. In practice that means a cross-domain caller depends on a
`contracts/*-api` port and a `*Ref`/DTO value type, while the owning module
keeps its entity and maps explicitly at the boundary (for example
`publishing.domain.BlogMediaAsset` → `content.api.MediaAssetRef` behind
`content.api.MediaAssetPort`, and `billing.application.BillingService` →
`payment.api.BillingPort` with `payment.api.TokenUsage` as its usage DTO).
There is deliberately no `common-model` / `shared-dto` module.

ADR-1 has a real cost and it is paid at the boundary: a contract may not import
`libs/xander-model`, because that library declares `jackson-databind` for
`ModelUsage.fromJson(JsonNode)`. So the seven domains that charge for something
convert the gateway's `ModelUsage` into `payment.api.TokenUsage` themselves —
`new TokenUsage(u.inputTokens(), u.cachedTokens(), u.outputTokens())`. The
alternative was to widen `CONTRACT_ALLOWED_IMPORT_PREFIXES`, which would have
made a contract depend on a framework-bearing library and put §19's
super-`common.jar` back on the menu. See
[`docs/migration/adr-contract-boundaries.md`](migration/adr-contract-boundaries.md)
§4 for the measured outcome.

`agent` invokes business capabilities only through `AgentTool` adapters. Every
turn exposes the full registered tool set: skills change how the model works,
never what it can call. A turn renders one thin catalog of skill names and
when-to-read lines into the tool surface, and the model calls `load_skill` to
pull back the one body it decides to follow. The offered catalog and the turn's
tool names are stored on `agent_run`; the step snapshot also records which
loaded bodies that step is actually following. Tools run through
`AgentToolExecutionPipeline`, which re-asserts the turn's tool set and checks
basic required arguments before a durable invocation exists; approval, not tool
visibility, is the boundary that holds external effects. The pipeline
records an invocation UUID before execution. `email_reminder` and
`schedule_plan` pass that UUID into owning-service, database-unique create
keys; `AgentToolInvocationReconciler` later performs read-only lookups for
their interrupted calls and settles proven effects. A stale `RUNNING`
invocation is otherwise marked `UNKNOWN`, never replayed automatically.
`publish_post` reconciles local publication and CSDN/Juejin synchronization
only from durable records in the owning modules; it never probes a vendor or
re-publishes to infer an outcome. A remote success that was never persisted in
`blog_publish_record` remains `UNKNOWN`. `blog_generate` persists its
invocation UUID as a per-user task-create key and may recover only a durable
`ready` task; created, running, failed, or missing tasks remain `UNKNOWN` and
are never restarted automatically. Recognized remote uncertainty (timeouts,
connection-access failures, and HTTP 5xx responses) is likewise persisted as
`UNKNOWN` for read-only reconciliation; validation and other determinate
business failures remain `FAILED`. Approval and idempotency for other mutating
tools remain pending. `agent_approval` owns user decisions for
high-impact invocations: publishing, scheduled mail/content plans, and
destructive plan/reminder actions pause the conversation as
`awaiting_approval`. Approval atomically reopens the same fenced run, executes
the same invocation UUID on the agent worker, records its result, and resumes
the next model step; rejection cancels the invocation and closes the run.
`delegate_subtask` is deliberately read-only because child loops do not yet own
an independent invocation journal or approval lifecycle, so all mutations stay
in the parent execution pipeline. Built-in skills are
compiled Spring beans; a user-authored skill is a text body in `agent_skill`
that the model may load and follow, and it can neither relax approval nor change
tool rules. Uploaded executable skills are not supported. The
`blog_generate` adapter calls the `blogagent` application service and returns a
durable blog task ID; ownership of the generated task, sources, versions, and
publication hand-off remains in `blogagent`. Additional adapters delegate to
the owning modules without duplicating their state: `image_generate` renders an
illustration through the blog-agent image client and persists it via the
`content-api` `MediaAssetPort` implemented by `publishing`; `publish_post` hands a task or post to the
`publishing`/`integration` sync services and charges the platform flat rate;
`query_posts` and `query_unsynced_posts` read the user's own posts through the
`content-api` `ContentPostPort` implemented by `publishing`; `schedule_plan` and
`email_reminder` create durable records in `automation`. Agent tools never
own schedules, platform credentials, or publication records, and they must not
rely on a request thread context: every tool receives an explicit user id, which
is why the `content-api` ports take the caller id as a parameter — a
`ThreadLocal` cannot cross a process. Tools that still reach a service which
reads the thread (`automation`, and `blogagent`'s publish path) establish the
`UserContext` explicitly. The orchestration UI may link to a tool result for review, but the blog
generation workflow is not a second agent or a source of orchestration
conversation state.

The personal knowledge mirror is part of the same orchestration surface rather
than a second chatbot. `create_knowledge` and `query_knowledge` delegate to the
`recitation` application service with the explicit conversation user id. The
agent may create user-confirmed knowledge and read mastery/review projections,
while `recitation` remains the owner of source content, tests, scores, review
history, and scheduling. Both the web and mini-program clients consume these
tools through the existing conversation event contract.

Agent turns also accept up to five user attachments. The agent application
uploads supported images and documents through `integration.oss`, stores an
immutable attachment snapshot on the owning `agent_message`, and sends recent
attachments to the configured Responses-compatible model as native
`input_image` or `input_file` items. The model provider remains stateless
(`store=false`); attachment history stays in the local agent conversation.

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
`ModelEndpoint` (base URL, API key, model name, API style). Feature keys are stable
constants (`agent`, `blog_agent`, `blog_agent_image`, `img2three_vision`, `recitation_asr`). API
style is configured per
slot in `feature_model_config` (`primary_api_style` / `fallback_api_style`):
`IMAGES_GENERATIONS` (/images/generations, legacy),
`CHAT_COMPLETIONS` (/chat/completions with messages+modalities, official OpenAI),
`CHAT_PROMPT` (/chat/completions with top-level prompt, aggregator gateways like okinto),
`RESPONSES` (text features), `DASHSCOPE_ASR` (DashScope asynchronous recorded-audio transcription). `ModelGatewayService`
resolves blanks to per-feature defaults (`blog_agent_image`→`CHAT_COMPLETIONS`,
text features→`RESPONSES`, `recitation_asr`→`DASHSCOPE_ASR`).
Clients dispatch on the style; today
`BlogAgentImageClient` consumes `IMAGES_GENERATIONS`, `CHAT_COMPLETIONS` and
`CHAT_PROMPT` (responses accepted from both `choices[0].message.content` and
`data[0]`), text clients still use `/responses`. The
`20260816_feature_model_config_api_style_migration.sql` marker key is
`20260816_feature_model_config_api_style`. The
gateway is disabled by default and requires
`MODEL_PROVIDER_ENCRYPTION_KEY` (Base64 32 bytes) when enabled; the
`admin_model_gateway_migration.sql` marker key is `20260815_admin_model_gateway`
and must be applied before production enablement. When enabled, an unconfigured
feature throws `FeatureModelNotConfiguredException` (HTTP 400, no environment
fallback): configure all five features in the admin console before users rely
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
6. Shared cross-module code is split by technology, not collected in one `common`
   jar: `Result` and the model call/usage types in `libs/xander-model`, the
   pagination response `PageData` and the MyBatis-Plus interceptor in
   `libs/xander-mybatis-support`, the trusted identity model (`UserContext`) and
   token verifier (`JwtUtil`) in `libs/xander-auth-core`, and the operational
   health endpoints still on the application module (`apps/core-host`). The split
   is deliberate, not cosmetic: a WebFlux gateway can depend on `xander-model` and
   `xander-auth-core` but must never pull in MyBatis, so they cannot share one jar.
   The auth lib also keeps `HandlerInterceptor` out on purpose — `AuthInterceptor`
   stays in `identity.security`, where the Servlet-side wiring lives.
7. Types that cross a future *service* boundary live in `contracts/`, not in
   either domain. `contracts/content-api` holds the publish SPI (`BlogPublisher`)
   and its data (`PublishCommand`, `PublishResult`, `PublishingLimits`), because
   the CSDN and Juejin adapters implement that port from the `integration` domain
   while `PublicationService` consumes it from `publishing`. Leaving the port in
   `publishing.domain` would make `integration` implement an interface owned by
   another service once the split happens. The same module also holds the
   media-library port (`MediaAssetPort` / `MediaAssetRef` / `StoredImage`) and the
   post-read/write ports (`ContentPostPort` / `PublicationPort`) with their data
   (`ContentPost` / `ContentPostPage` / `ContentPostInput` / `ContentPostQuery` /
   `ContentPostStatus`). `ContentPost` and `ContentPostPage` are *mirrors* of
   `publishing.api.BlogPostVO` and `common.PageData` — same names, types and
   order — so that swapping the type leaves the wire JSON byte-identical and the
   extraction is provably a pure refactor; the mirror is pinned by
   `ContentPostShapeTest` in `apps/core-host`, which the contract module itself
   cannot host because it cannot see `BlogPostVO`. Contract modules are
   **dependency free** (JDK imports only, no compile/runtime dependency): every service depends
   on them, including the WebFlux gateway, so a contract that grows a framework
   dependency has become the super-`common` jar in a different folder. The
   frontend-facing DTOs (`publishing.api.BlogPostDTO` / `BlogPostVO`) deliberately
   stay with the domain until its api layer is split, because `BlogPostVO` is also
   the MyBatis projection target of `BlogPostMapper`.
8. `contracts/integration-api` holds the object-storage port (`OssPort`) and its
   result (`UploadResponse`), because four domains in four different deployment
   units (`agent`, `knowledgebase`, `publishing`, `recitation`) all injected the
   concrete `integration.oss.OssService`. Unlike item 7 this was not a *move*: none
   of the seven cross-domain types in `integration` is an interface, so the port had
   to be **introduced**. `OssPort` takes `byte[]` rather than Spring's
   `MultipartFile` — a contract is depended on by every service including the
   WebFlux gateway, and a `MultipartFile` cannot be sent over a network call
   anyway. `OssService.upload(MultipartFile, String)` survives as an in-domain
   convenience for `FileController`. This module has grown since, always by the same
   rule: an `integration` type that a *different* deployment unit injects gets a
   port. `OssPort` came first; then `CsdnBlogSyncPort` / `JuejinBlogSyncPort` with
   `BlogSyncResult` for the off-site sync services; and now `MailPort`, which also
   let `MailService` shed its reminder-rendering half — the
   `automation.domain.EmailReminderMailTemplates` import and the
   `email-reminder.display-zone` key it was reading out of another domain's
   configuration. That removal is what deleted the last `integration → automation`
   statement. Then `CsdnCredentialPort` / `JuejinCredentialPort` for the credential
   query surface — each declares exactly **one** method (`isAuthorized`), because
   that is the whole cross-domain surface: the two implementations have six public
   methods each, and the other five have no cross-domain caller at all, so putting
   them on the port would force the consumer's adapter to implement four members it
   never calls. ⚠️ These two ports have **identical method names and signatures**, so
   a receiver swap compiles — unlike `CsdnBlogSyncPort` / `JuejinBlogSyncPort`, whose
   differing parameter lists catch that at compile time, this one is caught only by
   an asymmetric-stub behavioural test. That deleted the last `automation →
   integration` statement, so **nothing in `integration` is crossed by another
   deployment unit any more**. The last cross-unit implementation edge in the
   whole repo — `admin → automation`, where `AdminNotificationController` injected
   the concrete `BlogPlanSseService` only to call `broadcast` — is now gone too,
   deleted by `NotificationBroadcastPort` in `automation.spi`. The class itself
   cannot be moved (`subscribe` returns `SseEmitter`, `push` takes blog-plan ids),
   but the *edge* carried nothing but three `String`s and returned an `int`, so the
   port takes exactly those three: a **narrow port**, not a relocated class. It also
   drops the implementation's `planId` / `runId` parameters, because the sole caller
   always passed `null` and a parameter the caller must fake on every call does not
   belong on a contract. **Cross-deployment-unit implementation edges are now
   zero** — the 17 implementation statements / 7 edges that remain are all inside a
   single deployment unit. ⚠️ Zero is an **unguarded** invariant today: a new
   one-way cross-unit edge reddens no gate, because `deployment-unit-coupling` only
   judges shape (a one-way edge joins no component and creates no mutual pair) and
   `cross-domain-import-baseline`'s baseline is the scanner's own output. Each cut is
   recorded in `docs/migration/lib-extraction.md`.

9. `contracts/core-api` holds the runtime model-routing SPI (`ModelGateway`), its
   `ModelEndpoint` record, and `FeatureModelNotConfiguredException`. Like item 7
   this is a *move*, not an introduced port: the type was already an interface.
   The implementation (`ModelGatewayService`, which reads `model_provider` and
   `model_feature_config`) stays in the `admin` domain of `dinqor-core-host`, while
   the consumers sit in three future deployment units — `dinqor-agent`,
   `dinqor-content-automation` and `dinqor-core-host` — so an interface owned by the
   provider but depended on across a service boundary points the wrong way. The
   exception travels with the interface rather than the implementation: it is
   documented SPI behaviour ("the gateway is on and this feature has no usable
   endpoint"), `BlogAgentImageClient` catches it and `GlobalExceptionHandler` maps
   it to HTTP 400. With this extraction `admin` has **zero** inbound implementation
   imports; the only remaining edge is `virtualpay → admin` on the `admin.api` layer,
   which is contract surface rather than backlog. The `FEATURE_*` and `API_STYLE_*`
   constants are persisted as `model_feature_config` column values, so
   `ModelGatewayTest` pins each literal — a rename would not break the build, it
   would silently orphan existing rows.

10. `contracts/payment-api` holds `BillingBiz`, the charging catalogue whose values
    are persisted as `points_ledger.biz_type`. Another *move*, not a port: it is a
    final constants class with a private constructor and no imports at all. The
    criterion is the one used throughout — the type crosses a deployment unit,
    because **every charging call site has to name what it is charging for**, and
    those call sites live in six domains across three future deployment units
    (`dinqor-agent`, `dinqor-platform-integration`, `dinqor-content-automation`,
    `dinqor-core-host`). It is not an internal enum of `billing`: after the split the
    other services would each need a copy (which would drift) or a dependency on
    another service's internals (which reverses the boundary). The catalogue is
    named for the *capability* (`payment`) rather than the current module
    (`billing`), which is why its package is `com.xander.lab.payment.api` — the §18
    module list is deliberately not a mirror of today's directory names. Renaming a
    catalogue value does not break the build, it silently orphans existing ledger
    rows, so `BillingBizTest` reflects over every `public static final String` field
    and compares the whole map rather than asserting field by field — a per-field
    test would miss "a new charging item was added and nobody added an assertion",
    which is the likeliest change of all.

    The same module now also holds `BillingPort` and `TokenUsage`, the second cut.
    `BillingService` — the other cross-domain type in this domain, a concrete
    `@Service` over three MyBatis mappers, imported by seven domains across four
    future deployment units — needs a port rather than a move. Its
    `chargeTokens(...)` takes `com.xander.lab.common.ModelUsage`, which carries
    Jackson, so ADR-1 forced the contract to own an equivalent DTO rather than
    widening the import allow-list. The port covers exactly the seven methods the
    other domains call; `pageLedger` / `overview` / `priceList` / `listModelPrices` /
    `upsertModelPrice` / `deleteModelPrice` stay on the implementation because only
    `billing`'s own controllers call them. Because `TokenUsage`'s accessors are named
    identically to `ModelUsage`'s, the implementation body did not change at all —
    only the parameter type. `BillingPortTest` pins the *full signatures*, not just
    the method names: `chargeTokens`' first four parameters are
    `(Long, String, String, String)`, and reordering the three adjacent `String`s
    would silently write the idempotency key into the `biz_type` column.

11. `contracts/core-api` also holds `UserLookupPort`, a one-method port that answers
    "what is this user's display name". Before it existed, two domains injected
    `identity.infrastructure.UserMapper` — a MyBatis mapper — and
    `identity.domain.User`, the `sys_user` entity, purely to render an author name:
    `BlogService.resolveAuthor` and `ComponentService.shareComponent`. That is what
    identity's own package-info forbids ("feature modules ... must not parse tokens
    or query the user table directly"), and for `publishing` it crossed a deployment
    unit as well — `dinqor-content-automation` reaching straight into
    `dinqor-core-host`'s tables. The port returns `null` when it cannot answer rather
    than a fallback string, because the two callers have *different* fallbacks
    (`"User {id}"` versus `"匿名用户{id}"`) and a fallback is display policy, not
    identity data. Two things about this port are worth remembering. First, it
    replaced a *behavioural* difference nobody had noticed: the old `BlogService`
    treated a blank nickname as absent, while the old `ComponentService` used
    `nickname != null` and would display an **empty author name** — and neither path
    had a single test, so nothing would have caught a change either way. Second, a
    port's *width* is an invariant nothing else guards: adding a `default` method
    compiles, moves no metric, and trips no gate, yet each method is another way
    around the boundary. `UserLookupPortTest` therefore reflects over the declared
   methods and asserts there is exactly one — verified by injection, where a second
   `default` method left the build green and every other test passing.

12. `contracts/integration-api` also holds `MailPort` — three methods, and the most
    useful thing about it is what it does *not* carry. It replaces
    `integration.mail.MailService`, a concrete `@Service` that three callers in two
    other deployment units injected (`identity.AuthService` sends login verification
    codes; `automation.EmailReminderScheduler` sends due reminders). The direction is
    what made this cut interesting: `MailService` had
    `sendReminderMail(to, subject, userMessage, scheduledAt, contentType, templateId)`,
    and the last four parameters existed only to **render** — normalise PLAIN/HTML,
    format the send time in the display zone, choose a branded layout, build a
    plain-text fallback. Rendering needs `automation.domain.EmailReminderMailTemplates`
    (another domain's business type) and the `email-reminder.display-zone` key, whose
    `owner` in `docs/config-ownership.yaml` is `content-automation`. The provider
    therefore depended on the consumer *and* read the consumer's configuration: that
    was the entire `integration → automation` edge, and the last leg of the last cycle
    in the deployment-unit graph.

    The split criterion is "what does this reminder look like" versus "put it in the
    mail". The first is an `automation` concept (`EmailReminderTask`,
    `EmailReminderService` and `EmailReminderScheduler` all live there), so rendering
    moved back as `automation.domain.EmailReminderMailRenderer` — a pure function that
    takes the display zone id as a `String` and resolves it *inside* the HTML branch,
    so a bad `display-zone` cannot kill plain-text reminders. `MailPort.sendHtmlMail`
    then takes two **finished** bodies, and `MailPort` has no time parameter at all.

    Two smaller things came out of the same cut. `MailService` had **no test class** —
    it implemented no contract, so its body was only ever exercised as a mock inside
    its callers' tests; `MailServiceTest` now asserts that both finished bodies reach
    the MIME tree verbatim. And the port deliberately takes no `userId`, which
    `MailPortTest` pins as a *negative* assertion: the verification code is sent before
    login, and delivery does not need to know who the recipient is, so "add the caller
    id for consistency" is exactly the change that would write a false signal into the
    contract.

    Effect: cross-unit implementation statements 8 → 5, cross-unit edges 4 → 2, and
    every Wave 2 unit became a singleton component — the remaining graph is the chain
    `core-host → content-automation → platform-integration`, which admits a
    topological extraction order where the cycle admitted none.

## Agent HTTP, SSE, and WebSocket Contract

Authenticated orchestration routes live under `/api/agent/conversations`.
Creating a conversation and reading its snapshot use the shared `Result`
envelope. A turn is delivered by `POST /{id}/messages/stream`; clients that cannot read SSE (WeChat mini-program) use the non-stream trigger `POST /{id}/messages` (claims the turn, runs it in background, and returns the run version immediately). Live streaming for those clients uses the WebSocket endpoint `/ws/agent?conversationId=&runVersion=` (token via `Authorization` header or `token` query), which replays the same durable event stream frame-by-frame and closes after the terminal event. Non-terminal WebSocket disconnects are reconnected with backoff; the client deduplicates replayed frames by durable event ID and reads one final conversation snapshot after `complete` or `error`, without periodic snapshot polling. Browser SSE recovery uses
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
6. `20260824_agent_message_attachment_migration.sql`. This adds the nullable
   attachment snapshot used by web and mini-program multimodal turns.
7. `20260903_agent_harness_journal_migration.sql`, then
   `20260903_agent_harness_invocation_unknown_migration.sql`. These create the
   Harness run/step/invocation journal and document `UNKNOWN` as the required
   reconciliation state for a side effect interrupted by a worker crash.
8. `20260903_blog_content_plan_invocation_idempotency_migration.sql`. This
   adds the per-user create key used by the `schedule_plan` tool; the matching
   fresh-install column and unique index live in `blog_content_plan_schema.sql`.
9. `20260903_agent_harness_approval_migration.sql`. This adds durable approval
   records plus the waiting-approval run, invocation, conversation, and event
   vocabularies required to resume the original invocation safely.
10. `20260903_blog_agent_task_invocation_idempotency_migration.sql`. This adds
    the per-user creation key used to prove a completed `blog_generate`
    invocation without restarting model work.

The older `blog_agent_*_migration.sql` files are upgrade-only deltas for a
database that predates the current `blog_agent_schema.sql`; do not apply those
column-add migrations after the current fresh baseline. Migration markers are
audit records, while the guarded column check is the source of truth for the
cancel-request and execution-generation upgrades.

The list above is the fresh-install path: the baseline files already contain
every current column, so the remaining migrations are no-ops on a new database.
An **upgrade** of an existing database must additionally replay the card and
dictionary migrations — `20260825_agent_autonomy`, `20260827_agent_conversation_pin_unread`,
`20260831_agent_quiz_card`, `20260901_agent_plan_message`, `20260902_agent_artifact_card`,
`20260903_agent_context_compaction`, `20260909_agent_memory_media_asset`, and the
`20260914_*` pair — **in date order**. Two reasons that order is not optional:

- `20260901_agent_plan_message` is the only migration that adds
  `agent_message.run_version` and `idx_agent_message_conversation_run_kind`. The
  plan card upserts by `(conversation_id, run_version, kind)` and
  `AgentMessageMapper.insertForActiveRun` lists `run_version` in its column list,
  so a database that skips this migration fails the first time the agent updates
  its plan.
- The `kind` / `event_type` column comments are rewritten by several migrations,
  each restating the whole vocabulary. **The last writer wins**, so applying them
  out of order silently drops values from the operator-facing dictionary — which
  is exactly how `plan` went missing once.

## Blog HTML Preview Deployment

Production serves preview pages on a dedicated wildcard domain so every session
is an independent origin. Example nginx server block (DNS must map
`*.blogHTML.dinqor.cn` to the host):

```nginx
server {
    listen 443 ssl;
    server_name ~^(?<sid>[0-9a-f]{16})\.blogHTML\.dinqor\.cn$;
    # existing TLS config ...

    location /raw {
        proxy_pass http://127.0.0.1:30002/blog-html/$sid/raw;
        proxy_set_header Host $host;
    }
}
```

The blog site must allow embedding the preview origin, e.g. CSP
`frame-src 'self' https://*.blogHTML.dinqor.cn`; the sandbox page
itself restricts embedders via its own `frame-ancestors` header
(`BLOG_HTML_FRAME_ANCESTORS`).

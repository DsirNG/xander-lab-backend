# DinQorAI Backend Rules

## API and authentication

- Return the shared `{ "code": number, "message": string, "data": null }` error shape.
- Keep every `Result` error code aligned with a real HTTP status. Custom business codes require an explicit semantic status mapping.
- Authentication failures use HTTP 401 and the canonical login-expired response; never return HTTP 200 for a missing, expired, or invalid token.
- Keep private APIs explicit in `AuthInterceptor`; public wildcard patterns must not expose task or user data.
- Authorization checks must include resource ownership, not only authentication.
- Use `GlobalExceptionHandler` and typed domain/application exceptions instead of ad hoc error bodies.
- Preserve JSON field names and HTTP routes during package refactors unless the task explicitly changes the public contract.

## Business modules and comments

- Organize new code by business capability first, then by `api`, `application`, `domain`, `infrastructure`, or `security`.
- Do not add new business classes to the global `controller`, `service`, `entity`, or `mapper` packages.
- Every business module must contain a `package-info.java` stating what it owns, what it does not own, and which modules may call it.
- Every public business class and interface needs succinct responsibility-oriented JavaDoc.
- Document public methods when authorization, idempotency, state transitions, side effects, retries, or errors are not obvious from the signature.
- Add short comments before concurrency control, token handling, scheduler claiming, retry logic, and compatibility workarounds. Explain why, not each assignment.
- DTO comments describe the API contract. Domain-field comments describe ownership, units, lifecycle states, security meaning, and enum values where needed.
- Improve misleading or garbled comments in files being changed, but avoid unrelated comment churn.
- Architecture boundary changes must update `docs/ARCHITECTURE.md` in the same change.

## Module boundaries

- API packages own transport mapping and validation only. Controllers and MCP tools must not contain persistence queries, remote orchestration, retries, or domain state transitions.
- Application services coordinate use cases and transaction boundaries.
- Domain state must not depend on Spring MVC, MCP schemas, HTTP clients, Redis templates, or vendor SDK types.
- Infrastructure packages implement persistence and external-system concerns. Keep CSDN, OSS, mail, model, and MCP details out of core article and plan models.
- Cross-module calls target an application interface or documented shared contract. Do not introduce new dependencies on another module's mapper.
- `identity` owns users, JWT parsing, login sessions, and request identity. Other modules may read `UserContext` or call identity application services, but must not parse JWTs or alter login Redis keys.
- `blogagent` owns generation tasks, sources, versions, conversations, and events. It must not own schedules, platform credentials, or publication records.
- `publishing` owns articles and publication attempts. `automation` owns recurring plans and durable runs. `integration` owns protocol and vendor adapters.
- Avoid circular dependencies. Introduce an application port or move a shared concept to the module that owns its lifecycle.

## SQL and schema

- Every `CREATE TABLE` and `ALTER TABLE ... ADD COLUMN` includes Chinese `COMMENT` on the table and every column.
- Comments must explain enums, foreign keys, units, ownership, and lifecycle meaning rather than repeat names.
- Existing-table changes go in `src/main/resources/db/*_migration.sql`; keep baseline `*_schema.sql` files aligned.
- Never rely on `CREATE TABLE IF NOT EXISTS` to update an existing table; use explicit migration statements.
- Do not hide destructive schema changes in startup code or automatic entity synchronization.
- Add indexes for ownership filters, due-task scans, idempotency keys, foreign keys, and stable pagination. Document non-obvious query patterns.
- Store scheduling instants in UTC. Convert to a user's named time zone only at API and presentation boundaries.

## Transactions, concurrency, and idempotency

- Declare transactions at application-service use-case boundaries. Use `readOnly = true` for clear database-only reads.
- Keep AI, CSDN, OSS, SMTP, MCP, and other network calls outside database transactions.
- Persist intent first, call remotely, then persist the result in a separate transaction.
- Retryable mutations require a stable idempotency key backed by a database unique constraint or atomic Redis operation.
- State transitions use conditional updates containing the expected previous state. A zero-row update means a lost race or completed operation, not success.
- Multi-platform publication is not one distributed transaction. Persist each platform attempt independently.
- Never hold JVM locks across network or database calls. Multi-instance correctness uses database or Redis coordination.
- Keep compensation explicit. Do not silently delete durable task or publication history after partial failure.

## Schedulers and background work

- Persist plans, runs, attempts, next-run time, status, and last error. In-memory timers are not a source of truth.
- Pollers claim work atomically, use bounded batches, and support multiple instances without duplicate execution.
- Claimed jobs need a lease or stale-running recovery policy, maximum attempts, and bounded retry backoff.
- One item failure must not abort an entire scheduler batch; persist the failure and continue safely.
- Define downtime catch-up behavior explicitly: skip, run once, or replay missed occurrences.
- Use an injected clock or deterministic calculator in time-sensitive tests; do not depend on wall-clock timing or fixed sleeps.
- Long-running work exposes durable status and progress. SSE/client disconnects must not cancel committed work unless cancellation is explicit.

## External integrations

- Put each vendor or protocol integration behind a local interface and translate payloads/errors at the adapter boundary.
- Configure connection and request timeouts for every HTTP, model, OSS, and mail call; no production call may be unbounded.
- Reuse configured HTTP clients rather than constructing a new client per request.
- Retry only classified transient failures. Do not retry authentication/validation errors or non-idempotent publications without reconciliation.
- Bound response sizes, redirects, and downloaded media sizes. Validate content type and payload structure.
- Never expose raw vendor exceptions, response bodies, stack traces, or credential-bearing request details through public APIs.
- Adapter tests use mocks, fakes, or a mock HTTP server. Automated tests never publish real articles, send real mail, or upload to production storage.
- Record external IDs, URLs, trace IDs, attempts, and normalized error codes needed for support and reconciliation.

## Credentials and security

- Store passwords using BCrypt, Argon2, or another modern password hash. New code must not add plaintext passwords or fallback passwords.
- Store platform credentials per user and platform; global credential keys are forbidden for multi-user capabilities.
- Encrypt refresh tokens, cookies, and platform credentials at rest with authenticated encryption. Keep keys outside source control and version encrypted data for rotation.
- Never log passwords, verification codes, JWTs, cookies, API keys, authorization headers, credential blobs, or full third-party request headers.
- Rate-limit login, registration, verification codes, authorization handoff, publication, and expensive AI generation.
- Every private task, plan, media, and publication query scopes by the current user unless the resource is explicitly public.
- New private endpoints must update authorization rules and tests in the same change.
- Validate CORS origins, redirects, uploaded filenames, external URLs, and HTML content at their trust boundaries.

## Errors, APIs, and state

- Use stable machine-readable codes for authorization required, validation, conflict, rate limiting, transient external failure, and permanent failure.
- Map transport errors once at the API boundary. Services throw typed exceptions instead of returning `Result` or `ResponseEntity`.
- Do not catch broad `Exception` merely to return an empty object or generic success. Intentional degradation must be documented and logged with a normalized reason.
- Background mutations return a task/run ID rather than holding requests open until remote completion.
- Define states as constants or enums with documented transitions. Avoid new scattered string literals for statuses and stages.
- API timestamps must include an offset or have a documented configured zone; scheduler APIs include the user's named time zone.

## Logging and observability

- Use structured, parameterized logging; do not use `System.out`, `printStackTrace`, or concatenated log messages.
- Include safe identifiers such as task ID, plan ID, run ID, publication ID, and platform in background logs.
- Generate or propagate a correlation ID across Agent generation, scheduler runs, and publication adapters.
- Log an error once at the layer that can add actionable context; avoid logging and rethrowing at every layer.
- Use `INFO` for lifecycle milestones, `WARN` for handled degradation/retry, `ERROR` for terminal/unexpected failure, and `DEBUG` for high-volume details.
- Do not log full article bodies, private prompts, model responses, or uploaded binary data by default.
- Persist business-relevant failure state; logs alone are not durable task history.

## Configuration and dependencies

- Secrets and environment-specific endpoints come from environment variables or ignored local configuration.
- Checked-in defaults must be non-secret and safe for local failure.
- New configuration groups use typed `@ConfigurationProperties`, validate required production values, and document units/defaults.
- Features with credentials, schedulers, or external side effects are disabled by default until configuration and migrations are present.
- Add dependencies only when the JDK or existing stack cannot solve the problem cleanly. Centralize reused versions in `pom.xml` properties.
- Do not add automatic schema mutation, hidden startup migrations, or production-only behavior that cannot be tested locally.

## Testing and build gates

- Mirror each business module under `src/test/java`. Application services, security rules, schedulers, transitions, and adapters require behavior tests.
- Controller tests cover validation, authentication, ownership outcomes, and real HTTP status mapping. Bean Validation DTOs need contract tests.
- Test idempotency, duplicate delivery, stale claims, retry exhaustion, partial multi-platform failure, and restart recovery for durable workflows.
- Mapper interfaces without custom SQL do not need empty tests. Custom SQL, locking queries, migrations, and uniqueness assumptions need integration tests.
- Unit tests must not need network access, production credentials, a real mailbox, or a real publication account.
- Tests are deterministic and independent. Clear `ThreadLocal`, static state, temporary files, and mocked security context after each test.
- Run narrow relevant tests, then `mvn -q test`. Package migrations also run `mvn -q -DskipTests compile` before the suite.
- Do not weaken or delete a failing test to make the build pass. Bug fixes include a regression test.

## Java style and maintainability

- Use Java 17 features consistently with the existing Spring Boot 3 codebase.
- Prefer constructor injection and immutable dependencies; avoid field injection and mutable global state.
- Keep methods at one abstraction level. Extract policy, schedule calculation, payload conversion, and transitions when they obscure the use case.
- Use records for immutable internal values when serialization or MyBatis does not require mutable beans.
- Avoid boolean parameters that hide different commands; prefer explicit methods such as `createDraft` and `publish`.
- Validate at the API boundary and enforce invariants again in the owning service when data can enter through multiple adapters.
- Delete confirmed dead code instead of leaving commented-out implementations.
- Keep source files UTF-8. Do not introduce or preserve mojibake in comments, validation messages, API responses, or SQL comments in files being changed.

## Implementation safety

- Persist and make idempotent mutations that can outlive a client timeout.
- Preserve user changes in a dirty worktree and avoid rewriting unrelated files.
- Prefer small, behavior-preserving migration batches. Preserve routes, tables, serialized fields, config keys, and security behavior unless explicitly changing them.
- Add migrations for schema changes, compile before commit, and never commit local credentials or runtime configuration.

## 定时发文计划（blog-content-plan）要点

- 计划状态语义：ACTIVE=待执行、RUNNING=运行中、FINISHED=一次性计划成功终态、FAILED=失败终态；前端 6 语言文案与徽标需同步（`blogPlans.status*` key）。
- 一次性计划（run_once=1）终态 SQL（finishOneShotPlan / failOneShotPlan / recoverStaleOneShot）会把 `next_run_at` 置 NULL：该列必须保持可空（迁移 `20260814_blog_content_plan_next_run_nullable`），改回 NOT NULL 会让所有一次性计划卡死在 RUNNING。
- 调度器默认关闭：本地在 `application-local.yml` 开 `blog-content-plan.enabled: true`，生产靠部署脚本/`docker-compose.yml` 注入 `BLOG_CONTENT_PLAN_ENABLED=true`；迁移 SQL 需手工应用到线上库。
- 崩溃恢复必须区分成败：stale 一次性计划若已有 SUCCEEDED 的 run 则补记 FINISHED，否则标记 FAILED 并失败其 in-flight runs，避免把已发布成功的内容报成失败。

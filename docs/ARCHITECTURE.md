# Backend Architecture

The backend remains a modular monolith. Business capabilities are grouped by
feature; deployment and database topology stay unchanged.

## Business Modules

- `identity` (migrated): owns users, login, token refresh, JWT validation and request authorization.
- `blogagent` (migrated): generates and revises content, stores evidence and versions.
- `publishing` (migrated): owns blog posts, categories, tags, view records, publication commands, generic publication contracts, durable publication records, and blog media uploads.
- `automation`: will own recurring content plans and durable plan runs.
- `integration` (migrated): owns MCP protocol adapters, OSS/file/mail delivery adapters, and vendor-specific integrations such as the complete CSDN MCP flow.
- `studio` (migrated): owns FlowCraft projects, canvases and snapshots, plus the image-to-3D task pipeline and model client.
- `components` (migrated): owns the UI component library catalog, detail pages, and community-shared submissions.

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

The migration is incremental. Existing public HTTP routes, tables and runtime
configuration must remain compatible during package moves.

`integration.mcp.security` owns OAuth 2.1 discovery, PKCE authorization,
bearer-token validation, consent, and MCP request identity. It is distinct from
both browser JWT sessions and CSDN platform credentials. The `blog`, `csdn`,
and `dual` MCP modules own only their transport contracts and application flows.

## Migration Order

1. `identity` and `blogagent` establish the feature-first package pattern.
2. Move blog posts, categories, tags and media into `publishing`; the publication port and local adapter are now in place.
3. Move MCP, CSDN, model, OSS and mail adapters into `integration`.
4. Add recurring content plans and durable runs under `automation`.
5. Group Flow and Img2Three under `studio`.

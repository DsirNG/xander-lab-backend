# Backend Architecture

The backend remains a modular monolith. Business capabilities are grouped by
feature; deployment and database topology stay unchanged.

## Business Modules

- `identity` (migrated): owns users, login, token refresh, JWT validation and request authorization.
- `blogagent` (migrated): generates and revises content, stores evidence and versions.
- `publishing`: owns blog posts, publication commands, generic publication contracts, and durable publication records.
- `automation`: will own recurring content plans and durable plan runs.
- `integration`: owns MCP protocol adapters and vendor-specific integrations such as the complete CSDN MCP flow.
- `studio`: will group Flow and Img2Three creation tools.

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

`integration.mcp` now owns MCP client authorization, browser handoff, request
identity, and MCP-facing blog commands. A durable MCP client authorization maps
one external client installation to one application user; it is distinct from
both browser JWT sessions and CSDN platform credentials.

## Migration Order

1. `identity` and `blogagent` establish the feature-first package pattern.
2. Move blog posts, categories, tags and media into `publishing`; the publication port and local adapter are now in place.
3. Move MCP, CSDN, model, OSS and mail adapters into `integration`.
4. Add recurring content plans and durable runs under `automation`.
5. Group Flow and Img2Three under `studio` after their active changes settle.

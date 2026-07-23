---
name: xander-backend
description: Implement, modify, review, or troubleshoot Xander Lab's Spring Boot backend. Use for API controllers, authentication, Result responses, exceptions, MyBatis, Redis, database migrations, and long-running task workflows.
---

# Xander Backend

Read the repository `AGENTS.md` before modifying code. This skill defines backend-specific rules that must accompany every API change.

## API error contract

- All API errors use `{ "code": number, "message": string, "data": null }`.
- The HTTP status must match the error semantics. Never return HTTP 200 for a body with an error code.
- Use real HTTP 400, 401, 403, 404, and 5xx responses. Map custom business codes to their semantic HTTP status before returning them.
- Missing, invalid, or expired authentication always returns HTTP 401 with the canonical login-expired message.
- Use `GlobalExceptionHandler` for thrown exceptions and preserve the shared Result contract. Do not handwrite ad hoc response bodies in controllers.

## Authentication and persistence

- Keep private routes explicit in `AuthInterceptor`; verify authorization before returning protected data.
- Keep remote model and network calls outside database transactions.
- Persist and make idempotent any mutation that might outlive a client timeout, including publishing and agent tasks.
- Schema changes require a migration. Never commit runtime credentials or local configuration.

## Completion checklist

- Compile the backend after code changes.
- Inspect status-code behavior when changing controller or exception logic.
- Stage and commit only files relevant to the request.

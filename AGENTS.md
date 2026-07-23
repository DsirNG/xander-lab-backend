# Xander Lab Backend Rules

## API and authentication

- Return the shared `{ "code": number, "message": string, "data": null }` error shape.
- Keep every Result error code aligned with a real HTTP status. Standard 4xx/5xx codes are returned directly; custom business codes require an explicit semantic status mapping.
- Authentication failures use actual HTTP 401 and the canonical login-expired response message; never return HTTP 200 for an expired, missing, or invalid token.
- Keep private APIs explicit in `AuthInterceptor`; do not let public wildcard patterns expose task or user data.
- Use `GlobalExceptionHandler` and domain exceptions consistently instead of writing ad hoc error bodies.

## Implementation safety

- Keep remote model and network calls outside database transactions.
- Persist and make idempotent mutations that can outlive a client timeout, such as publication or background tasks.
- Add migrations for existing-table schema changes, compile before commit, and never commit local credentials or runtime configuration.

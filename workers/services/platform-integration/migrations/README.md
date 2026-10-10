`0001_platform_credentials.sql` creates the Integration-owned per-user CSDN/Juejin
credential table. Credential payloads are AES-GCM encrypted with the stable
`PLATFORM_CREDENTIAL_ENCRYPTION_KEY`, bound to both provider and owner, and
never returned by status APIs. Trusted service calls can import credentials
during a staged data transfer.

`0002_juejin_catalog.sql` stores the shared Juejin category/tag projection and
its last successful sync time. The internal catalog command fetches category
hot tags and up to ten pages of global tags before writing D1. Provider IDs are
kept as decimal text so 64-bit IDs are not rounded by JavaScript. The protected
Juejin publication boundary now creates and publishes provider drafts from an
explicit article projection; Content Automation keeps the idempotency record.
`0003_platform_file_assets.sql` adds file metadata; file bytes are stored in
Alibaba OSS. Local Wrangler does not need OSS credentials for Worker startup,
but upload requests return a configuration error until they are provided.
`0004_platform_authorization.sql` adds one browser slot per provider and an
owner-scoped FIFO authorization queue. `0005_mcp_oauth.sql` adds OAuth client,
consent, authorization-code, token-family, and rate-limit records; token values
are stored only as hashes. Public CSDN and Juejin post-sync routes delegate to
Content's protected publication endpoint, which owns the durable ledger.

Apply local migrations with:

```powershell
pnpm exec wrangler d1 migrations apply dinqor-platform-integration-local --local --config wrangler.jsonc
```

`0001_platform_credentials.sql` creates the Integration-owned per-user credential
table. Credential payloads are AES-GCM encrypted with the stable
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
`0006_platform_extension_authorization.sql` stores short-lived extension handoff
tasks and only the hash of each one-use transfer token. The extension completion
route is separately excluded from DinQor login JWT checks; the Platform Worker
validates the task token before saving credentials through the existing
AES-GCM-encrypted credential service. CSDN and Juejin use this flow for their
existing one-click publishing adapters; other registered platforms use it for
account binding only.
`0007_expand_platform_credential_platforms.sql` rebuilds the credential table
with the supported connection-platform identifiers while preserving existing
encrypted CSDN/Juejin rows.
`0008_platform_account_profile.sql` adds owner-scoped display metadata for verified
platform profiles; it never stores cookies or authorization tokens in that column.

Apply local migrations with:

```powershell
pnpm exec wrangler d1 migrations apply dinqor-platform-integration-local --local --config wrangler.jsonc
```
# 0008 平台账号展示资料

`0008_platform_account_profile.sql` 增加当前用户已验证的昵称、头像和平台账号标识。
授权时间继续使用 `authorized_at`，连通性验证仅更新 `last_validated_at`；重新授权会清空旧资料。
更新使用凭据版本条件，防止旧验证覆盖重新授权或断开连接后的账号。

# 0009 扩展授权平台范围

`0009_expand_platform_extension_authorization_platforms.sql` 重建短时扩展授权任务表并保留现有任务，
将平台约束扩展到全部支持账号绑定的平台。线上旧表的约束仍只允许 CSDN 和掘金；修改已执行的
`0006` 文件不会改变生产 D1，因此必须通过这条新迁移开放微信公众号等平台的授权任务。

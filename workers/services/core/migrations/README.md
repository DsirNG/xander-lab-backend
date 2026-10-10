Core owns the identity user table and will own login/session, billing, and
configuration tables as their routes migrate. Other Workers keep their own
schemas and must not query this D1 database. Do not copy the whole MySQL schema
here; migrate only Core-owned tables.

The initial schema creates sys_user for profile reads and updates. Migration
0002 stores hashed access-token revocations through their JWT expiry; hourly
cleanup removes expired fingerprints. Migration 0003 adds an account auth
version used to revoke every prior token after a password change or reset.
Migration 0005 adds Core-owned model-provider and feature-model configuration,
encrypted WeChat credentials, and virtual-pay settings. Model-provider admin
routes stay disabled until `ADMIN_MODEL_GATEWAY_ENABLED=true`; set
`MODEL_PROVIDER_ENCRYPTION_KEY` for provider API keys and
`WECHAT_CREDENTIAL_ENCRYPTION_KEY` for WeChat and virtual-pay secrets. Both
encryption keys must be Base64-encoded 32-byte AES keys and remain stable while
ciphertext is stored.
Migration 0006 adds Core-owned recitation materials, audio-attempt metadata, and
quiz history. Recitation material CRUD and history reads are served by the Core
Worker. When `RECITATION_ENABLED=true`, authenticated multipart audio uploads
go to Aliyun OSS and D1 records only owner, task and object-key metadata. Core's
scheduled worker submits and polls DashScope ASR through the `recitation_asr`
feature-model configuration, then stores an aligned report and updates mastery
in an atomic D1 batch. The flag defaults off; configure OSS access keys and an
enabled DashScope ASR feature model before enabling it. No audio bytes are stored
in D1.
Migration 0007 adds an idempotent Payment entitlement event ledger. Its D1
trigger extends the user's active subscription once per virtual-payment trade;
Payment calls the protected Core contract after provider confirmation. Migration
0008 keeps the highest subscription tier while extending the expiry for every
new verified purchase, including a purchase at a lower tier.
Migration 0009 adds Core-owned refresh sessions, bounded rotation history, and
reuse-event records. The default remains Java-compatible stateless JWT refresh.
To enable rotation, set `AUTH_REFRESH_PROTOCOL_ENABLED=true` and provide a
stable `AUTH_REFRESH_ENCRYPTION_KEY` containing a Base64-encoded 32-byte AES
key; session lifetime defaults to 30 days, replay grace to 25 seconds, and
reuse detection retention to 24 hours. Password changes, administrator status
changes, and logout revoke the corresponding D1 refresh sessions.
Production user data is not imported by
local D1 migrations; the migration plan must define an explicit export,
ID-preserving import, and checksum process before cutover.

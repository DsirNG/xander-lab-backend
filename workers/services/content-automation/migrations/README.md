This D1 migration directory contains Content Automation's article, publication,
notification, blog plan and run, BlogAgent task/session, email reminder, and
article idempotency schemas. Publication attempt tokens and lease deadlines
fence concurrent Agent retries while the Worker calls Platform. The CSDN
cooldown is claimed atomically per user; the daily cap counts successful
publication records in the current UTC day. Article idempotency keys are unique
per owner and are bound to a SHA-256 request fingerprint. Migration `0010`
stores the owner-scoped reminder schedule and delivery lease. Migration `0011`
stores short leases for active notification SSE connections and expiring
diagnostic broadcasts; diagnostic events are never inserted into the durable
per-user notification table. Migration `0012` stores owner-scoped image metadata;
image bytes remain in Aliyun OSS under the configured media object prefix.

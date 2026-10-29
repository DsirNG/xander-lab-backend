This D1 migration directory contains Content Automation's article, publication,
notification, blog plan and run, BlogAgent task/session, email reminder, and
article idempotency, and multi-article series schemas. Publication attempt tokens and lease deadlines
fence concurrent Agent retries while the Worker calls Platform. The CSDN
cooldown is claimed atomically per user; the daily cap counts successful
publication records in the current UTC day. Article idempotency keys are unique
per owner and are bound to a SHA-256 request fingerprint. Migration `0010`
stores the owner-scoped reminder schedule and delivery lease. Migration `0011`
stores short leases for active notification SSE connections and expiring
diagnostic broadcasts; diagnostic events are never inserted into the durable
per-user notification table. Migration `0012` stores owner-scoped image metadata;
image bytes remain in Aliyun OSS under the configured media object prefix.

Migration `0013` persists editable content-series drafts and links activated one-shot article plans by series and day. Series activation inserts all child plans and changes the series state in one D1 batch; only Local, CSDN, and Juejin are currently selectable for publication.

Migration `0014` persists each BlogAgent task's resolved generation mode. Existing tasks stay on the full workflow; new ordinary writing requests use the quick path unless they need research and independent review.

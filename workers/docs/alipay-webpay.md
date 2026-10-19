# Alipay website payments

The active implementation is `services/payment`, using official `alipay-sdk`
4.14.0 in public-key mode. It implements `alipay.trade.page.pay`, query, close,
full refund, refund query and signed asynchronous notifications. The website
offers plans in Settings → Subscription & payment and returns to
`/payment/alipay/return`. Existing WeChat virtual payment is independent.

## Configuration and activation

The capability is disabled unless `ALIPAY_ENABLED=true`. Configure the Payment
Worker only; never put application private keys in Vite variables or source
control. Use ignored `.dev.vars` for local testing and Wrangler Secrets for
deployment. Do not send production private keys to an agent.

| Variable | Requirement |
| --- | --- |
| `ALIPAY_ENABLED` | Exact `true` to activate; omitted means disabled |
| `ALIPAY_ENVIRONMENT` | Explicit `sandbox` or `production` |
| `ALIPAY_APP_ID` | Application ID for that environment |
| `ALIPAY_SELLER_ID` | Matching seller ID; verified on notifications |
| `ALIPAY_PRIVATE_KEY` | Original raw PKCS#1 application private key, without PEM wrapping |
| `ALIPAY_PUBLIC_KEY` | Original raw Alipay public key for the same application/environment |
| `ALIPAY_RETURN_URL` | Actual frontend origin plus `/payment/alipay/return`; local HTTP permitted only in sandbox |
| `ALIPAY_NOTIFY_URL` | Public HTTPS gateway origin plus `/api/alipay/notify`; mandatory in production |
| `ALIPAY_PRODUCTS` | JSON array of `{ productId, tier, priceCents, durationDays }`; `tier` is PLUS, PRO or ULTRA; prices are integer CNY cents |
| `ALIPAY_REFUNDS_ENABLED` | Default false; enable only after an operator process for reviewing/refunding subscription entitlements exists |

Apply `services/payment/migrations/0002_alipay_webpay.sql` before activation.
Local application uses:

```text
pnpm --filter @dinqor/payment-worker exec wrangler d1 migrations apply dinqor-payment-local --local
```

The checked-in D1 identifier is a local placeholder. Production migration and
deployment use the deployment environment's actual database configuration.
No production migration or deployment was performed during implementation.

Production requires HTTPS return/notification URLs. Local sandbox may omit
the notification URL while using verified query reconciliation, but notification
delivery from Alipay must be tested before production activation. The merchant
must separately complete product contracting and application publication.

## API and ownership

All routes go through the Gateway and its generated access policy. Payment
requires the internal service token. Only `POST /api/alipay/notify` bypasses
user login; Payment verifies its RSA2 signature, application ID, seller ID,
order number, amount and event type.

| Method | Path | Access |
| --- | --- | --- |
| GET | `/api/alipay/products` | Logged-in user |
| POST | `/api/alipay/orders` | Logged-in user; `{ productId }` only, `Idempotency-Key` required |
| GET | `/api/alipay/orders/{outTradeNo}` | Owner |
| POST | `/api/alipay/orders/{outTradeNo}/reconcile` | Owner; signed provider query |
| POST | `/api/alipay/orders/{outTradeNo}/close` | Owner; rechecks provider before closing an unpaid trade |
| POST | `/api/alipay/notify` | Independent Alipay signature |
| POST | `/api/admin/alipay/orders/{outTradeNo}/refund` | Admin plus explicit refund activation |
| GET | `/api/admin/alipay/orders/{outTradeNo}/refund-status` | Admin |

The browser submits only the SDK-generated POST form to the matching fixed
Alipay gateway. Response scripts are discarded. Checkout responses suppress
the shared HTTP client's debug logging. The result page ignores callback
parameters, loads only an order saved in the current account's browser session,
and queries the owner-scoped backend. Opening the page without an order renders
a neutral state. Query-string assertions never activate a subscription.

## State and recovery

Orders snapshot their configured product, price, duration, application, seller
and environment. `(user_id, idempotency_key)` prevents duplicate creation.
Different products cannot share a checkout key.

Verified payments move `PENDING → PAID → DELIVERED`. Core's existing unique
`virtualpay:ALIPAY_{outTradeNo}` event grants the subscription exactly once.
A failed Core call leaves `PAID`; callback replay or an owner reconciliation
can resume it. Notification audit records are saved after fulfillment so failed
delivery is retried. `TRADE_SUCCESS`/`TRADE_FINISHED` refund notifications are
excluded from payment delivery. A trade number cannot be substituted later.

Closing an unpaid provider trade moves `PENDING → CLOSED`. Unknown/pending
provider states stay pending; a signed trade-not-found response also stays
pending. No automatic polling is introduced. The user may refresh the result
page to run reconciliation.

Only full refunds are offered. Every order has one persistent refund request
number and a fixed refund amount, so retries cannot exceed the original charge.
Uncertain results retain `REFUND_PENDING` and the same request number. Only
`fund_change=Y` or a matching `REFUND_SUCCESS` query marks a refund successful.
Wait at least 10 seconds before refund-status reconciliation.

**Subscription refunds need operator review.** Core's current entitlement
ledger has no reversal contract. Refunds therefore remain disabled by default;
the refund record/response explicitly retains `entitlementReviewRequired=true`.
No automatic reversal of an already granted subscription is claimed. Establish
the entitlement adjustment process before enabling the admin refund switch.

The official SDK owns request signatures, form serialization, callback
verification and response verification (`validateSign=true`). Worker aliases
replace urllib sockets with bounded native fetch and suppress SDK debuglog
output containing signed requests/raw responses. Only the V2 form/text transport
contract is supported; proxies, redirects and non-Alipay destinations are rejected.

## Validation and remaining external work

```text
pnpm check
pnpm smoke:alipay
```

`smoke:alipay` bundles the actual Worker and runs official SDK signing, D1 order
creation, response verification/tamper rejection, callback replay and entitlement
delivery under workerd. All provider responses and keys in this smoke test are
ephemeral local fixtures; no Alipay network call or real-money transaction occurs.
It is **not** a real Alipay sandbox payment test.

At implementation time the official Skill's Windows jq download failed and
selected `CODE_ONLY`. The real sandbox configuration/payment, public notification
delivery, merchant contracting and application publication remain uncompleted.
After restoring access to the official jq download, resume the installed
`alipay-aipay-pre` Skill and recheck its environment before those steps. Do not
treat local tests or a successful bundle as evidence of signing, sandbox payment
or production readiness.

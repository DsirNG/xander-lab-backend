`0001_payment_settings.sql` creates Payment-owned virtual-pay configuration
and order storage. The admin settings key is AES-GCM encrypted with the
Payment-only `PAYMENT_SETTINGS_ENCRYPTION_KEY`; the settings API never returns
the key. The public catalog reads only enabled, valid products. Creating an
order verifies the bound WeChat identity through Core and stores a pending
order before returning client signatures. Status reads are user-scoped.
Reconciliation queries the WeChat order, requires status 2/3/4, validates the
paid amount, then grants through Core's idempotent event contract before
marking the Payment order delivered. Signed XML notifications use the configured
`WECHAT_VIRTUAL_PAY_MESSAGE_TOKEN`, match the stored OpenID, product, and
quantity, and share the same one-time entitlement key. The local Gateway smoke
test exercises callback replay and state transitions without live provider
credentials; login-code exchange and query-order still require WeChat setup.

`0002_alipay_webpay.sql` adds separate Alipay website orders, notification audit
records and full-refund intent records. Prices are CNY cents. Environment and
application/seller identity are snapshotted per order. Checkout and Core grant
keys are persistent; the refund amount and request number remain stable on
retries. No existing WeChat schema is changed. Alipay is disabled by default.
See [the integration guide](../../../docs/alipay-webpay.md) for activation,
Workers transport restrictions, local validation and outstanding external tests.

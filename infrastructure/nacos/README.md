# Nacos configuration layout

This directory contains the non-secret Nacos DataIds described by V9 §16. The service runtime
defaults keep discovery disabled for local development; the opt-in `nacos` profile enables
discovery and deployment supplies the server address, namespace and secrets.

Stable DataIds and Groups:

- `dinqor-shared.yaml` / `COMMON`: platform-wide non-secret defaults.
- `dinqor-core.yaml` / `DINQOR-CORE`: `dinqor-core-host` private settings.
- `dinqor-gateway.yaml` / `DINQOR-GATEWAY`: Gateway routes and edge settings.
- `dinqor-agent.yaml` / `DINQOR-AGENT`: Agent host private settings.
- `dinqor-platform-integration.yaml` / `DINQOR-PLATFORM-INTEGRATION`: Platform integration private settings.
- `dinqor-content-automation.yaml` / `DINQOR-CONTENT-AUTOMATION`: Content automation private settings.

The DataId groups above are configuration ownership boundaries. Nacos service discovery uses a
separate shared group, `COMMON`, by default (`NACOS_DISCOVERY_GROUP` can override it). Keeping
discovery in one group is required for Gateway `lb://...` routes to resolve instances registered
by another service; changing a service's private config group must not break discovery.

`dinqor-gateway.yaml` is the source of truth for the fallback route, split-service route
destinations and stream proxy settings; there is no second checked-in gateway overlay to drift
from the Nacos DataId. The Java route definitions only provide the route predicates and safe
defaults; production destination overrides belong in this private DataId.
The global Gateway response timeout is intentionally omitted: a negative duration causes the
Spring Cloud Gateway proxy to return 504 for ordinary proxied requests in the Nacos profile.
Streaming behavior is controlled by the stream-proxy settings and route-specific policy.

Passwords, JWT secrets, API keys, encryption keys and database credentials never belong in these
files. They are provided by deployment secrets or environment variables.

Runtime activation:

- set `SPRING_PROFILES_ACTIVE=nacos` for `dinqor-core-host`, `dinqor-gateway`,
  `dinqor-agent`, `dinqor-platform-integration`, `dinqor-content-automation` and
  `dinqor-bloghtml-preview`;
- set `NACOS_SERVER_ADDR`, `NACOS_NAMESPACE` and `NACOS_CONFIG_ENABLED=true`;
- keep local development on the default profile, where Nacos Config is not imported;
  the `nacos` profile fails startup when either required DataId cannot be loaded.

Each `application-nacos.yml` imports exactly two DataIds: `dinqor-shared.yaml` from `COMMON`
and the service-private DataId from its stable logical Group. Wave 2 services use the same
stable logical names during the compatibility cutover; only Gateway traffic and internal
tokens decide when a route becomes live.

The `nacos` profile also switches `dinqor-core-host` to the `dinqor_core` runtime account;
`SPRING_DATASOURCE_PASSWORD` is still required from deployment Secret. The standalone
`migration-runner` keeps using `dinqor_migration` and is never activated by a business-service
profile.

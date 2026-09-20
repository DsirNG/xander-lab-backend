# Nacos configuration layout

This directory contains the non-secret Nacos DataIds described by V9 §16. The service runtime
defaults keep discovery disabled for local development; the opt-in `nacos` profile enables
discovery and deployment supplies the server address, namespace and secrets.

Stable DataIds and Groups:

- `dinqor-shared.yaml` / `COMMON`: platform-wide non-secret defaults.
- `dinqor-core.yaml` / `DINQOR-CORE`: `dinqor-core-host` private settings.
- `dinqor-gateway.yaml` / `DINQOR-GATEWAY`: Gateway routes and edge settings.

`dinqor-gateway.yaml` is the source of truth for the fallback route and stream proxy settings;
there is no second checked-in gateway overlay to drift from the Nacos DataId.

Passwords, JWT secrets, API keys, encryption keys and database credentials never belong in these
files. They are provided by deployment secrets or environment variables.

Runtime activation:

- set `SPRING_PROFILES_ACTIVE=nacos` for `dinqor-core-host`, `dinqor-gateway` and
  `dinqor-bloghtml-preview`;
- set `NACOS_SERVER_ADDR`, `NACOS_NAMESPACE` and `NACOS_CONFIG_ENABLED=true`;
- keep local development on the default profile, where Nacos Config is not imported;
  the `nacos` profile fails startup when either required DataId cannot be loaded.

Each `application-nacos.yml` imports exactly two DataIds: `dinqor-shared.yaml` from `COMMON`
and the service-private DataId from its stable logical Group.

The `nacos` profile also switches `dinqor-core-host` to the `dinqor_core` runtime account;
`SPRING_DATASOURCE_PASSWORD` is still required from deployment Secret. The standalone
`migration-runner` keeps using `dinqor_migration` and is never activated by a business-service
profile.

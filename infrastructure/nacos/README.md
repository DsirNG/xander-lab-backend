# Nacos configuration layout

This directory contains the non-secret deployment overlays described by V9 §16. The service
runtime defaults keep discovery disabled for local development; deployment enables it with
`NACOS_DISCOVERY_ENABLED=true` and injects the server address and namespace.

Stable DataIds and Groups:

- `dinqor-shared.yaml` / `COMMON`: platform-wide non-secret defaults.
- `dinqor-core.yaml` / `DINQOR-CORE`: `dinqor-core-host` private settings.
- `dinqor-gateway.yaml` / `DINQOR-GATEWAY`: Gateway routes and edge settings.

Passwords, JWT secrets, API keys, encryption keys and database credentials never belong in these
files. They are provided by deployment secrets or environment variables.

Runtime activation:

- set `SPRING_PROFILES_ACTIVE=nacos` for `dinqor-core-host`, `dinqor-gateway` and
  `dinqor-bloghtml-preview`;
- set `NACOS_SERVER_ADDR`, `NACOS_NAMESPACE` and `NACOS_CONFIG_ENABLED=true`;
- keep local development on the default profile, where Nacos Config is not imported.

Each `application-nacos.yml` imports exactly two DataIds: `dinqor-shared.yaml` from `COMMON`
and the service-private DataId from its stable logical Group.

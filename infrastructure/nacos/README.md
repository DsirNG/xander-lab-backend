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

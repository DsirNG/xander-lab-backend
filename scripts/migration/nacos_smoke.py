#!/usr/bin/env python3
"""Verify the local Nacos bootstrap and registered DinQorAI services.

The script intentionally uses only the Python standard library so it can run in a
fresh verification environment. It does not print configuration values; it only
checks that the expected DataIds are present and non-empty.
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from urllib.parse import urlencode
from urllib.error import HTTPError
from urllib.request import Request, urlopen


@dataclass(frozen=True)
class ServiceCheck:
    name: str
    readiness_port: int
    minimum_instances: int


DATA_IDS = (
    ("dinqor-shared.yaml", "COMMON"),
    ("dinqor-core.yaml", "DINQOR-CORE"),
    ("dinqor-gateway.yaml", "DINQOR-GATEWAY"),
    ("dinqor-bloghtml-preview.yaml", "DINQOR-BLOGHTML-PREVIEW"),
    ("dinqor-agent.yaml", "DINQOR-AGENT"),
    ("dinqor-platform-integration.yaml", "DINQOR-PLATFORM-INTEGRATION"),
    ("dinqor-content-automation.yaml", "DINQOR-CONTENT-AUTOMATION"),
)

SERVICES = (
    ServiceCheck("dinqor-core-host", 30012, 1),
    ServiceCheck("dinqor-gateway", 30017, 1),
    ServiceCheck("dinqor-bloghtml-preview", 30019, 1),
    ServiceCheck("dinqor-agent", 30018, 2),
    ServiceCheck("dinqor-platform-integration", 30015, 1),
    ServiceCheck("dinqor-content-automation", 30016, 2),
)


def get(base_url: str, path: str, params: dict[str, str]) -> tuple[int, bytes]:
    query = urlencode(params)
    request = Request(f"{base_url.rstrip('/')}{path}?{query}")
    with urlopen(request, timeout=10) as response:  # noqa: S310 - caller controls local URL
        return response.status, response.read()


def get_url(url: str) -> tuple[int, bytes]:
    """Read a local URL while preserving expected HTTP error statuses."""
    try:
        with urlopen(Request(url), timeout=10) as response:  # noqa: S310 - local smoke URL
            return response.status, response.read()
    except HTTPError as response:
        return response.code, response.read()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nacos-url", default="http://localhost:8848")
    parser.add_argument("--namespace", default="dinqor-dev")
    parser.add_argument(
        "--skip-readiness",
        action="store_true",
        help="Only verify Nacos health/config/discovery, not local service ports",
    )
    parser.add_argument(
        "--gateway-port",
        type=int,
        default=30017,
        help="Local host port of the Nacos-enabled Gateway smoke instance",
    )
    args = parser.parse_args()
    base_url = args.nacos_url.rstrip("/")

    try:
        status, _ = get(base_url, "/nacos/actuator/health", {})
        require(status == 200, f"Nacos health returned HTTP {status}")

        status, body = get(base_url, "/nacos/v1/console/namespaces", {})
        require(status == 200, f"Nacos namespace API returned HTTP {status}")
        namespace_response = json.loads(body)
        namespaces = namespace_response.get("data", namespace_response)
        require(
            any(item.get("namespace") == args.namespace for item in namespaces),
            f"Nacos namespace is missing: {args.namespace}",
        )

        for data_id, group in DATA_IDS:
            status, body = get(
                base_url,
                "/nacos/v1/cs/configs",
                {"dataId": data_id, "group": group, "tenant": args.namespace},
            )
            require(status == 200 and body.strip(), f"Nacos DataId is missing or empty: {data_id}")

        for service in SERVICES:
            status, body = get(
                base_url,
                "/nacos/v1/ns/instance/list",
                {
                    "serviceName": service.name,
                    "groupName": "COMMON",
                    "namespaceId": args.namespace,
                    "healthyOnly": "false",
                },
            )
            require(status == 200, f"Nacos service API returned HTTP {status}: {service.name}")
            hosts = json.loads(body).get("hosts", [])
            require(
                len(hosts) >= service.minimum_instances,
                f"Nacos service has {len(hosts)} instance(s), expected >= "
                f"{service.minimum_instances}: {service.name}",
            )

        if not args.skip_readiness:
            for service in SERVICES:
                status, _ = get(
                    f"http://localhost:{service.readiness_port}",
                    "/actuator/health/readiness",
                    {},
                )
                require(status == 200, f"readiness returned HTTP {status}: {service.name}")

            gateway_url = f"http://localhost:{args.gateway_port}"
            status, _ = get_url(f"{gateway_url}/api/blog/categories")
            require(
                status == 200,
                f"Gateway -> Content public route returned HTTP {status}",
            )
            status, _ = get_url(f"{gateway_url}/api/publishing/csdn/authorization/status")
            require(
                status == 401,
                f"Gateway protected Platform route returned HTTP {status}, expected 401",
            )
    except (OSError, ValueError, RuntimeError) as exc:
        print(f"FAIL nacos-smoke: {exc}", file=sys.stderr)
        return 1

    print(
        f"PASS nacos-smoke: health, namespace={args.namespace}, "
        f"{len(DATA_IDS)} DataIds, {len(SERVICES)} services, readiness verified"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

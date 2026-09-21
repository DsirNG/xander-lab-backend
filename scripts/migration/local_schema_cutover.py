#!/usr/bin/env python3
"""Merge a legacy local schema into the canonical local schema.

The Wave 1/Wave 2 deployment contract uses one physical MySQL database with
``xander_lab`` as the canonical schema. Earlier local smoke containers may still
have data in a legacy ``dinqor`` schema. This command copies only rows that are
not already present in the target (``INSERT IGNORE``), never drops either schema,
never changes account passwords, and never revokes the legacy Core grant.

It is deliberately restricted to Docker containers whose names start with
``dinqor-``. Production data movement belongs to a reviewed migration job, not
this local verification helper.
"""

from __future__ import annotations

import argparse
import json
import re
import shlex
import subprocess
import sys
from pathlib import Path
from typing import Sequence


MYSQL_IMAGE = "mysql:8.0.36"
CORE_GRANT_FILE = Path(__file__).resolve().parents[2] / "migrations" / "grants" / "dinqor_core.sql"


def docker_inspect(container: str) -> dict[str, object]:
    completed = subprocess.run(
        ["docker", "inspect", container],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    if completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip()
        raise RuntimeError(f"cannot inspect Docker container {container}: {detail}")
    payload = json.loads(completed.stdout)
    if not payload:
        raise RuntimeError(f"Docker container not found: {container}")
    return payload[0]


def container_environment(metadata: dict[str, object]) -> dict[str, str]:
    config = metadata.get("Config")
    if not isinstance(config, dict):
        raise RuntimeError("Docker container has no Config metadata")
    raw = config.get("Env") or []
    values: dict[str, str] = {}
    for item in raw:
        if not isinstance(item, str):
            continue
        key, separator, value = item.partition("=")
        if separator:
            values[key] = value
    return values


def network_name(metadata: dict[str, object], preferred: str | None) -> str:
    if preferred:
        return preferred
    settings = metadata.get("NetworkSettings")
    networks = settings.get("Networks") if isinstance(settings, dict) else None
    if not isinstance(networks, dict) or not networks:
        raise RuntimeError("MySQL container is not attached to a Docker network")
    if "dinqor-runtime" in networks:
        return "dinqor-runtime"
    return next(iter(networks))


def run_mysql(container: str, root_password: str, sql: str) -> str:
    completed = subprocess.run(
        [
            "docker",
            "exec",
            "-e",
            f"MYSQL_PWD={root_password}",
            container,
            "mysql",
            "-uroot",
            "-N",
            "-B",
            "-e",
            sql,
        ],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    if completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip()
        raise RuntimeError(f"MySQL command failed ({completed.returncode}): {detail}")
    return completed.stdout.strip()


def existing_tables(container: str, root_password: str, database: str) -> set[str]:
    result = run_mysql(
        container,
        root_password,
        "SELECT table_name FROM information_schema.tables "
        f"WHERE table_schema = {sql_string(database)};",
    )
    return {line.strip() for line in result.splitlines() if line.strip()}


def sql_string(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def assert_database_exists(container: str, root_password: str, database: str) -> None:
    result = run_mysql(
        container,
        root_password,
        "SELECT SCHEMA_NAME FROM information_schema.schemata "
        f"WHERE SCHEMA_NAME = {sql_string(database)};",
    )
    if result.strip() != database:
        raise RuntimeError(f"database does not exist: {database}")


def merge_rows(
    container: str,
    root_password: str,
    network: str,
    source: str,
    target: str,
    mysql_image: str,
) -> None:
    quoted_source = shlex.quote(source)
    quoted_target = shlex.quote(target)
    # The password is supplied through MYSQL_PWD to both client processes. It is
    # not interpolated into the shell command or emitted in an error message.
    command = (
        f"mysqldump -h {shlex.quote(container)} -uroot "
        "--no-create-info --skip-triggers --skip-lock-tables "
        "--insert-ignore --set-gtid-purged=OFF "
        f"{quoted_source} | mysql -h {shlex.quote(container)} -uroot {quoted_target}"
    )
    completed = subprocess.run(
        [
            "docker",
            "run",
            "--rm",
            "--network",
            network,
            "-e",
            f"MYSQL_PWD={root_password}",
            mysql_image,
            "sh",
            "-ec",
            command,
        ],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    if completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip()
        raise RuntimeError(f"local schema merge failed ({completed.returncode}): {detail}")


def core_grant_tables() -> list[str]:
    tables: list[str] = []
    pattern = re.compile(r"ON `xander_lab`\.`([^`]+)`")
    for line in CORE_GRANT_FILE.read_text(encoding="utf-8").splitlines():
        match = pattern.search(line)
        if match and match.group(1) not in tables:
            tables.append(match.group(1))
    return tables


def apply_existing_core_grants(
    container: str,
    root_password: str,
    target: str,
    tables: set[str],
) -> list[str]:
    pattern = re.compile(r"ON `xander_lab`\.`([^`]+)`")
    skipped: list[str] = []
    selected: list[str] = []
    for line in CORE_GRANT_FILE.read_text(encoding="utf-8").splitlines():
        if line.startswith("CREATE USER IF NOT EXISTS"):
            continue
        match = pattern.search(line)
        if match and match.group(1) not in tables:
            if match.group(1) not in skipped:
                skipped.append(match.group(1))
            continue
        selected.append(line)

    sql = "\n".join(selected) + "\n"
    completed = subprocess.run(
        [
            "docker",
            "exec",
            "-i",
            "-e",
            f"MYSQL_PWD={root_password}",
            container,
            "mysql",
            "-uroot",
            target,
        ],
        input=sql,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    if completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip()
        raise RuntimeError(f"Core grant application failed ({completed.returncode}): {detail}")
    return skipped


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", default="dinqor-refactor-mysql")
    parser.add_argument("--network", help="Docker network; defaults to dinqor-runtime when present")
    parser.add_argument("--source", default="dinqor")
    parser.add_argument("--target", default="xander_lab")
    parser.add_argument("--mysql-image", default=MYSQL_IMAGE)
    parser.add_argument("--apply", action="store_true", help="copy missing rows into the target schema")
    parser.add_argument(
        "--apply-core-grants",
        action="store_true",
        help="apply generated DML grants for Core tables that already exist in the target",
    )
    parser.add_argument(
        "--allow-missing-grant-tables",
        action="store_true",
        help="allow local baselines to omit legacy tables listed by the generated grant file",
    )
    args = parser.parse_args(argv)

    if not args.container.startswith("dinqor-"):
        raise RuntimeError("refusing non-local container; use a container name beginning with dinqor-")
    if args.source == args.target:
        raise RuntimeError("source and target schemas must differ")
    if args.apply_core_grants and not args.apply:
        raise RuntimeError("--apply-core-grants requires --apply")

    metadata = docker_inspect(args.container)
    environment = container_environment(metadata)
    root_password = environment.get("MYSQL_ROOT_PASSWORD")
    if not root_password:
        raise RuntimeError("MYSQL_ROOT_PASSWORD is not present in the local MySQL container")
    network = network_name(metadata, args.network)

    assert_database_exists(args.container, root_password, args.source)
    assert_database_exists(args.container, root_password, args.target)
    target_tables = existing_tables(args.container, root_password, args.target)
    missing_grants = [table for table in core_grant_tables() if table not in target_tables]
    if missing_grants and args.apply_core_grants and not args.allow_missing_grant_tables:
        raise RuntimeError(
            "target is missing generated Core grant tables; pass "
            "--allow-missing-grant-tables only for an explicitly accepted local baseline"
        )

    if args.apply:
        merge_rows(
            args.container,
            root_password,
            network,
            args.source,
            args.target,
            args.mysql_image,
        )
        if args.apply_core_grants:
            missing_grants = apply_existing_core_grants(
                args.container,
                root_password,
                args.target,
                target_tables,
            )

    print(
        json.dumps(
            {
                "container": args.container,
                "network": network,
                "source": args.source,
                "target": args.target,
                "applied": args.apply,
                "core_grants_applied": args.apply_core_grants,
                "missing_core_grant_tables": missing_grants,
                "source_preserved": True,
                "merge_mode": "insert-ignore",
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, ValueError, json.JSONDecodeError) as exc:
        print(f"FAIL local-schema-cutover: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc

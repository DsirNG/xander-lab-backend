#!/usr/bin/env python3
"""Safely retire the two legacy authentication Redis key families.

The default mode is a read-only SCAN.  Deletion is deliberately guarded by
the V9 T3 evidence requirements and two explicit operator confirmations.
Credentials are read from the normal redis-cli environment (REDISCLI_AUTH),
never from command-line arguments or output.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path


PATTERNS = ("login:token:*", "login:user_tokens:*")
REQUIRED_EVIDENCE = {
    "consecutiveZeroScans": 3,
    "legacyRefreshRequests": 0,
    "observedPublishCycle": True,
}


class RedisCliError(RuntimeError):
    """Raised when the configured redis-cli cannot complete a command."""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--redis-cli", default=os.environ.get("REDIS_CLI", "redis-cli"))
    parser.add_argument("--host", default=os.environ.get("REDIS_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("REDIS_PORT", "6379")))
    parser.add_argument("--db", type=int, default=int(os.environ.get("REDIS_DB", "0")))
    parser.add_argument("--batch-size", type=int, default=256)
    parser.add_argument("--execute", action="store_true", help="delete both exact legacy key families")
    parser.add_argument("--evidence-file", type=Path,
                        help="JSON evidence for the V9 T3 preconditions")
    parser.add_argument("--confirm-legacy-redis-retirement", action="store_true",
                        help="explicitly confirm the destructive T3 operation")
    return parser.parse_args()


def redis_command(args: argparse.Namespace, *command: str) -> list[str]:
    return [
        args.redis_cli,
        "--raw",
        "--no-auth-warning",
        "-h",
        args.host,
        "-p",
        str(args.port),
        "-n",
        str(args.db),
        *command,
    ]


def run_redis(args: argparse.Namespace, *command: str) -> str:
    environment = os.environ.copy()
    try:
        completed = subprocess.run(
            redis_command(args, *command),
            check=False,
            capture_output=True,
            text=True,
            env=environment,
        )
    except OSError as exc:
        raise RedisCliError("无法启动 redis-cli，请确认工具已安装") from exc
    if completed.returncode != 0:
        raise RedisCliError(f"redis-cli 执行失败，退出码={completed.returncode}")
    return completed.stdout


def scan_keys(args: argparse.Namespace, pattern: str) -> list[str]:
    output = run_redis(args, "--scan", "--pattern", pattern)
    return [line for line in output.splitlines() if line]


def load_evidence(path: Path) -> None:
    try:
        evidence = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RedisCliError(f"无法读取 T3 evidence 文件: {path}") from exc
    if not isinstance(evidence, dict):
        raise RedisCliError("T3 evidence 必须是 JSON object")
    for key, expected in REQUIRED_EVIDENCE.items():
        actual = evidence.get(key)
        if key == "consecutiveZeroScans":
            valid = isinstance(actual, int) and not isinstance(actual, bool) and actual >= expected
        else:
            valid = actual == expected
        if not valid:
            raise RedisCliError(f"T3 evidence 不满足 {key} 条件")


def delete_in_batches(args: argparse.Namespace, keys: list[str]) -> None:
    if args.batch_size < 1:
        raise RedisCliError("--batch-size 必须大于 0")
    for offset in range(0, len(keys), args.batch_size):
        batch = keys[offset:offset + args.batch_size]
        run_redis(args, "DEL", *batch)


def main() -> int:
    args = parse_args()
    if args.execute and not args.confirm_legacy_redis_retirement:
        print("执行删除必须同时提供 --confirm-legacy-redis-retirement", file=sys.stderr)
        return 2
    if args.execute and args.evidence_file is None:
        print("执行删除必须提供 --evidence-file", file=sys.stderr)
        return 2
    try:
        keys_by_pattern = {pattern: scan_keys(args, pattern) for pattern in PATTERNS}
        print("Legacy Redis T3 盘点结果（只匹配两个精确键族）")
        for pattern, keys in keys_by_pattern.items():
            print(f"{pattern}: {len(keys)} keys")
        if not args.execute:
            print("模式: dry-run（未删除任何 key）")
            return 0

        load_evidence(args.evidence_file)
        print("T3 evidence: verified")
        all_keys = [key for keys in keys_by_pattern.values() for key in keys]
        delete_in_batches(args, all_keys)
        remaining = {pattern: len(scan_keys(args, pattern)) for pattern in PATTERNS}
        if any(remaining.values()):
            raise RedisCliError("T3 删除后仍检测到遗留键，已停止")
        print("模式: execute（两个精确键族均已清理并复核为 0）")
        return 0
    except RedisCliError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

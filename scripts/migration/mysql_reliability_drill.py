"""Run a destructive-free local MySQL backup and restore drill.

The command intentionally reads MYSQL_ROOT_PASSWORD inside the named MySQL
container. It never accepts, logs, or prints a password. The source database is
read through a transaction-consistent mysqldump; restore happens into a unique
temporary database which is always removed in the finally block.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import shlex
import subprocess
import uuid
from pathlib import Path
from typing import Sequence


def run(container: str, command: str, *, check: bool = True) -> str:
    completed = subprocess.run(
        ["docker", "exec", container, "sh", "-lc", command],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    if check and completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip()
        raise RuntimeError(f"container command failed ({completed.returncode}): {detail}")
    return completed.stdout.strip()


def mysql_command(sql: str) -> str:
    return (
        'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --batch --skip-column-names '
        f"-e {shlex.quote(sql)}"
    )


def parse_variables(raw: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in raw.splitlines():
        key, separator, value = line.partition("\t")
        if separator:
            values[key] = value
    return values


def drill(container: str, source_database: str) -> dict[str, object]:
    suffix = uuid.uuid4().hex[:12]
    restore_database = f"dinqor_restore_drill_{suffix}"
    dump_path = f"/tmp/{restore_database}.sql"
    quoted_source = shlex.quote(source_database)
    quoted_dump = shlex.quote(dump_path)
    backup_success = False
    restored_table_count = 0
    checked_at = dt.datetime.now(dt.timezone.utc)

    try:
        variables = parse_variables(
            run(container, mysql_command(
                "SHOW VARIABLES WHERE Variable_name IN ('log_bin', 'binlog_format')"
            ))
        )
        run(container, mysql_command(f"CREATE DATABASE {restore_database}"))
        run(
            container,
            'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" '
            f"--single-transaction --routines --events --triggers --no-tablespaces "
            f"{quoted_source} > {quoted_dump}",
        )
        backup_success = True
        run(
            container,
            'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" '
            f"{restore_database} < {quoted_dump}",
        )
        restored_table_count = int(
            run(
                container,
                mysql_command(
                    "SELECT COUNT(*) FROM information_schema.tables "
                    f"WHERE table_schema = '{restore_database}'"
                ),
            )
        )
        return {
            "checked_at_utc": checked_at.isoformat(),
            "container": container,
            "source_database": source_database,
            "backup_success": backup_success,
            "last_backup_age_seconds": 0,
            "binlog_enabled": variables.get("log_bin") == "ON",
            "binlog_format": variables.get("binlog_format"),
            "binlog_lag_seconds": None,
            "availability": "single-instance-no-replica",
            "restore_drill_date": checked_at.date().isoformat(),
            "restored_table_count": restored_table_count,
        }
    finally:
        run(container, f"{mysql_command(f'DROP DATABASE IF EXISTS {restore_database}')} || true", check=False)
        run(container, f"rm -f {quoted_dump}", check=False)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", default="dinqor-refactor-mysql")
    parser.add_argument("--database", default="xander_lab")
    parser.add_argument("--output", help="write the JSON evidence file for the Core metrics bridge")
    args = parser.parse_args(argv)
    result = drill(args.container, args.database)
    encoded = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        Path(args.output).write_text(encoded, encoding="utf-8")
    print(encoded, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

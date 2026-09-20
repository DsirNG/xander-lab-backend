#!/usr/bin/env python3
"""Generate per-service MySQL GRANT scripts from docs/table-ownership.yaml (V9 §66).

`table-ownership.yaml` is the single source of truth: the machine turns it into
`migrations/grants/<account>.sql`. Nobody hand-writes GRANTs.

Two things are emitted:

  1. `<account>.sql`            target-state grants (idempotent, safe to re-run)
  2. `core-host-narrowing.md`   when each table must be REVOKEd from core-host

V9 §6 is explicit that the narrowing must happen in the SAME change window as
the cut-over — "新服务用最小权限，但 core-host 仍拿 root / 全库权限" would make
ownership a paper concept. The REVOKE therefore never precedes the drain
(V9 §54 / §96: Drain → Disable → REVOKE).
"""

from __future__ import annotations

import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

try:
    import yaml
except ImportError:
    print("ERROR: PyYAML is required. pip install pyyaml")
    raise SystemExit(2)

import scan  # noqa: E402

DOCS = scan.REPO / "docs"
GRANTS = scan.REPO / "migrations" / "grants"

# deployment unit -> DB account
ACCOUNT_OF = {
    "dinqor-core-host": "dinqor_core",
    "dinqor-agent": "dinqor_agent",
    "dinqor-platform-integration": "dinqor_integration",
    "dinqor-content-automation": "dinqor_content_automation",
    "dinqor-bloghtml-preview": "dinqor_bloghtml_preview",
    "migration-runner": "dinqor_migration",
    "shared": "__shared__",
}

WAVE_OF_ACCOUNT = {
    "dinqor_core": "wave1",
    "dinqor_bloghtml_preview": "wave1",
    "dinqor_migration": "wave1",
    "dinqor_agent": "wave2",
    "dinqor_integration": "wave2",
    "dinqor_content_automation": "wave2",
}

HEADER = """-- DinQorAI microservice migration — GRANT script for `{account}` (V9 §63 / §66).
--
-- GENERATED FILE. Do not hand-edit.
--   source : docs/table-ownership.yaml
--   tool   : scripts/migration/gen_grants.py
--
-- Effective wave: {wave}
--   Wave 0A: generate only, do not apply (V9 §6: 暂不强制拆进程)
--   Wave 1 : apply together with the first real split, in the SAME change window
--            that narrows core-host — otherwise ownership is a paper concept.
--
-- Runtime-service accounts hold DML only and must never receive DDL rights
-- (V9 §102 禁止项: 业务服务拿 DDL 权限). The migration-runner account is the
-- controlled DDL exception and is emitted explicitly below.
--
-- Idempotent: safe to re-run.
"""


def main() -> int:
    ownership = yaml.safe_load((DOCS / "table-ownership.yaml").read_text(encoding="utf-8"))
    GRANTS.mkdir(parents=True, exist_ok=True)

    by_account: dict[str, list[dict]] = defaultdict(list)
    # Seed every account so that a service owning no tables (bloghtml-preview
    # is Redis-only) still gets an explicit, reviewable file instead of silently
    # missing from the GRANT set.
    for account in ACCOUNT_OF.values():
        if account != "__shared__":
            by_account.setdefault(account, [])
    for entry in ownership["tables"]:
        unit = entry["target_deployment_unit"]
        account = ACCOUNT_OF.get(unit)
        if account is None:
            raise SystemExit(f"unknown target_deployment_unit {unit!r} for {entry['table']}")
        if account == "__shared__":
            # Tables shared by every runtime (ShedLock) are granted to every
            # business account that runs a global_once task.
            for target in ("dinqor_core", "dinqor_agent", "dinqor_content_automation"):
                by_account[target].append({**entry, "shared_grant": True})
            continue
        by_account[account].append(entry)

    for entry in ownership.get("pending_tables") or []:
        unit = entry["target_deployment_unit"]
        account = ACCOUNT_OF.get(unit)
        if account in (None, "__shared__"):
            for target in ("dinqor_core", "dinqor_agent", "dinqor_content_automation"):
                by_account[target].append({**entry, "shared_grant": True, "pending": True})
            continue
        by_account[account].append({**entry, "pending": True})

    written = []
    for account in sorted(by_account):
        tables = sorted({e["table"] for e in by_account[account]})
        wave = WAVE_OF_ACCOUNT.get(account, "wave3+")
        lines = [HEADER.format(account=account, wave=wave), ""]
        if account == "dinqor_migration":
            lines += [
                "-- The migration account is the ONLY account with DDL rights.",
                "-- It must never be used as a runtime account by any service.",
                "",
                "CREATE USER IF NOT EXISTS 'dinqor_migration'@'%' IDENTIFIED BY '<from deploy secret>';",
                "",
                "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES,",
                "      CREATE TEMPORARY TABLES, LOCK TABLES, EXECUTE, CREATE VIEW, SHOW VIEW",
                "  ON `xander_lab`.* TO 'dinqor_migration'@'%';",
                "",
                "-- Flyway needs to read its own history table and schema metadata.",
                "GRANT SELECT ON `mysql`.* TO 'dinqor_migration'@'%';",
                "",
                "FLUSH PRIVILEGES;",
            ]
        elif not tables:
            lines.append("-- No table grants: this service owns no MySQL tables.")
            lines.append("-- dinqor-bloghtml-preview is Redis-only (TTL preview sessions, no schema).")
            lines.append("-- Keep the account so the service can still be granted future tables")
            lines.append("-- without a rename, but grant it NOTHING today.")
        else:
            lines.append(f"-- Tables owned by this account ({len(tables)}):")
            for table in tables:
                lines.append(f"--   {table}")
            lines.append("")
            lines.append(f"CREATE USER IF NOT EXISTS '{account}'@'%' IDENTIFIED BY '<from deploy secret>';")
            lines.append("")
            # One GRANT per table keeps diffs readable and makes the REVOKE
            # step in core-host-narrowing.md symmetric.
            for table in tables:
                lines.append(
                    f"GRANT SELECT, INSERT, UPDATE, DELETE ON `xander_lab`.`{table}` TO '{account}'@'%';"
                )
            lines.append("")
            lines.append("FLUSH PRIVILEGES;")
        path = GRANTS / f"{account}.sql"
        path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
        written.append((path.name, len(tables), wave))

    # --- core-host narrowing plan ------------------------------------------
    plan = [
        "# core-host 权限收窄计划（V9 §6 / §54 / §66 / §96）",
        "",
        "GENERATED FILE — source: `docs/table-ownership.yaml`, tool: `scripts/migration/gen_grants.py`.",
        "",
        "## 硬约束",
        "",
        "```text",
        "1. 为新服务创建独立 DB Account",
        "2. 在同一次切流变更中收窄 core-host 权限",
        "3. 抽出的域表从 core-host REVOKE（在 Drain 完成之后）",
        "```",
        "",
        "禁止：`新服务用最小权限，但 core-host 仍拿 root / 全库权限` —— 否则 Owner 只是纸面概念。",
        "",
        "REVOKE 顺序严格遵循 §96 的十步：",
        "",
        "```text",
        "1. Migration Job       2. 新服务 deploy      3. 新服务 health",
        "4. 路由新连接到新服务   5. core-host 进入 drain",
        "6. 等待 / 强制结束旧长连接",
        "7. 确认 active = 0（逐实例，见 §55.2）",
        "8. 禁用 core-host 对应 Scheduler / Controller",
        "9. REVOKE core-host 表权限      ← 只能在这里",
        "10. 验证唯一 Owner",
        "```",
        "",
        "## 按 Wave 收窄清单",
        "",
    ]
    groups: dict[str, list[str]] = defaultdict(list)
    for entry in ownership["tables"]:
        if entry.get("revoke_from_core_host_at") == "never":
            continue
        unit = entry["target_deployment_unit"]
        wave = WAVE_OF_ACCOUNT.get(ACCOUNT_OF.get(unit, ""), "wave3+")
        groups[wave].append(f"{entry['table']} → {unit}")

    for wave in sorted(groups):
        plan.append(f"### {wave}")
        plan.append("")
        for item in sorted(groups[wave]):
            plan.append(f"- `{item}`")
        plan.append("")
        plan.append("```sql")
        for item in sorted(groups[wave]):
            table = item.split(" → ")[0]
            plan.append(f"REVOKE ALL PRIVILEGES ON `xander_lab`.`{table}` FROM 'dinqor_core'@'%';")
        plan.append("```")
        plan.append("")

    plan += [
        "## 不随 Wave 收窄的表（`revoke_from_core_host_at: never`）",
        "",
        "identity / billing / admin / profile 以及 Wave 3+ 域的表现在仍由 core-host 持有。",
        "它们只在 `core-host → dinqor-core` 正式改名（§4 最终阶段）时才考虑进一步拆分。",
        "",
    ]
    (GRANTS / "core-host-narrowing.md").write_text("\n".join(plan), encoding="utf-8", newline="\n")

    for name, count, wave in written:
        print(f"wrote migrations/grants/{name}  ({count} tables, {wave})")
    print("wrote migrations/grants/core-host-narrowing.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

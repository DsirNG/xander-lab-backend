#!/usr/bin/env python3
"""DinQorAI microservice migration — machine inventory scanner.

Implements the V9 methodology of section 13: the machine owns the *key set*
("what the system actually has"), humans own the *semantics* ("who owns it,
how it migrates"). This script produces the key sets only. Curated files such
as ``docs/table-ownership.yaml`` are never overwritten by this script; they are
validated against the key sets by ``ci_gates.py``.

Usage:
    python scripts/migration/scan.py            # regenerate machine inventories
    python scripts/migration/scan.py --stdout   # print instead of write
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import yamlutil  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
JAVA_ROOT = REPO / "src" / "main" / "java"
RESOURCES = REPO / "src" / "main" / "resources"
DB_DIR = RESOURCES / "db"
OUT_DIR = REPO / "docs"

# ---------------------------------------------------------------------------
# Domain model
# ---------------------------------------------------------------------------

# The 15 business domains of V9 section 1, plus the shared kernel.
BUSINESS_DOMAINS = [
    "agent",
    "identity",
    "publishing",
    "automation",
    "blogagent",
    "knowledgebase",
    "recitation",
    "studio",
    "virtualpay",
    "billing",
    "admin",
    "components",
    "bloghtml",
    "integration",
    "profile",
]
SHARED_KERNEL = ["common", "config", "filter", "util"]

# V9 section 3 / section 89: domain -> deployment unit.
DEPLOYMENT_UNIT = {
    "identity": "dinqor-core-host",
    "billing": "dinqor-core-host",
    "admin": "dinqor-core-host",
    "profile": "dinqor-core-host",
    "agent": "dinqor-agent",
    "integration": "dinqor-platform-integration",
    "publishing": "dinqor-content-automation",
    "blogagent": "dinqor-content-automation",
    "automation": "dinqor-content-automation",
    "bloghtml": "dinqor-bloghtml-preview",
    "knowledgebase": "dinqor-core-host",
    "recitation": "dinqor-core-host",
    "studio": "dinqor-core-host",
    "components": "dinqor-core-host",
    "virtualpay": "dinqor-core-host",
}

# Wave in which each domain leaves the monolith (V9 section 3).
WAVE = {
    "bloghtml": "wave1",
    "agent": "wave2",
    "integration": "wave2",
    "publishing": "wave2",
    "blogagent": "wave2",
    "automation": "wave2",
    "identity": "wave3+",
    "billing": "wave3+",
    "admin": "wave3+",
    "profile": "wave3+",
    "knowledgebase": "wave3+",
    "recitation": "wave3+",
    "studio": "wave3+",
    "components": "wave3+",
    "virtualpay": "wave3+",
}

# V9 section 7: v2 API prefix per domain.
V2_PREFIX = {
    "identity": "/api/v2/auth",
    "admin": "/api/v2/admin",
    "billing": "/api/v2/billing",
    "agent": "/api/v2/agents",
    "publishing": "/api/v2/content",
    "blogagent": "/api/v2/content",
    "automation": "/api/v2/automations",
    "integration": "/api/v2/integrations",
    "knowledgebase": "/api/v2/knowledge",
    "recitation": "/api/v2/learning",
    "studio": "/api/v2/studio",
    "components": "/api/v2/studio/components",
    "virtualpay": "/api/v2/payments",
    "bloghtml": "/api/v2/previews",
    "profile": "/api/v2/users",
}

# ---------------------------------------------------------------------------
# Java source parsing
# ---------------------------------------------------------------------------

MAPPING_ANNOTATIONS = {
    "GetMapping": "GET",
    "PostMapping": "POST",
    "PutMapping": "PUT",
    "DeleteMapping": "DELETE",
    "PatchMapping": "PATCH",
    "RequestMapping": None,  # method comes from the annotation arguments
}

ANNOTATION_RE = re.compile(
    r"@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)\b"
)

STRING_LITERAL_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')
PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.MULTILINE)
CLASS_RE = re.compile(r"\b(?:public\s+|final\s+|abstract\s+)*class\s+(\w+)")
REQUEST_METHOD_RE = re.compile(r"RequestMethod\.(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)")


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def annotation_arguments(content: str, open_paren_index: int) -> tuple[str, int]:
    """Return the balanced argument text starting at ``open_paren_index``."""
    depth = 0
    index = open_paren_index
    while index < len(content):
        char = content[index]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return content[open_paren_index + 1:index], index + 1
        index += 1
    return content[open_paren_index + 1:], len(content)


def extract_paths(arguments: str) -> list[str]:
    """Extract declared paths from mapping annotation arguments."""
    if not arguments.strip():
        return []
    head = arguments.strip()
    if head.startswith("{"):
        return [unescape(m) for m in STRING_LITERAL_RE.findall(head)]
    if head.startswith('"'):
        first = STRING_LITERAL_RE.search(head)
        return [unescape(first.group(1))] if first else []
    for key in ("value", "path"):
        match = re.search(rf"\b{key}\s*=\s*(\{{[^}}]*\}}|\"[^\"]*\")", arguments)
        if match:
            return [unescape(m) for m in STRING_LITERAL_RE.findall(match.group(1))]
    return []


def unescape(value: str) -> str:
    return value.replace('\\"', '"').replace("\\\\", "\\")


def join_path(prefix: str, suffix: str) -> str:
    if not prefix:
        return suffix or "/"
    if not suffix:
        return prefix
    return prefix.rstrip("/") + "/" + suffix.lstrip("/")


def domain_of(package: str) -> str:
    parts = package.split(".")
    if len(parts) >= 4 and parts[:3] == ["com", "xander", "lab"]:
        return parts[3]
    return "unknown"


def is_class_level(content: str, annotation_end: int) -> bool:
    """A mapping annotation is class level when a ``class`` keyword follows it
    before the next statement/body brace."""
    tail = content[annotation_end:annotation_end + 800]
    boundary = re.search(r"[;{]", tail)
    segment = tail[:boundary.start()] if boundary else tail
    segment = ANNOTATION_RE.sub("", segment)
    return re.search(r"\bclass\b", segment) is not None


def method_signature(content: str, annotation_end: int) -> str:
    tail = content[annotation_end:annotation_end + 1500]
    boundary = re.search(r"\{", tail)
    return tail[:boundary.start()] if boundary else tail


def slugify(text: str) -> str:
    text = text.strip("/")
    text = re.sub(r"\{[^}]*\}", "", text)
    text = re.sub(r"[^A-Za-z0-9]+", "-", text)
    return text.strip("-").lower() or "root"


def kebab(name: str) -> str:
    """``AgentToolInvocationReconciler`` -> ``agent-tool-invocation-reconciler``.

    Digits stay attached to the preceding word so ``Img2ThreeService`` becomes
    ``img2three-service``, matching the identifiers used by the plan.
    """
    name = re.sub(r"(?<=[a-z])(?=[A-Z])", "-", name)
    name = re.sub(r"(?<=[A-Z])(?=[A-Z][a-z])", "-", name)
    return re.sub(r"-+", "-", name).lower()


def scan_java() -> dict:
    routes: list[dict] = []
    scheduled: list[dict] = []
    imports_by_file: dict[str, list[str]] = {}
    reverse_deps: dict[str, set[str]] = defaultdict(set)
    redis_keys: list[dict] = []

    import_re = re.compile(r"^\s*import\s+(?:static\s+)?(com\.xander\.lab\.[\w.]+)\s*;", re.MULTILINE)
    scheduled_re = re.compile(r"@Scheduled\b")
    redis_re = re.compile(r'"((?:[a-z][a-z0-9]*:){1,3}(?:[a-z0-9*{}_\-]*))"')

    for path in sorted(JAVA_ROOT.rglob("*.java")):
        content = read_text(path)
        rel = path.relative_to(REPO).as_posix()
        package_match = PACKAGE_RE.search(content)
        package = package_match.group(1) if package_match else ""
        domain = domain_of(package)
        class_match = CLASS_RE.search(content)
        class_name = class_match.group(1) if class_match else path.stem
        fqcn = f"{package}.{class_name}" if package else class_name

        # --- cross-domain imports -------------------------------------------
        imports = import_re.findall(content)
        imports_by_file[rel] = imports
        for imported in imports:
            target = domain_of(imported)
            if target in BUSINESS_DOMAINS and target != domain:
                reverse_deps[domain].add(target)

        # --- redis key prefixes --------------------------------------------
        if "/common/" in rel or "Redis" in rel or "Constants" in rel:
            for key in redis_re.findall(content):
                redis_keys.append({
                    "key_prefix": key,
                    "declared_in": rel,
                    "logical_owner": "unassigned" if domain in SHARED_KERNEL else domain,
                })

        # --- @Scheduled ------------------------------------------------------
        for match in scheduled_re.finditer(content):
            line = content[:match.start()].count("\n") + 1
            tail = content[match.end():]
            args = ""
            annotation_end = match.end()
            if tail.lstrip().startswith("("):
                open_index = match.end() + (len(tail) - len(tail.lstrip()))
                args, annotation_end = annotation_arguments(content, open_index)
            # The signature must start *after* the annotation: scheduled
            # expressions contain ``${...}`` and the first brace would otherwise
            # be swallowed, leaving the method name empty.
            signature = method_signature(content, annotation_end)
            method_name = re.search(r"(\w+)\s*\(", signature)
            scheduled.append({
                "id": kebab(class_name),
                "class": fqcn,
                "method": method_name.group(1) if method_name else "",
                "trigger": " ".join(args.split()),
                "logical_owner": "unassigned" if domain in SHARED_KERNEL else domain,
                "file": rel,
                "line": line,
            })

        # --- HTTP mappings ---------------------------------------------------
        is_controller = "@RestController" in content or "@Controller" in content
        if not is_controller:
            continue

        class_prefix = ""
        pending: list[dict] = []

        for match in ANNOTATION_RE.finditer(content):
            name = match.group(1)
            http_method = MAPPING_ANNOTATIONS[name]
            arguments = ""
            annotation_end = match.end()
            tail = content[match.end():]
            stripped = tail.lstrip()
            if stripped.startswith("("):
                open_index = match.end() + (len(tail) - len(stripped))
                arguments, annotation_end = annotation_arguments(content, open_index)
            if http_method is None:
                found = REQUEST_METHOD_RE.findall(arguments)
                http_method = found[0] if found else None
            paths = extract_paths(arguments)
            line = content[:match.start()].count("\n") + 1

            if is_class_level(content, annotation_end):
                class_prefix = paths[0] if paths else ""
                continue
            pending.append({
                "http_method": http_method or "ANY",
                "paths": paths,
                "line": line,
                "end": annotation_end,
            })

        for entry in pending:
            # The exact signature runs from the end of the method-level mapping
            # annotation to the opening brace of the method body. Anything wider
            # picks up neighbouring methods and mislabels plain endpoints as SSE.
            signature = method_signature(content, entry["end"])
            method_name = re.search(r"(\w+)\s*\(", signature)
            stream = "none"
            if any(token in signature for token in
                   ("SseEmitter", "ResponseBodyEmitter", "ServerSentEvent", "Flux<")):
                stream = "sse"
            last_event_id = "Last-Event-ID" in signature
            for sub_path in (entry["paths"] or [""]):
                routes.append({
                    "id": "",
                    "logical_owner": domain if domain in BUSINESS_DOMAINS else "shared-kernel",
                    "deployment_unit": DEPLOYMENT_UNIT.get(domain, "dinqor-core-host"),
                    "kind": "controller",
                    "http_method": entry["http_method"],
                    "path": join_path(class_prefix, sub_path),
                    "stream": stream,
                    "last_event_id": last_event_id,
                    "handler": f"{fqcn}#{method_name.group(1) if method_name else ''}",
                    "file": rel,
                    "line": entry["line"],
                })

    return {
        "routes": routes,
        "scheduled": scheduled,
        "imports": imports_by_file,
        "reverse_deps": {k: sorted(v) for k, v in sorted(reverse_deps.items())},
        "redis_keys": redis_keys,
    }


# ---------------------------------------------------------------------------
# Servlet / WebSocket endpoints (registered outside Spring MVC)
# ---------------------------------------------------------------------------

SERVLET_RE = re.compile(
    r"new\s+ServletRegistrationBean<[^>]*>\s*\(\s*[\w.]+,\s*\"([^\"]+)\""
)
WS_HANDLER_RE = re.compile(r"addHandler\s*\(\s*[\w.]+\(\s*\)\s*,\s*\"([^\"]+)\"")


def scan_non_mvc() -> list[dict]:
    found: list[dict] = []
    for path in sorted(JAVA_ROOT.rglob("*.java")):
        content = read_text(path)
        rel = path.relative_to(REPO).as_posix()
        package_match = PACKAGE_RE.search(content)
        domain = domain_of(package_match.group(1) if package_match else "")
        for match in SERVLET_RE.finditer(content):
            found.append({
                "id": "",
                "logical_owner": domain,
                "deployment_unit": DEPLOYMENT_UNIT.get(domain, "dinqor-core-host"),
                "kind": "servlet",
                "http_method": "ANY",
                "path": match.group(1),
                "stream": "none",
                "last_event_id": False,
                "handler": "ServletRegistrationBean",
                "file": rel,
                "line": content[:match.start()].count("\n") + 1,
            })
        for match in WS_HANDLER_RE.finditer(content):
            found.append({
                "id": "",
                "logical_owner": domain,
                "deployment_unit": DEPLOYMENT_UNIT.get(domain, "dinqor-core-host"),
                "kind": "websocket",
                "http_method": "GET",
                "path": match.group(1),
                "stream": "ws",
                "last_event_id": False,
                "handler": "WebSocketHandler",
                "file": rel,
                "line": content[:match.start()].count("\n") + 1,
            })
    return found


# ---------------------------------------------------------------------------
# SQL table inventory
# ---------------------------------------------------------------------------

CREATE_TABLE_RE = re.compile(
    r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(\w+)`?", re.IGNORECASE
)
ALTER_TABLE_RE = re.compile(r"ALTER\s+TABLE\s+`?(\w+)`?", re.IGNORECASE)
SQL_BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)
SQL_LINE_COMMENT_RE = re.compile(r"--[^\n]*")


def strip_sql_comments(content: str) -> str:
    """Comments mention ``CREATE TABLE IF NOT EXISTS`` in prose; leaving them in
    makes the regex match the comment text and invent a table named ``IF``."""
    content = SQL_BLOCK_COMMENT_RE.sub(" ", content)
    return SQL_LINE_COMMENT_RE.sub(" ", content)


def scan_tables() -> list[dict]:
    tables: dict[str, dict] = {}
    for path in sorted(DB_DIR.rglob("*.sql")):
        rel = path.relative_to(REPO).as_posix()
        content = strip_sql_comments(read_text(path))
        for match in CREATE_TABLE_RE.finditer(content):
            name = match.group(1)
            entry = tables.setdefault(name, {"table": name, "created_in": rel, "altered_in": []})
            entry["created_in"] = rel
        for match in ALTER_TABLE_RE.finditer(content):
            name = match.group(1)
            entry = tables.setdefault(name, {"table": name, "created_in": "", "altered_in": []})
            if rel not in entry["altered_in"]:
                entry["altered_in"].append(rel)
    return [tables[name] for name in sorted(tables)]


# ---------------------------------------------------------------------------
# application.yml config inventory
# ---------------------------------------------------------------------------

def scan_config() -> list[dict]:
    entries: list[dict] = []
    for name in ("application.yml", "application-local.yml"):
        path = RESOURCES / name
        if not path.exists():
            continue
        stack: list[tuple[int, str]] = []
        for raw in read_text(path).splitlines():
            if not raw.strip() or raw.lstrip().startswith("#"):
                continue
            indent = len(raw) - len(raw.lstrip(" "))
            body = raw.strip()
            match = re.match(r"^([\w.\-]+)\s*:\s*(.*)$", body)
            if not match:
                continue
            key, value = match.group(1), match.group(2).strip()
            while stack and stack[-1][0] >= indent:
                stack.pop()
            stack.append((indent, key))
            full = ".".join(item[1] for item in stack)
            entries.append({
                "key": full,
                "source": name,
                "value": value,
                "leaf": bool(value),
                "logical_owner": owner_of_config(full),
            })
    return entries


CONFIG_OWNERS = [
    (r"^(server|spring)\.", "shared"),
    (r"^spring\.datasource", "core"),
    (r"^spring\.data\.redis|^spring\.redis", "core"),
    (r"^spring\.flyway", "core"),
    (r"^mybatis-plus", "shared"),
    (r"^jwt", "core"),
    (r"^admin\.", "core"),
    (r"^recitation\.", "knowledge"),
    (r"^knowledge-base\.", "knowledge"),
    (r"^agent\.", "agent"),
    (r"^mcp\.", "integration"),
    (r"^blog-html\.", "bloghtml-preview"),
    (r"^blog\.|^blog-content-plan\.|^email-reminder\.", "content-automation"),
    (r"^img2three\.", "studio"),
    (r"^billing\.", "core"),
    (r"^wechat\.", "core"),
    (r"^mail\.|^aliyun\.|^redis-monitor\.|^app\.", "core"),
]


def owner_of_config(key: str) -> str:
    for pattern, owner in CONFIG_OWNERS:
        if re.search(pattern, key):
            return owner
    return "unassigned"


# ---------------------------------------------------------------------------
# Assembly
# ---------------------------------------------------------------------------

def build_route_inventory() -> dict:
    java = scan_java()
    routes = java["routes"] + scan_non_mvc()
    for route in routes:
        route["id"] = route_id(route)
    routes.sort(key=lambda item: (item["path"], item["http_method"], item["file"]))
    seen: Counter[str] = Counter()
    for route in routes:
        seen[route["id"]] += 1
    for route in routes:
        if seen[route["id"]] > 1:
            route["id"] = f"{route['id']}-{route['file'].rsplit('/', 1)[-1].replace('.java', '').lower()}"
    return {
        "generated_by": "scripts/migration/scan.py",
        "note": "Machine-owned key set. Do not hand-edit; re-run the scanner.",
        "counts": {
            "total": len(routes),
            "streams": sum(1 for r in routes if r["stream"] != "none"),
            "websocket": sum(1 for r in routes if r["kind"] == "websocket"),
            "sse": sum(1 for r in routes if r["stream"] == "sse"),
            "servlets": sum(1 for r in routes if r["kind"] == "servlet"),
        },
        "routes": routes,
    }


def route_id(route: dict) -> str:
    prefix = V2_PREFIX.get(route["logical_owner"], "/api")
    path = route["path"]
    for candidate in (prefix, "/api"):
        if path.startswith(candidate):
            path = path[len(candidate):]
            break
    return f"{route['logical_owner']}-{route['http_method'].lower()}-{slugify(path)}"


def build_scheduled_inventory() -> dict:
    java = scan_java()
    items = sorted(java["scheduled"], key=lambda item: item["class"])
    return {
        "generated_by": "scripts/migration/scan.py",
        "note": "Machine-owned key set of every @Scheduled entry point.",
        "counts": {"total": len(items)},
        "scheduled": items,
    }


def build_table_inventory() -> dict:
    tables = scan_tables()
    return {
        "generated_by": "scripts/migration/scan.py",
        "note": "Machine-owned key set of every table mentioned by the 80 legacy SQL scripts.",
        "counts": {"total": len(tables)},
        "tables": tables,
    }


def build_config_inventory() -> dict:
    entries = scan_config()
    leaves = [e for e in entries if e["leaf"]]
    return {
        "generated_by": "scripts/migration/scan.py",
        "note": "Machine-owned key set of every configuration key in application.yml.",
        "counts": {"total": len(entries), "leaves": len(leaves)},
        "config": entries,
    }


HEADER = """DinQorAI microservice migration — machine-generated inventory.

Generated by: scripts/migration/scan.py
Do NOT hand-edit. Re-run the scanner instead. Curated semantics live in the
sibling ownership / policy files, which the CI gates validate against this file.
"""

# ---------------------------------------------------------------------------
# API migration contract (V9 section 8)
# ---------------------------------------------------------------------------

API_MIGRATION_HEADER = """DinQorAI microservice migration — API Migration Contract (V9 section 8).

Bootstrapped by scripts/migration/scan.py from docs/route-inventory.yaml.
Re-running the scanner only APPENDS routes that are not present yet; existing
entries keep their hand-filled `clients` / `status` values.

Human-owned fields: clients.{web,miniprogram,admin}, status, compatibility.
Machine-owned fields: id, owner, old.{method,path}, new.{method,path}.

CI gates: `api-migration-key-coverage` (every inventory route present),
`contract-freeze-guard` (frozen entries must not change).
"""

CLIENT_VALUES = ["pending", "migrated", "not_applicable"]

# Paths that never reach the browser clients (admin console / internal).
ADMIN_ONLY_PREFIXES = ("/api/admin/",)
# Routes with no v2 equivalent (independent auth planes, non-API page routes).
NO_V2_PREFIXES = ("/blog-html/", "/ws/", "/api/auth/", "/api/virtual-pay/notify", "/.well-known/", "/health")


def build_api_migration(routes: list[dict]) -> list[dict]:
    entries: list[dict] = []
    for route in routes:
        if route["kind"] == "servlet":
            # MCP transport endpoints are registered as servlets, not controllers;
            # they are covered by the `mcp_inbound` section of auth-policy.yaml.
            continue
        old_path = route["path"]
        is_admin = old_path.startswith(ADMIN_ONLY_PREFIXES)
        if not old_path.startswith("/api") or old_path.startswith(NO_V2_PREFIXES):
            entries.append({
                "id": route["id"],
                "owner": route["logical_owner"],
                "old": {"method": route["http_method"], "path": old_path},
                "new": {"method": route["http_method"], "path": ""},
                "clients": {"web": "not_applicable", "miniprogram": "not_applicable",
                            "admin": "not_applicable"},
                "compatibility": {"v1_enabled": True, "v2_enabled": False},
                "status": "no_v2_target",
                "note": "协议端点 / 健康检查 / 非 /api 页面路由，不纳入 v2 业务 API。",
            })
            continue
        entries.append({
            "id": route["id"],
            "owner": route["logical_owner"],
            "old": {"method": route["http_method"], "path": old_path},
            "new": {"method": route["http_method"], "path": to_v2_path(route)},
            "clients": {
                "web": "not_applicable" if is_admin else "pending",
                "miniprogram": "not_applicable" if is_admin else "pending",
                "admin": "pending" if is_admin else "not_applicable",
            },
            "compatibility": {"v1_enabled": True, "v2_enabled": False},
            "status": "planned",
        })
    entries.sort(key=lambda item: (item["owner"], item["old"]["path"], item["old"]["method"]))
    return entries


def to_v2_path(route: dict) -> str:
    """Map a v1 path onto its v2 owner prefix (V9 section 3 / section 7).

    The v1 URL prefix is deliberately NOT trusted: several routes live under a
    prefix that does not match their real owner (e.g. ``/api/publishing/csdn/**``
    is owned by ``integration``). The table below is therefore keyed on the
    concrete v1 prefix, ordered longest-first so the more specific entry wins.
    """
    path = route["path"]
    for v1_prefix, v2_prefix in V2_PATH_TABLE:
        if path == v1_prefix.rstrip("/") or path.startswith(v1_prefix):
            return v2_prefix.rstrip("/") + path[len(v1_prefix.rstrip("/")):]
    return V2_PREFIX.get(route["logical_owner"], "/api/v2") + path[len("/api"):]


# v1 prefix -> v2 prefix. Longest match wins (list is pre-sorted at import time).
V2_PATH_TABLE = sorted(
    [
        ("/api/publishing/csdn/", "/api/v2/integrations/csdn/"),
        ("/api/publishing/juejin/", "/api/v2/integrations/juejin/"),
        ("/api/mcp/oauth/", "/api/v2/integrations/mcp/oauth/"),
        ("/api/publishing/", "/api/v2/content/publishing/"),
        ("/api/knowledge-base/", "/api/v2/knowledge/"),
        ("/api/blog-agent/", "/api/v2/content/blog-agent/"),
        ("/api/blog-plans", "/api/v2/automations/plans"),
        ("/api/blog/", "/api/v2/content/blog/"),
        ("/api/email-reminders", "/api/v2/automations/email-reminders"),
        ("/api/notifications", "/api/v2/automations/notifications"),
        ("/api/agent/", "/api/v2/agents/"),
        ("/api/admin/", "/api/v2/admin/"),
        ("/api/auth/", "/api/v2/auth/"),
        ("/api/recitations/", "/api/v2/learning/"),
        ("/api/img2three/", "/api/v2/studio/img2three/"),
        ("/api/flow/", "/api/v2/studio/flow/"),
        ("/api/components/", "/api/v2/studio/components/"),
        ("/api/virtual-pay/", "/api/v2/payments/virtual-pay/"),
        ("/api/points", "/api/v2/billing/points"),
        ("/api/profile/", "/api/v2/users/profile/"),
        ("/api/upload", "/api/v2/integrations/upload"),
        ("/api/download/", "/api/v2/integrations/download/"),
        ("/api/export/", "/api/v2/content/export/"),
        ("/api/blog-html/", "/api/v2/previews/"),
    ],
    key=lambda pair: -len(pair[0]),
)


# ---------------------------------------------------------------------------
# Config ownership (V9 section 16 / section 17)
# ---------------------------------------------------------------------------

CONFIG_OWNERSHIP_HEADER = """DinQorAI microservice migration — 配置归属清单（V9 section 16 / section 17）.

Bootstrapped by scripts/migration/scan.py from docs/config-inventory.yaml.
Nacos 规范：Namespace = dinqor-{dev,staging,prod}，Group = 稳定逻辑服务名
（COMMON / DINQOR-CORE / DINQOR-GATEWAY / ...），DataId = dinqor-<service>.yaml。

配置优先级：部署 Secret / 环境变量 > 服务私有 Nacos > 共享 Nacos > jar 内默认值。
任何配置只能有一个真源；禁止 application.yml / .env / Nacos 三处同时配置同一键。

CI 门禁 `config-ownership-drift`：inventory 中每个键都必须在本文件出现，
且 secret 键不得出现在 Nacos 明文章节。
"""

# 密钥类键：绝不进 Nacos 明文（V9 section 16 Secrets）。
SECRET_PATTERNS = [
    r"password",
    r"secret",
    r"private-key",
    r"access-key",
    r"secret-key",
    r"encryption-key",
    r"api-key",
    r"token$",
    r"jwt\.",
]

# 服务私有配置：每个服务只扫描自己的 Mapper / Alias（V9 section 80）。
SERVICE_PRIVATE_PATTERNS = [
    r"^mybatis-plus",
    r"^spring\.datasource",
    r"^spring\.data\.redis",
    r"^spring\.redis",
]

SHARED_PATTERNS = [
    r"^server\.",
    r"^spring\.jackson",
    r"^spring\.servlet",
    r"^spring\.mvc",
    r"^spring\.web",
    r"^spring\.threads",
    r"^spring\.task",
]


def config_class(key: str) -> str:
    lowered = key.lower()
    if any(re.search(pattern, lowered) for pattern in SECRET_PATTERNS):
        return "secret"
    if any(re.search(pattern, key) for pattern in SERVICE_PRIVATE_PATTERNS):
        return "service_private"
    if any(re.search(pattern, key) for pattern in SHARED_PATTERNS):
        return "shared"
    return "service_private"


def build_config_ownership(config_entries: list[dict]) -> list[dict]:
    entries: list[dict] = []
    for entry in config_entries:
        if not entry["leaf"]:
            continue
        entries.append({
            "key": entry["key"],
            "source": entry["source"],
            "classification": config_class(entry["key"]),
            "owner": entry["logical_owner"],
            "nacos_group": nacos_group(entry["logical_owner"]),
        })
    entries.sort(key=lambda item: (item["owner"], item["key"]))
    return entries


NACOS_GROUPS = {
    "shared": "COMMON",
    "core": "DINQOR-CORE",
    "gateway": "DINQOR-GATEWAY",
    "agent": "DINQOR-AGENT",
    "integration": "DINQOR-PLATFORM-INTEGRATION",
    "content-automation": "DINQOR-CONTENT-AUTOMATION",
    "bloghtml-preview": "DINQOR-BLOGHTML-PREVIEW",
    "knowledge": "DINQOR-CORE",
    "studio": "DINQOR-CORE",
    "unassigned": "COMMON",
}


def nacos_group(owner: str) -> str:
    return NACOS_GROUPS.get(owner, "COMMON")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stdout", action="store_true", help="print instead of writing")
    parser.add_argument("--no-derived", action="store_true",
                        help="skip api-migration.yaml / config-ownership.yaml")
    args = parser.parse_args()

    artifacts = {
        "route-inventory.yaml": build_route_inventory(),
        "scheduled-inventory.yaml": build_scheduled_inventory(),
        "table-inventory.yaml": build_table_inventory(),
        "config-inventory.yaml": build_config_inventory(),
    }

    for name, payload in artifacts.items():
        text = yamlutil.dumps(payload, HEADER)
        if args.stdout:
            print(f"===== {name} =====")
            print(text)
        else:
            target = OUT_DIR / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text, encoding="utf-8", newline="\n")
            print(f"wrote {target.relative_to(REPO)}  {payload.get('counts', {})}")

    if not args.stdout and not args.no_derived:
        write_api_migration(artifacts["route-inventory.yaml"]["routes"])
        write_config_ownership(artifacts["config-inventory.yaml"]["config"])

    if not args.stdout:
        report = build_report(artifacts)
        (OUT_DIR / "migration-scan-report.md").write_text(report, encoding="utf-8", newline="\n")
        print("wrote docs/migration-scan-report.md")
    return 0


EXISTING_ID_RE = re.compile(r"^\s*-\s*id:\s*(\S+)\s*$", re.MULTILINE)


def write_api_migration(routes: list[dict]) -> None:
    """Bootstrap / merge docs/api-migration.yaml without clobbering human edits."""
    target = OUT_DIR / "api-migration.yaml"
    entries = build_api_migration(routes)
    if target.exists():
        existing = set(EXISTING_ID_RE.findall(target.read_text(encoding="utf-8")))
        fresh = [entry for entry in entries if entry["id"] not in existing]
        if not fresh:
            print(f"kept docs/api-migration.yaml ({len(existing)} entries, nothing new)")
            return
        text = yamlutil.dumps(fresh)
        text = text.replace("  - id:", "- id:", 1) if text.startswith("  - id:") else text
        with open(target, "a", encoding="utf-8", newline="\n") as handle:
            handle.write("\n# --- appended by scripts/migration/scan.py ---\n")
            handle.write(text.lstrip("\n"))
        print(f"appended {len(fresh)} new entries to docs/api-migration.yaml")
        return

    payload = {
        "generated_by": "scripts/migration/scan.py",
        "counts": {"total": len(entries)},
        "entries": entries,
    }
    target.write_text(yamlutil.dumps(payload, API_MIGRATION_HEADER), encoding="utf-8", newline="\n")
    print(f"wrote docs/api-migration.yaml  {{'total': {len(entries)}}}")


def write_config_ownership(config_entries: list[dict]) -> None:
    entries = build_config_ownership(config_entries)
    counts = Counter(entry["classification"] for entry in entries)
    payload = {
        "generated_by": "scripts/migration/scan.py",
        "counts": {"total": len(entries), **dict(counts)},
        "config": entries,
    }
    target = OUT_DIR / "config-ownership.yaml"
    target.write_text(yamlutil.dumps(payload, CONFIG_OWNERSHIP_HEADER),
                      encoding="utf-8", newline="\n")
    print(f"wrote docs/config-ownership.yaml  {payload['counts']}")


def build_report(artifacts: dict) -> str:
    routes = artifacts["route-inventory.yaml"]["routes"]
    by_owner = Counter(r["logical_owner"] for r in routes)
    streams = [r for r in routes if r["stream"] != "none"]
    scheduled = artifacts["scheduled-inventory.yaml"]["scheduled"]
    tables = artifacts["table-inventory.yaml"]["tables"]
    config = artifacts["config-inventory.yaml"]["config"]
    unassigned_config = [c for c in config if c["logical_owner"] == "unassigned" and c["leaf"]]

    lines = [
        "# Migration scan report",
        "",
        "Generated by `scripts/migration/scan.py`. Machine view only.",
        "",
        "## Routes",
        "",
        f"- total endpoints: **{len(routes)}**",
        f"- SSE endpoints: **{sum(1 for r in routes if r['stream'] == 'sse')}**",
        f"- WebSocket endpoints: **{sum(1 for r in routes if r['kind'] == 'websocket')}**",
        f"- non-MVC servlets: **{sum(1 for r in routes if r['kind'] == 'servlet')}**",
        "",
        "| logical owner | endpoints |",
        "|---|---|",
    ]
    for owner, count in sorted(by_owner.items(), key=lambda kv: (-kv[1], kv[0])):
        lines.append(f"| {owner} | {count} |")

    lines += ["", "## Long-lived connections (must be owned by a security-aware host)", "",
              "| owner | method | path | kind | file |", "|---|---|---|---|---|"]
    for route in streams:
        lines.append(
            f"| {route['logical_owner']} | {route['http_method']} | `{route['path']}` | "
            f"{route['stream']} | `{route['file']}` |"
        )

    lines += ["", "## @Scheduled", "", f"- total: **{len(scheduled)}**", "",
              "| class | method | trigger | owner |", "|---|---|---|---|"]
    for item in scheduled:
        lines.append(
            f"| `{item['class'].split('.')[-1]}` | `{item['method']}` | `{item['trigger']}` | "
            f"{item['logical_owner']} |"
        )

    lines += ["", "## Tables", "", f"- total: **{len(tables)}**", "",
              "See `docs/table-inventory.yaml`; ownership is curated in `docs/table-ownership.yaml`.",
              "", "## Config keys", "",
              f"- total keys: **{len(config)}** (leaf: {sum(1 for c in config if c['leaf'])})",
              f"- unassigned leaves: **{len(unassigned_config)}**", ""]
    if unassigned_config:
        for entry in unassigned_config:
            lines.append(f"  - `{entry['key']}` ({entry['source']})")

    lines += ["", "## Cross-domain implementation imports", "",
              "Computed from `import com.xander.lab.<other domain>...`.",
              "", "| from \\ to | domains imported |", "|---|---|"]
    java = scan_java()
    for domain, targets in java["reverse_deps"].items():
        if targets:
            lines.append(f"| {domain} | {', '.join(targets)} |")

    lines += ["", "## Shared kernel reverse dependencies (V9 section 21 gate)", ""]
    violations = []
    for source, targets in java["reverse_deps"].items():
        if source in SHARED_KERNEL and targets:
            violations.append((source, targets))
    if violations:
        for source, targets in violations:
            lines.append(f"- **VIOLATION** `{source}` imports {', '.join(targets)}")
    else:
        lines.append("- none")

    lines.append("")
    return "\n".join(lines)


if __name__ == "__main__":
    raise SystemExit(main())

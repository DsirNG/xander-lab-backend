#!/usr/bin/env python3
"""DinQorAI microservice migration — machine inventory scanner.

Implements the V9 methodology of section 13: the machine owns the *key set*
("what the system actually has"), humans own the *semantics* ("who owns it,
how it migrates"). This script produces the key sets only. Curated files such
as ``docs/table-ownership.yaml`` are never overwritten by this script; they are
validated against the key sets by ``ci_gates.py``.

One deliberate exception, and it is a *field*-level exception rather than a
file-level one: ``docs/scheduled-ownership.yaml`` is curated, but the
``scan_ref`` pointer inside each entry is pure machine output (the file and line
of that ``@Scheduled`` annotation). It is regenerated here because it rots
silently: adding a single import above a scheduled method shifts every line
below it, and nothing about the task's *semantics* changes. Seven of twelve
pointers had already drifted by exactly one line before this was added.
``ci_gates.py``'s ``scheduled-scan-ref-freshness`` verifies the result.

Usage:
    python scripts/migration/scan.py            # regenerate machine inventories
    python scripts/migration/scan.py --stdout   # print instead of write
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import yamlutil  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
# V9 §18: the application lives in apps/core-host/, extracted libraries in libs/<name>/,
# and the cross-service contracts in contracts/<name>/. These are the roots the scanner
# treats as "the codebase"; see java_roots() for why the non-application roots have to be
# included rather than assumed.
APP_ROOT = REPO / "apps" / "core-host"
JAVA_ROOT = APP_ROOT / "src" / "main" / "java"
LIB_ROOT = REPO / "libs"
CONTRACTS_ROOT = REPO / "contracts"
RESOURCES = APP_ROOT / "src" / "main" / "resources"
DB_DIR = RESOURCES / "db"
OUT_DIR = REPO / "docs"


def module_source_roots() -> list[Path]:
    """``libs/*/src/main/java`` and ``contracts/*/src/main/java``, in stable order."""
    roots: list[Path] = []
    for parent in (LIB_ROOT, CONTRACTS_ROOT):
        roots.extend(sorted(path for path in parent.glob("*/src/main/java") if path.is_dir()))
    return roots


def java_roots() -> list[Path]:
    """Every Java source root in the repo, not just the application's.

    Scanning only the application root means that the moment a class is moved into a
    library or a contract module it disappears from *every* inventory below -- including
    the legacy-Redis key list, which is exactly how a moved constant would quietly stop
    being tracked. So every root is always scanned.
    """
    return [JAVA_ROOT, *module_source_roots()]


def java_files() -> list[Path]:
    return sorted(path for root in java_roots() for path in root.rglob("*.java"))


def shared_kernel_packages() -> dict[str, list[str]]:
    """``com.xander.lab.<declared package>`` -> the module dirs under ``libs/`` or
    ``contracts/`` that ship it.

    The **declared** package is what gets reported (``com.xander.lab.content.api``, not
    ``com.xander.lab.content``), and that distinction only starts to matter once a contract
    lives inside a business domain's namespace. ``contracts/integration-api`` ships
    ``com.xander.lab.integration.api``; reporting the first segment would make the gate
    demand a ``SHARED_KERNEL`` entry broad enough to also swallow ``integration.oss``,
    ``integration.mail`` and ``integration.mcp`` -- which would drop every one of those
    edges from the backlog while the coupling stayed exactly the same. Reporting the
    declared package keeps the required entry as narrow as the module actually is.

    Used by the ``shared-kernel-classification`` gate to check that ``SHARED_KERNEL``
    covers every package a non-application module ships. See the comment above
    ``SHARED_KERNEL`` for why a missing entry is worse than a loud failure: the coupling
    stays, the count drops.

    Contract modules count too, and for the same reason. A contract's whole purpose is to
    be the one legal cross-service dependency, so its package must be recognised as shared
    kernel -- otherwise every import of it looks like a cross-domain implementation import
    and the backlog number would *not* move when a type is moved into a contract. The
    extraction would look like it did nothing.
    """
    provided: dict[str, list[str]] = {}
    for root in module_source_roots():
        module = root.relative_to(REPO).as_posix().rsplit("/src/main/java", 1)[0]
        for path in root.rglob("*.java"):
            parts = path.relative_to(root).as_posix().split("/")
            if len(parts) >= 4 and parts[:3] == ["com", "xander", "lab"]:
                package = ".".join(parts[3:-1])
                provided.setdefault(package, [])
                if module not in provided[package]:
                    provided[package].append(module)
    return {name: sorted(mods) for name, mods in sorted(provided.items())}


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
# Packages that belong to the shared kernel rather than to a business domain.
#
# Entries are package **prefixes**, matched by ``shared_kernel_owner()`` taking the longest
# match, so they may be dotted. That is not decoration: 4 of the 8 contracts V9 section 18
# names (``agent-api`` / ``automation-api`` / ``integration-api`` / ``studio-api``) are
# named after a package that is *also* a business domain. Listing the bare domain would
# classify the whole domain as shared kernel -- dropping every ``integration.oss`` /
# ``integration.mail`` / ``integration.mcp`` edge from the backlog while the coupling was
# unchanged.
#
# Where the contract itself goes is a second question, and the answer is NOT the same for
# all four. ``integration`` works out to ``integration.api`` because that domain organises
# its packages by integration kind (``file`` / ``mail`` / ``mcp`` / ``oss``) and leaves
# ``.api`` free. The other three do not: ``agent`` / ``automation`` / ``studio`` each
# already own ``com.xander.lab.<domain>.api`` as their *frontend HTTP* layer (controllers,
# VOs, request bodies). Putting a contract there would (a) ship one package in two jars and
# (b) make the scanner classify those controllers as shared kernel, so every controller
# importing its own application layer becomes an ``<domain>.api -> <domain>`` edge --
# inflating the backlog by dozens of statements -- plus a
# ``shared-kernel-reverse-dependency`` violation for each. Those contracts therefore take
# the next free segment: ``agent.spi`` / ``automation.spi`` / ``studio.spi``.
#
# ``common`` / ``config`` / ``filter`` / ``util`` are the in-app shared packages; ``auth``
# is the package provided by ``libs/xander-auth-core`` (V9 §20); ``content`` / ``core.api``
# / ``payment.api`` / ``integration.api`` / ``agent.spi`` / ``automation.spi`` are the
# packages provided by ``contracts/*`` (V9 §18/§91).
#
# Contract packages belong here even though a contract is not a "library". A contract's
# whole purpose is to be the one legal cross-service dependency, so an import of one must
# not be counted as a cross-domain implementation import -- if it were, moving a type into
# a contract would leave the backlog number unchanged and the extraction would look like it
# did nothing.
#
# This is a hardcoded list and that is a real hazard: a new lib or contract module whose
# package is missing here is *silently* misfiled. Its classes are still scanned, but the
# cross-domain scanner treats the package as neither a domain nor shared kernel and drops
# every edge pointing at it -- so the backlog number goes DOWN while the coupling is
# unchanged. That is exactly the silent-omission failure V9 section 13 exists to prevent,
# so ``shared_kernel_packages()`` below derives the set from the tree and the
# ``shared-kernel-classification`` gate compares the two. Adding a module means adding its
# package here; the gate fails until you do.
SHARED_KERNEL = ["agent.spi", "auth", "automation.spi", "blogagent.spi", "common", "config", "content",
                 "core.api", "filter", "integration.api", "payment.api", "recitation.spi", "util"]

# Import prefixes a ``contracts/*`` module is allowed to use (V9 §19 / §21).
#
# Kept as a tuple so the ``contract-dependency-boundary`` gate has one place to read and a
# deliberate edit is the only way to widen it. The JDK is the whole list on purpose: a
# contract is depended on by every service, including the WebFlux Gateway, so any framework
# prefix here would be the super-common.jar §19 forbids, wearing a different name.
CONTRACT_ALLOWED_IMPORT_PREFIXES = ("java.",)

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


def shared_kernel_owner(package: str) -> str | None:
    """The ``SHARED_KERNEL`` entry that owns ``package``, or ``None``.

    Accepts a package name or a class FQCN -- callers pass both.

    Entries are package *prefixes* and may be dotted, which is what lets a contract be
    classified narrowly even when it sits inside a business domain's own namespace. The
    match takes the **longest** entry, and that is the whole mechanism:

        com.xander.lab.integration.api.UploadResponse -> integration.api  (shared kernel)
        com.xander.lab.integration.oss.OssService     -> None            (business domain)

    Without the dotted entry a contract under ``integration`` could only be expressed by
    listing ``integration`` itself, which would classify ``integration.oss`` / ``.mail`` /
    ``.mcp`` as shared kernel too and silently drop their edges from the backlog.
    """
    parts = package.split(".")
    if len(parts) < 4 or parts[:3] != ["com", "xander", "lab"]:
        return None
    rest = ".".join(parts[3:])
    best: str | None = None
    for entry in SHARED_KERNEL:
        if rest == entry or rest.startswith(entry + "."):
            if best is None or len(entry) > len(best):
                best = entry
    return best


def domain_of(package: str) -> str:
    """The owner of ``package``: a business domain, or the shared-kernel entry.

    Returning the shared-kernel entry (``content``, ``integration.api``) rather than a
    domain name is deliberate -- it is what makes an import of a contract invisible to the
    backlog scanner, because callers test ``target not in BUSINESS_DOMAINS``. A
    shared-kernel entry is never in ``BUSINESS_DOMAINS``, so those imports are skipped.

    For every package that existed before the dotted entries were introduced this returns
    exactly what the old first-segment implementation returned.
    """
    owner = shared_kernel_owner(package)
    if owner is not None:
        return owner
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


# ``@Foo`` / ``@Foo(...)`` / ``@com.example.Foo(...)`` -- used to strip sibling
# annotations when locating a method declaration.
ANNOTATION_STRIP_RE = re.compile(r"@[\w.]+\s*(?:\([^)]*\))?")


def method_signature(content: str, annotation_end: int) -> str:
    tail = content[annotation_end:annotation_end + 1500]
    boundary = re.search(r"\{", tail)
    signature = tail[:boundary.start()] if boundary else tail
    # Drop any further annotations sitting between the one we started from and the
    # declaration. ``@Scheduled`` is routinely accompanied by a sibling annotation
    # (``@SchedulerLock``, ``@Async``, ``@Transactional``); without this the first
    # ``word(`` in the text is that annotation, so every caller silently gets
    # ``SchedulerLock`` as the *method name* -- and for a class with several entry
    # points that wrong name also becomes part of the id.
    return ANNOTATION_STRIP_RE.sub("", signature)


def slugify(text: str) -> str:
    text = text.strip("/")
    # Keep the *name* of a path variable instead of deleting it. Deleting it made
    # ``/conversations`` and ``/conversations/{id}`` slug to the same string, so a
    # REST collection route and its detail route collapsed into one id -- and every
    # id-keyed gate then saw only one of the two. ``/a/{id}/b`` -> ``a-id-b``.
    text = re.sub(r"\{([^}]*)\}", r"\1", text)
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

    for path in java_files():
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
    for path in java_files():
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
    # Disambiguate collisions by appending the owning class name. One pass is not
    # enough: two mappings declared on the *same* class get the *same* suffix and
    # stay collided -- which is exactly how ``GET /conversations`` and
    # ``GET /conversations/{id}`` both became ``...-agentconversationcontroller``,
    # hiding one route from every id-keyed gate. The second pass guarantees that
    # the machine-owned key set really is a set.
    seen: Counter[str] = Counter()
    for route in routes:
        seen[route["id"]] += 1
    for route in routes:
        if seen[route["id"]] > 1:
            suffix = route["file"].rsplit("/", 1)[-1].replace(".java", "").lower()
            route["id"] = f"{route['id']}-{suffix}"

    taken: set[str] = set()
    for route in routes:
        candidate = route["id"]
        if candidate in taken:
            index = 2
            while f"{candidate}-{index}" in taken:
                index += 1
            candidate = f"{candidate}-{index}"
        taken.add(candidate)
        route["id"] = candidate
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
    # Sort by (class, line) rather than class alone: a class may contribute more
    # than one entry point, and the order between them must be reproducible.
    items = sorted(java["scheduled"], key=lambda item: (item["class"], item["line"]))
    # Ids are stable identifiers that docs/scheduled-ownership.yaml references by
    # name, so keep the bare class name whenever it is unambiguous. Only a class
    # with more than one @Scheduled gets the method appended -- without this, two
    # entry points collapse into one id, and the loser becomes invisible to every
    # gate (deleting it would not even change the inventory count).
    per_class: dict[str, int] = {}
    for item in items:
        per_class[item["class"]] = per_class.get(item["class"], 0) + 1
    for item in items:
        if per_class[item["class"]] > 1 and item["method"]:
            item["id"] = f"{item['id']}-{kebab(item['method'])}"
    return {
        "generated_by": "scripts/migration/scan.py",
        "note": "Machine-owned key set of every @Scheduled entry point.",
        "counts": {"total": len(items)},
        "scheduled": items,
    }


OWNERSHIP_SCHEDULED = OUT_DIR / "scheduled-ownership.yaml"

# The only field scan.py is allowed to rewrite inside a curated file, and only
# because it is machine output rather than human judgement. See the module
# docstring for why it cannot be left to hand-maintenance.
# Public because ci_gates.py reuses them: the writer and the verifier must agree
# on how a scan_ref line is parsed, and two copies would eventually diverge.
OWNERSHIP_SCAN_REF_RE = re.compile(r"^(\s*)scan_ref:\s*(\S+)\s*$")
OWNERSHIP_ID_RE = re.compile(r"^  - id:\s*(\S+)\s*$")


def refresh_scheduled_scan_refs(inventory: dict) -> int:
    """Point every ``scan_ref`` in scheduled-ownership.yaml at the current line.

    Everything else in that file -- owner, scope, notes -- is human semantics and
    is left byte-for-byte alone. Only the ``file:line`` pointer is rewritten.
    """
    if not OWNERSHIP_SCHEDULED.exists():
        return 0
    truth = {item["id"]: f"{item['file']}:{item['line']}" for item in inventory["scheduled"]}
    lines = OWNERSHIP_SCHEDULED.read_text(encoding="utf-8").split("\n")
    current: str | None = None
    refreshed = 0
    for index, line in enumerate(lines):
        id_match = OWNERSHIP_ID_RE.match(line)
        if id_match:
            current = id_match.group(1)
            continue
        ref_match = OWNERSHIP_SCAN_REF_RE.match(line)
        if not ref_match or current not in truth:
            continue
        if ref_match.group(2) != truth[current]:
            lines[index] = f"{ref_match.group(1)}scan_ref: {truth[current]}"
            refreshed += 1
    if refreshed:
        # newline="\n" matters: the repo is LF, and letting Python translate
        # would silently convert the whole file to CRLF.
        OWNERSHIP_SCHEDULED.write_text("\n".join(lines), encoding="utf-8", newline="\n")
    return refreshed


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


# ---------------------------------------------------------------------------
# Cross-domain implementation imports (V9 section 81)
# ---------------------------------------------------------------------------

def _layer_of(fqcn: str) -> str:
    """Layer segment of a domain import.

    ``com.xander.lab.agent.api.Foo`` -> ``api``;
    ``com.xander.lab.agent.application.Foo`` -> ``application``.

    ``integration`` is organised by integration kind (``file`` / ``mail`` /
    ``mcp`` / ``oss``) instead of by DDD layer, so ``com.xander.lab.integration
    .mcp.client.Foo`` returns ``mcp``. That is intentional: it is not the
    ``api`` layer, so it counts as implementation.
    """
    parts = fqcn.split(".")
    return parts[4] if len(parts) > 4 else ""


def build_cross_domain_imports() -> dict:
    """The Wave 0B backlog: which domains import which others' *implementation*.

    V9 section 81 says "约 280 处" cross-domain implementation imports. That is an
    estimate, and it is the kind of number section 13 says the machine must own.
    The machine count also splits it in a way the estimate cannot:

    * ``api``-layer imports (``com.xander.lab.<domain>.api.*``) are the *contract*
      surface Wave 0B is building. They are the only legal cross-domain import
      afterwards, so they are counted separately and are never backlog.
    * Implementation imports that stay inside one deployment unit are not a Wave
      0B problem either: those two domains ship in the same process after the
      split, so the call never turns into a network call.
    * Implementation imports that **cross a deployment unit** are the real
      backlog -- each one must become a contract call, an event, or move.

    Shared-kernel -> business imports are deliberately NOT included; the section
    21 gate (``shared-kernel-reverse-dependency``) owns that rule. Keeping the
    two separate means neither can hide behind the other.
    """
    java = scan_java()
    business = set(BUSINESS_DOMAINS)
    edges: dict[tuple[str, str], dict] = {}
    shared_kernel_sources: set[str] = set()
    unclassified_sources: set[str] = set()

    for rel, imports in sorted(java["imports"].items()):
        marker = "/com/xander/lab/"
        if marker not in rel:
            continue
        # The source package is the *directory* path, not just its first segment: a
        # contract at contracts/integration-api/.../integration/api/Foo.java is the
        # package com.xander.lab.integration.api, and taking the first segment would file
        # the contract's own sources under the business domain it is a contract for.
        # domain_of() then resolves it to the longest matching SHARED_KERNEL entry.
        tail = rel.split(marker, 1)[1].split("/")
        if len(tail) >= 2:
            source = domain_of("com.xander.lab." + ".".join(tail[:-1]))
        else:
            source = tail[0]
        if source not in business:
            # Recorded rather than skipped: a source package that is neither a
            # domain nor the shared kernel would otherwise vanish from every
            # count in this file, which is exactly the silent-omission failure
            # mode section 13 exists to prevent.
            if source in SHARED_KERNEL:
                shared_kernel_sources.add(source)
            else:
                unclassified_sources.add(source)
            continue
        for fqcn in imports:
            target = domain_of(fqcn)
            if target not in business or target == source:
                continue
            entry = edges.setdefault((source, target), {
                "from": source,
                "to": target,
                "from_unit": DEPLOYMENT_UNIT.get(source, "dinqor-core-host"),
                "to_unit": DEPLOYMENT_UNIT.get(target, "dinqor-core-host"),
                "crosses_deployment_unit":
                    DEPLOYMENT_UNIT.get(source, "dinqor-core-host")
                    != DEPLOYMENT_UNIT.get(target, "dinqor-core-host"),
                "api_statements": 0,
                "implementation_statements": 0,
                "implementation_files": [],
            })
            if _layer_of(fqcn) == "api":
                entry["api_statements"] += 1
            else:
                entry["implementation_statements"] += 1
                if rel not in entry["implementation_files"]:
                    entry["implementation_files"].append(rel)

    ordered = sorted(edges.values(),
                     key=lambda e: (-e["implementation_statements"], e["from"], e["to"]))
    implementation = [e for e in ordered if e["implementation_statements"]]
    crossing = [e for e in implementation if e["crosses_deployment_unit"]]
    return {
        "generated_by": "scripts/migration/scan.py",
        "note": (
            "V9 section 81 backlog. Only implementation_statements counts: "
            "api_statements are the contract surface Wave 0B is building, and "
            "same-deployment-unit edges never become network calls. "
            "Shared-kernel -> business imports are not listed here; the section 21 "
            "gate owns them."
        ),
        "counts": {
            "implementation_statements":
                sum(e["implementation_statements"] for e in implementation),
            "implementation_edges": len(implementation),
            "cross_deployment_unit_statements":
                sum(e["implementation_statements"] for e in crossing),
            "cross_deployment_unit_edges": len(crossing),
            "api_statements": sum(e["api_statements"] for e in ordered),
            "api_edges": sum(1 for e in ordered if e["api_statements"]),
            "shared_kernel_sources": sorted(shared_kernel_sources),
            "unclassified_sources": sorted(unclassified_sources),
        },
        "edges": ordered,
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
Re-running the scanner REGENERATES this file, carrying over the hand-filled fields
of entries it recognises. The merge key is `(old.method, old.path)` — NOT `id` —
because ids are derived from the path and must stay free to change.

Human-owned fields: clients.{web,miniprogram,admin}, status, compatibility.
Machine-owned fields: id, owner, old.{method,path}, new.{method,path}.

CI gates: `api-migration-key-coverage` (every inventory route present),
`inventory-integrity` (ids unique, counts.total truthful),
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
        "cross-domain-imports.yaml": build_cross_domain_imports(),
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
        refreshed = refresh_scheduled_scan_refs(artifacts["scheduled-inventory.yaml"])
        print(f"refreshed {refreshed} scan_ref pointer(s) in "
              f"{OWNERSHIP_SCHEDULED.relative_to(REPO)}")

    if not args.stdout:
        report = build_report(artifacts)
        (OUT_DIR / "migration-scan-report.md").write_text(report, encoding="utf-8", newline="\n")
        print("wrote docs/migration-scan-report.md")
    return 0


_API_ENTRY_RE = re.compile(r"^  - (\w+):\s*(.*)$")
_API_SECTION_RE = re.compile(r"^    (\w+):\s*$")
_API_FIELD_RE = re.compile(r"^    (\w+):\s*(.+)$")
_API_MEMBER_RE = re.compile(r"^      (\w+):\s*(.+)$")
_API_SECTIONS = ("old", "new", "clients", "compatibility")
_API_HUMAN_FIELDS = ("clients", "compatibility", "status")


def _yaml_scalar(text: str):
    """Decode one scalar written by :mod:`yamlutil` (JSON scalars only)."""
    text = text.strip()
    try:
        return json.loads(text)
    except (ValueError, TypeError):
        return text


def read_api_migration() -> dict[tuple, dict]:
    """Read the human-owned fields out of the existing ``api-migration.yaml``.

    ``scan.py`` must stay dependency-free (it is the script people re-run most), so
    this parses the narrow subset of YAML that :mod:`yamlutil` emits rather than
    importing PyYAML. Only the fields a human edits are returned.
    """
    target = OUT_DIR / "api-migration.yaml"
    if not target.exists():
        return {}

    carried: dict[tuple, dict] = {}
    current: dict | None = None
    section: str | None = None

    for line in target.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if _API_ENTRY_RE.match(line):
            # Flush *before* starting the next entry: every entry starts with
            # ``- id:``, so a trailing flush would never run and all but the last
            # entry would be silently dropped.
            _flush_api_entry(carried, current)
            current = {"old": {}, "clients": {}, "compatibility": {}, "status": None}
            section = None
            continue
        if current is None:
            continue
        member = _API_MEMBER_RE.match(line)
        if member and section in _API_SECTIONS:
            if section != "new":
                current[section][member.group(1)] = _yaml_scalar(member.group(2))
            continue
        heading = _API_SECTION_RE.match(line)
        if heading:
            section = heading.group(1) if heading.group(1) in _API_SECTIONS else None
            continue
        field = _API_FIELD_RE.match(line)
        if field:
            section = None
            if field.group(1) == "status":
                current["status"] = _yaml_scalar(field.group(2))
            continue

    _flush_api_entry(carried, current)
    return carried


def _flush_api_entry(carried: dict, current: dict | None) -> None:
    if not current:
        return
    key = (current["old"].get("method"), current["old"].get("path"))
    if key == (None, None):
        return
    carried[key] = {field: current[field] for field in _API_HUMAN_FIELDS
                    if current.get(field) not in (None, {})}


def write_api_migration(routes: list[dict]) -> None:
    """Merge docs/api-migration.yaml without clobbering hand-filled fields.

    The merge key is ``(old.method, old.path)`` -- deliberately NOT ``id``. Ids are
    derived from the path, so any change to the id scheme would otherwise turn every
    existing entry into a ghost and append a fresh duplicate of it. ``old.path`` is
    machine-owned and stable, so human-curated ``clients`` / ``status`` /
    ``compatibility`` survive an id-scheme change.
    """
    target = OUT_DIR / "api-migration.yaml"
    entries = build_api_migration(routes)
    carried = read_api_migration()

    preserved = 0
    for entry in entries:
        old_entry = carried.get(_api_join_key(entry))
        if old_entry is None:
            continue
        preserved += 1
        for field in _API_HUMAN_FIELDS:
            if field in old_entry:
                entry[field] = old_entry[field]

    payload = {
        "generated_by": "scripts/migration/scan.py",
        "counts": {"total": len(entries)},
        "entries": entries,
    }
    target.write_text(yamlutil.dumps(payload, API_MIGRATION_HEADER),
                      encoding="utf-8", newline="\n")
    dropped = len(carried) - preserved
    print(f"wrote docs/api-migration.yaml  {{'total': {len(entries)}, "
          f"'preserved': {preserved}, 'dropped': {dropped}}}")


def _api_join_key(entry: dict) -> tuple:
    old = entry.get("old") or {}
    return (old.get("method"), old.get("path"))


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

    cross = artifacts["cross-domain-imports.yaml"]
    counts = cross["counts"]
    lines += ["", "## Cross-domain implementation imports (V9 section 81)", "",
              "Counted from `import com.xander.lab.<other domain>.<layer>...`. Only the",
              "implementation layer is backlog; `api` is the contract surface Wave 0B builds.",
              "",
              f"- implementation statements: **{counts['implementation_statements']}** "
              f"across {counts['implementation_edges']} edges",
              f"- of which cross a deployment unit (the real backlog): "
              f"**{counts['cross_deployment_unit_statements']}** across "
              f"{counts['cross_deployment_unit_edges']} edges",
              f"- api-layer statements (contract surface): **{counts['api_statements']}**",
              "",
              "| from | to | impl | api | crosses unit | from unit -> to unit |",
              "|---|---|---|---|---|---|"]
    for edge in cross["edges"]:
        lines.append(
            f"| {edge['from']} | {edge['to']} | {edge['implementation_statements']} | "
            f"{edge['api_statements']} | {'yes' if edge['crosses_deployment_unit'] else 'no'} | "
            f"{edge['from_unit']} -> {edge['to_unit']} |"
        )

    java = scan_java()
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

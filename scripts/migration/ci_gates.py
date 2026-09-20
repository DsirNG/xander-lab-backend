#!/usr/bin/env python3
"""DinQorAI microservice migration — CI gates (V9 section 88).

Runs every machine-checkable gate from the plan's CI list against the curated
inventories in ``docs/`` and the live source tree.

Usage:
    python scripts/migration/ci_gates.py            # run all gates
    python scripts/migration/ci_gates.py --list     # list gate names
    python scripts/migration/ci_gates.py auth-policy-coverage   # run one gate

Exit code 0 when every gate passes, 1 otherwise. Gates that cannot run yet
(because the target module does not exist in Wave 0A) report ``SKIP`` and do
not fail the build — they are printed so they cannot be forgotten.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import xml.etree.ElementTree
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

try:
    import yaml
except ImportError:  # pragma: no cover - CI must install pyyaml
    print("ERROR: PyYAML is required to read the curated inventories.")
    print("       pip install pyyaml")
    raise SystemExit(2)

import scan  # noqa: E402

REPO = scan.REPO
DOCS = REPO / "docs"
CAPABILITY_LOCK = DOCS / "capability-baseline.lock"
# The frozen-history manifest for the 80 archived SQL scripts (V9 §64 step 3).
LEGACY_SUMS = REPO / "migrations" / "legacy" / "SHA256SUMS"

# Shared-kernel violations that exist today and are scheduled for Wave 0B.
# The gate fails only when a NEW violation appears, so the baseline cannot rot
# while the fix is pending. Removing an entry from this list is part of the fix.
KNOWN_SHARED_KERNEL_VIOLATIONS = {
    ("config", "identity"),
    ("common", "integration"),
}

RESULTS: list[tuple[str, str, str]] = []


def load(name: str):
    path = DOCS / name
    if not path.exists():
        raise SystemExit(f"missing inventory: {path}")
    with open(path, encoding="utf-8") as handle:
        return yaml.safe_load(handle)


def report(gate: str, ok: bool, detail: str) -> None:
    RESULTS.append((gate, "PASS" if ok else "FAIL", detail))


def skip(gate: str, detail: str) -> None:
    RESULTS.append((gate, "SKIP", detail))


# ---------------------------------------------------------------------------
# Path matching (mirrors AuthInterceptor.matchPath, generalised to mid-path `*`)
# ---------------------------------------------------------------------------

def pattern_matches(pattern: str, path: str) -> bool:
    if not pattern:
        return False
    if pattern.endswith("/**"):
        prefix = pattern[:-3]
        return path == prefix or path.startswith(prefix + "/")
    if pattern.endswith("/*"):
        prefix = pattern[:-2]
        if path == prefix or path.startswith(prefix + "/"):
            return True
    regex = "^" + re.escape(pattern).replace(r"\*", "[^/]+") + "$"
    return re.match(regex, path) is not None


def methods_of(rule_match: dict) -> list[str]:
    method = rule_match.get("method", "ANY")
    if isinstance(method, list):
        return [str(item).upper() for item in method]
    return [str(method).upper()]


def rule_matches(rule_match: dict, http_method: str, path: str) -> bool:
    methods = methods_of(rule_match)
    if "ANY" not in methods and http_method.upper() not in methods:
        return False
    if "path" in rule_match and rule_match["path"] == path:
        return True
    if "path_pattern" in rule_match and pattern_matches(rule_match["path_pattern"], path):
        return True
    if "path_prefix" in rule_match and path.startswith(rule_match["path_prefix"]):
        return True
    if "path_prefix_also" in rule_match and path.startswith(rule_match["path_prefix_also"]):
        return True
    if "path_contains" in rule_match and rule_match["path_contains"] in path:
        return True
    return False


def collect_explicit_rules(policy: dict) -> list[tuple[str, dict, str]]:
    """Return (group, match-block, access) for every explicit policy rule.

    Order matters: it mirrors ``evaluation_order`` in auth-policy.yaml, so the
    first hit is the rule the Gateway would apply.
    """
    rules: list[tuple[str, dict, str]] = []
    for group in ("excluded", "prefix_rules", "explicit_private_get",
                  "explicit_public_write", "public_get", "public_non_api",
                  "protected", "streams"):
        for entry in policy.get(group) or []:
            if not isinstance(entry, dict) or "match" not in entry:
                continue
            access = entry.get("access")
            if access is None:
                access = "public" if group in ("public_get", "public_non_api") else "authenticated"
            rules.append((group, entry["match"], access))
    return rules


# ---------------------------------------------------------------------------
# Gates
# ---------------------------------------------------------------------------

def gate_route_inventory_drift() -> None:
    gate = "route-inventory-drift"
    on_disk = load("route-inventory.yaml")
    regenerated = scan.build_route_inventory()
    stored = {r["id"] for r in on_disk["routes"]}
    fresh = {r["id"] for r in regenerated["routes"]}
    added = sorted(fresh - stored)
    removed = sorted(stored - fresh)
    if not added and not removed:
        report(gate, True, f"{len(fresh)} routes, no drift")
        return
    report(gate, False,
           f"stale inventory — added={added or 'none'} removed={removed or 'none'}; "
           f"re-run scripts/migration/scan.py")


def gate_api_migration_key_coverage() -> None:
    gate = "api-migration-key-coverage"
    routes = load("route-inventory.yaml")["routes"]
    migration = load("api-migration.yaml")["entries"]
    covered = {entry["id"] for entry in migration}
    missing = sorted(
        route["id"] for route in routes
        if route["kind"] != "servlet" and route["id"] not in covered
    )
    if missing:
        report(gate, False, f"{len(missing)} routes without a migration entry: {missing[:8]}")
        return
    no_target = [e["id"] for e in migration if e.get("status") == "no_v2_target"]
    report(gate, True,
           f"{len(covered)} entries cover every route "
           f"({len(no_target)} marked no_v2_target)")


def gate_auth_policy_coverage() -> None:
    """Every route must resolve to an explicit rule.

    The v1 interceptor fails *open* for unmatched reads ("未匹配的 GET 保持公开").
    That is exactly the failure mode V9 section 103 asks about ("PRIVATE GET 无
    PUBLIC 误放"), so this gate fails when a route only reaches `public`
    through the catch-all default instead of an explicit public rule.
    """
    gate = "auth-policy-coverage"
    policy = load("auth-policy.yaml")
    routes = load("route-inventory.yaml")["routes"]
    rules = collect_explicit_rules(policy)
    mcp_paths = {entry["path"] for entry in (policy.get("mcp_inbound") or [])}

    unlisted_public: list[str] = []
    unlisted_other: list[str] = []
    for route in routes:
        if route["kind"] == "servlet" or route["path"] in mcp_paths:
            continue
        hit = None
        for group, rule_match, access in rules:
            if rule_matches(rule_match, route["http_method"], route["path"]):
                hit = (group, access)
                break
        if hit is None:
            if route["http_method"] in ("GET", "HEAD"):
                unlisted_public.append(f"{route['http_method']} {route['path']}")
            else:
                unlisted_other.append(f"{route['http_method']} {route['path']}")

    if unlisted_public:
        report(gate, False,
               f"{len(unlisted_public)} read routes only reach `public` via the "
               f"catch-all default (fail-open): {unlisted_public[:8]}")
        return
    report(gate, True,
           f"every route matched by an explicit rule "
           f"({len(unlisted_other)} writes rely on the authenticated default, which is fail-closed)")


def gate_auth_policy_token_location() -> None:
    """Every SSE / WS endpoint must declare an explicit token_location.

    Resolved against the `streams` group only: an earlier generic rule (e.g.
    `GET /api/agent/conversations/*`) also matches these paths, but it is the
    stream-specific declaration that the Gateway needs in order to accept the
    `?token=` handshake path.
    """
    gate = "auth-policy-token-location"
    policy = load("auth-policy.yaml")
    routes = load("route-inventory.yaml")["routes"]
    streams = [r for r in routes if r["stream"] != "none" and r["kind"] != "servlet"]
    stream_rules = policy.get("streams") or []
    allowed = {"header", "query", "header_or_query", "n/a"}
    problems: list[str] = []
    for route in streams:
        entry = next(
            (e for e in stream_rules
             if isinstance(e, dict) and "match" in e
             and rule_matches(e["match"], route["http_method"], route["path"])),
            None,
        )
        if entry is None:
            problems.append(f"{route['http_method']} {route['path']}: no `streams` entry")
            continue
        value = entry.get("token_location")
        if value not in allowed:
            problems.append(f"{route['http_method']} {route['path']}: token_location={value!r}")
    if problems:
        report(gate, False,
               f"{len(problems)}/{len(streams)} stream endpoints lack a valid token_location: "
               f"{problems[:6]}")
        return
    report(gate, True, f"{len(streams)} stream endpoints all declare token_location")


def gate_scheduled_ownership_coverage() -> None:
    gate = "scheduled-ownership-coverage"
    inventory = load("scheduled-inventory.yaml")["scheduled"]
    ownership = load("scheduled-ownership.yaml")["scheduled"]
    # A class with two @Scheduled methods used to yield two inventory rows sharing
    # one id, so the ownership gate saw only one of them. Fail loudly instead:
    # a duplicate id means at least one entry point has no owner.
    ids = [item["id"] for item in inventory]
    duplicates = sorted({name for name in ids if ids.count(name) > 1})
    if duplicates:
        report(gate, False, "duplicate @Scheduled ids (disambiguate by method): "
                            f"{duplicates}")
        return
    known = {entry["id"] for entry in ownership}
    missing = sorted(item["id"] for item in inventory if item["id"] not in known)
    if missing:
        report(gate, False, f"@Scheduled entries without ownership: {missing}")
        return
    report(gate, True, f"{len(inventory)}/{len(inventory)} scanned entries owned "
                       f"(+{len(known) - len(inventory)} planned)")


def gate_execution_scope_validation() -> None:
    gate = "execution-scope-validation"
    ownership = load("scheduled-ownership.yaml")["scheduled"]
    problems: list[str] = []
    for entry in ownership:
        scope = entry.get("execution_scope")
        strategy = entry.get("singleton_strategy")
        if scope not in ("global_once", "per_instance"):
            problems.append(f"{entry['id']}: execution_scope={scope!r}")
            continue
        if scope == "global_once" and (not strategy or strategy == "none"):
            problems.append(f"{entry['id']}: global_once without singleton_strategy")
        if scope == "per_instance" and strategy not in ("none", "local_only"):
            problems.append(f"{entry['id']}: per_instance with global lock ({strategy})")
        for field in ("logical_owner", "current_deployment_owner", "execution_mode"):
            if not entry.get(field):
                problems.append(f"{entry['id']}: {field} is empty")
    if problems:
        report(gate, False, "; ".join(problems[:8]))
        return
    report(gate, True, f"{len(ownership)} entries valid")


def gate_table_ownership_drift() -> None:
    gate = "table-ownership-drift"
    inventory = {t["table"] for t in load("table-inventory.yaml")["tables"]}
    ownership_doc = load("table-ownership.yaml")
    owned = [entry["table"] for entry in ownership_doc["tables"]]
    owned_set = set(owned)
    missing = sorted(inventory - owned_set)
    ghosts = sorted(owned_set - inventory)
    duplicates = sorted({name for name in owned if owned.count(name) > 1})
    problems = []
    if missing:
        problems.append(f"{len(missing)} unowned tables: {missing[:8]}")
    if ghosts:
        problems.append(f"{len(ghosts)} ghost entries: {ghosts[:8]}")
    if duplicates:
        problems.append(f"multiple owners: {duplicates}")
    if problems:
        report(gate, False, "; ".join(problems))
        return
    report(gate, True, f"{len(owned_set)} tables, single owner each")


def gate_config_ownership_drift() -> None:
    gate = "config-ownership-drift"
    inventory = load("config-inventory.yaml")["config"]
    leaves = {entry["key"] for entry in inventory if entry["leaf"]}
    owned = {entry["key"] for entry in load("config-ownership.yaml")["config"]}
    missing = sorted(leaves - owned)
    if missing:
        report(gate, False, f"{len(missing)} config keys without an owner: {missing[:8]}")
        return
    report(gate, True, f"{len(owned)} leaf keys classified")


def gate_shared_kernel_reverse_dependency() -> None:
    gate = "shared-kernel-reverse-dependency"
    java = scan.scan_java()
    violations = {
        (source, target)
        for source, targets in java["reverse_deps"].items()
        if source in scan.SHARED_KERNEL
        for target in targets
    }
    new = sorted(violations - KNOWN_SHARED_KERNEL_VIOLATIONS)
    fixed = sorted(KNOWN_SHARED_KERNEL_VIOLATIONS - violations)
    if new:
        report(gate, False, f"new shared-kernel -> business-domain imports: {new}")
        return
    detail = f"{len(violations)} known violation(s) still pending Wave 0B"
    if fixed:
        detail += f"; {len(fixed)} already fixed, remove from the allow-list: {fixed}"
    report(gate, True, detail)


def gate_shared_kernel_classification() -> None:
    """``SHARED_KERNEL`` must cover exactly the module packages, and stay narrow.

    ``SHARED_KERNEL`` is a hardcoded prefix list and the cross-domain scanner classifies
    imports by package name. So a module whose package is missing from that list is
    misfiled *silently*: its classes are still scanned, but an import of one is neither a
    business-domain edge nor a shared-kernel source, so every edge pointing at it is
    dropped. The backlog number then goes DOWN while the coupling is unchanged.

    That is the silent-omission failure V9 section 13 exists to prevent, so the key set is
    derived from the tree and compared instead of trusted.

    Contract modules are covered deliberately: a contract exists to be the legal
    cross-service dependency, so forgetting to classify it would make the extraction that
    created it invisible in the very number it was supposed to move.

    Two criteria:

    1. **Coverage.** Every package a module declares must be matched by some entry
       (by equality or as a sub-package), reported at the *declared* package depth.
    2. **Narrowness.** No entry may name a business domain or be a prefix of one. This is
       the mirror image of criterion 1, and the harm is *not* that the domain would be
       misfiled as shared kernel -- ``BUSINESS_DOMAINS`` wins in the scanner, so the
       backlog count would not move at all. The harm is that a contract listed this way
       becomes **invisible**: ``domain_of`` would return the broad domain name, which is
       still a business domain, so a type moved into the contract keeps being counted as a
       domain edge and the extraction moves no number. It also raises false
       ``shared-kernel-reverse-dependency`` violations for every import the domain makes.
       A contract for such a domain must therefore be listed as ``integration.api``.
    """
    gate = "shared-kernel-classification"
    provided = scan.shared_kernel_packages()
    declared = set(scan.SHARED_KERNEL)
    domains = set(scan.BUSINESS_DOMAINS)

    def covered_by(package: str, entry: str) -> bool:
        return package == entry or package.startswith(entry + ".")

    uncovered = sorted(pkg for pkg in provided
                       if scan.shared_kernel_owner(f"com.xander.lab.{pkg}") is None)
    if uncovered:
        where = ", ".join(f"{pkg} ({', '.join(provided[pkg])})" for pkg in uncovered)
        report(gate, False,
               f"module(s) ship package(s) {where} that SHARED_KERNEL does not cover -- "
               f"imports of them would be dropped from the cross-domain baseline without "
               f"any coupling being removed. Add them to SHARED_KERNEL in "
               f"scripts/migration/scan.py")
        return

    too_broad = sorted(entry for entry in scan.SHARED_KERNEL
                       if any(covered_by(domain, entry) for domain in domains))
    if too_broad:
        report(gate, False,
               f"SHARED_KERNEL entr(ies) {too_broad} name a business domain or a prefix of "
               f"one. A business domain always wins in the scanner, so this does NOT "
               f"misfile the domain -- it makes any contract under it invisible: the "
               f"contract's types would still be counted as domain edges, so moving a type "
               f"into it would move no number, and every import the domain makes would "
               f"raise a false shared-kernel-reverse-dependency violation. List the "
               f"contract narrowly instead (e.g. 'integration.api', not 'integration')")
        return

    in_app = sorted(entry for entry in scan.SHARED_KERNEL
                    if not any(covered_by(pkg, entry) for pkg in provided)
                    and entry not in domains)
    detail = (f"all {len(provided)} module package(s) classified: "
              + ", ".join(f"{pkg} <- {'/'.join(provided[pkg])}" for pkg in sorted(provided)))
    if in_app:
        detail += (f"; SHARED_KERNEL also lists {in_app}, which no module provides yet "
                   f"(still in-app shared packages)")
    report(gate, True, detail)


def gate_contract_dependency_boundary() -> None:
    """A contract module must stay dependency-free (V9 §19 / §21).

    V9 §18 gives ``contracts/*-api`` to the cross-service contracts, and §91 says Wave 0B
    builds them. Their whole reason to exist is that *every* service may depend on them --
    including the WebFlux Gateway, which §19 says fails to start the moment
    ``spring-boot-starter-web`` reaches its classpath.

    So the boundary is not a style preference, and it is also not self-enforcing: a
    contract is the single most convenient place to "just put the shared helper", and a
    contract that has grown a framework dependency has silently become the super-common.jar
    §19 forbids. There is no compile error for that, and every existing test stays green.
    Hence a gate.

    Two criteria:

    1. **Java sources import nothing but the JDK and their own package.** Any
       ``com.xander.lab.<other>.*`` import would also mean the contract is reaching back
       into a business domain, which reverses the dependency the contract exists to fix.
    2. **The module POM declares no compile or runtime dependency.** ``test`` scope is
       fine: a contract's normalisation behaviour is worth pinning (V9 §2.3), and test
       scope does not reach consumers.

    Widening the allow-list is allowed but must be deliberate: add to
    ``CONTRACT_ALLOWED_IMPORT_PREFIXES`` and say why in the commit message.
    """
    gate = "contract-dependency-boundary"
    if not scan.CONTRACTS_ROOT.is_dir():
        report(gate, True, "no contracts/ directory yet")
        return

    problems: list[str] = []
    modules = 0
    sources = 0
    for module_dir in sorted(path for path in scan.CONTRACTS_ROOT.iterdir() if path.is_dir()):
        pom = module_dir / "pom.xml"
        modules += 1
        if not pom.is_file():
            problems.append(f"{module_dir.name}: no pom.xml")
            continue

        text = pom.read_text(encoding="utf-8")
        for match in re.finditer(r"<dependency>(.*?)</dependency>", text, re.S):
            block = match.group(1)
            scope = re.search(r"<scope>\s*([^<]+?)\s*</scope>", block)
            if scope and scope.group(1) == "test":
                continue
            artifact = re.search(r"<artifactId>\s*([^<]+?)\s*</artifactId>", block)
            problems.append(
                f"{module_dir.name}/pom.xml: dependency "
                f"{artifact.group(1) if artifact else '?'} is not test-scoped; a contract "
                f"must not pull a framework onto every service's classpath")

        source_root = module_dir / "src" / "main" / "java"
        if not source_root.is_dir():
            continue
        own_package = None
        for path in sorted(source_root.rglob("*.java")):
            sources += 1
            rel = path.relative_to(scan.REPO).as_posix()
            body = path.read_text(encoding="utf-8", errors="replace")
            if own_package is None:
                pkg = re.search(r"^package\s+([\w.]+)\s*;", body, re.M)
                own_package = pkg.group(1) if pkg else ""
            for imported in re.findall(r"^import\s+(?:static\s+)?([\w.]+)\s*;", body, re.M):
                if imported.startswith(scan.CONTRACT_ALLOWED_IMPORT_PREFIXES):
                    continue
                if own_package and (imported == own_package
                                    or imported.startswith(own_package + ".")):
                    continue
                problems.append(f"{rel}: imports {imported}")

    if problems:
        report(gate, False,
               f"{len(problems)} contract boundary violation(s): "
               + "; ".join(problems[:5]) + (" ..." if len(problems) > 5 else ""))
        return
    report(gate, True,
           f"{modules} contract module(s), {sources} source file(s): JDK-only imports, "
           f"no compile/runtime dependency")


def gate_cross_domain_import_baseline() -> None:
    """V9 section 81: the cross-domain implementation-import backlog must shrink.

    The plan estimates "约 280" such imports. ``docs/cross-domain-imports.yaml``
    holds the machine count instead (section 13: the machine owns the key set),
    and the first machine count was much lower -- 172 statements, of which only
    125 crossed a deployment unit and therefore had to become contracts at all.
    Wave 0B has been shrinking that ever since; the message below reports the
    live figure, so read it as the current backlog, not as the 172 baseline.

    This gate fails when any edge grows, so a new cross-domain implementation
    import cannot be added while the backlog is being worked off. Shrinking is
    expected; when an edge reaches zero its baseline entry must be deleted,
    otherwise the remaining backlog looks bigger than it is and the Wave 0B
    progress number stops meaning anything.

    ⚠️ KNOWN HOLE (measured 2026-09-17, not yet fixed): the "baseline" below is
    ``docs/cross-domain-imports.yaml`` itself, and ``scan.py`` *overwrites* that
    file. So in the documented order -- scan first, then gates -- the baseline and
    the live scan are the same data and this gate cannot fail. What it actually
    detects today is "code changed without re-running scan.py", not "the backlog
    grew". Measured: put one constant back on ``BlogNotification`` and have
    ``knowledgebase`` import it again -> scan reports 42 -> 43 statements and the
    edge returns, yet this gate still reports PASS.

    The file is machine output (scan.py's own docstring says curated files are
    never overwritten by it), so using it as its own baseline breaks the
    machine-key-set / curated-baseline split that ``capability-baseline.yaml`` +
    ``capability-baseline.lock`` follow. Fixing it means either adding a curated
    baseline file or reading the baseline from the last commit; both are a
    deliberate change, tracked in
    ``docs/migration/adr-contract-boundaries.md`` section 10. Until then, do not
    read a green run here as evidence that the backlog did not grow -- compare the
    statement count in the detail line against the previous run by hand.
    """
    gate = "cross-domain-import-baseline"
    baseline = load("cross-domain-imports.yaml")
    live = scan.build_cross_domain_imports()

    def by_edge(payload: dict) -> dict[tuple[str, str], int]:
        return {(edge["from"], edge["to"]): edge["implementation_statements"]
                for edge in payload["edges"] if edge["implementation_statements"]}

    base_edges = by_edge(baseline)
    live_edges = by_edge(live)

    # The committed file must agree with itself. A `counts` block that disagrees
    # with its own `edges` would silently invalidate every threshold below.
    recomputed = sum(base_edges.values())
    declared = (baseline.get("counts") or {}).get("implementation_statements")
    if declared != recomputed:
        report(gate, False,
               f"docs/cross-domain-imports.yaml is self-inconsistent: counts says "
               f"{declared}, edges sum to {recomputed} -- re-run "
               f"scripts/migration/scan.py")
        return

    added = sorted(edge for edge in live_edges if edge not in base_edges)
    grown = sorted((edge, base_edges[edge], live_edges[edge])
                   for edge in live_edges
                   if edge in base_edges and live_edges[edge] > base_edges[edge])
    if added or grown:
        problems = []
        if added:
            problems.append("new cross-domain implementation edge(s): "
                            + ", ".join(f"{s}->{t}" for s, t in added))
        if grown:
            problems.append("edge(s) that grew: "
                            + ", ".join(f"{s}->{t} {before}->{after}"
                                        for (s, t), before, after in grown))
        report(gate, False, "; ".join(problems))
        return

    at_zero = sorted(edge for edge in base_edges if not live_edges.get(edge))
    # Report the LIVE backlog, not the baseline's. Both numbers in one sentence have
    # to describe the same state, and the state a reader cares about is "what is left
    # today". Mixing a baseline total with a live cross-unit count produces a sentence
    # that is true of no moment in time -- and it hides the shrink, which is the one
    # thing this gate's message is supposed to show.
    live_total = sum(live_edges.values())
    detail = (f"{live_total} implementation statements across {len(live_edges)} edges "
              f"({live['counts']['cross_deployment_unit_statements']} cross a "
              f"deployment unit)")
    if live_total != recomputed:
        detail += f"; baseline was {recomputed}"
    if at_zero:
        detail += (f"; {len(at_zero)} edge(s) now at zero, delete them from the "
                   f"baseline: {at_zero}")
    report(gate, True, detail)


# V9 section 3: the four deployment units the Wave 2 split has to untangle.
WAVE2_UNITS = (
    "dinqor-agent",
    "dinqor-content-automation",
    "dinqor-core-host",
    "dinqor-platform-integration",
)

# The unit pairs that depend on each other in *implementation* terms, measured 2026-09-16
# and re-measured 2026-09-17 after the agent-api, automation-api and recitation-api cuts.
# Stored as ordered pairs so the baseline records direction, not just adjacency.
#
# Shape of the graph, and why it matters for Wave 2:
#
#   * agent <-> platform-integration WAS one of them, and the agent-api cut ended it:
#     integration used to implement agent's AgentTool / AgentDynamicToolSource and call
#     AgentToolRegistry, which was 5 implementation statements one way. They now go through
#     the agent.spi contract, so integration -> agent is zero and the pair is no longer
#     mutual. Both directions are deleted from this baseline so the remaining tangle stays
#     visible -- a stale entry makes the graph look worse than it is.
#   * The automation-api cut then removed the last implementation edge pointing *into*
#     agent: the six agent -> automation statements (EmailReminderService /
#     BlogContentPlanService and their VOs) now go through the automation.spi contract.
#     agent is left with outgoing edges only -- 4 to content-automation, 6 to core-host,
#     2 to platform-integration -- and nothing points back at it.
#   * The recitation-api cut (materials capability only) then removed
#     content-automation's *only* remaining implementation edge to core-host: automation's
#     BlogContentPlanService and BlogPlanRunService called recitation's RecitationService,
#     2 statements, now going through recitation.spi. So content-automation <-> core-host is
#     no longer mutual either, and both directions were deleted for the same reason as the
#     agent pair. At that point core-host still reached content-automation twice
#     (studio -> blogagent and admin -> automation), which is what kept the three-unit
#     component alive.
#   * Consequence of the agent cut: agent left the Wave 2 strongly-connected component. The
#     old chord cycle closed through the platform-integration -> agent leg, so removing that
#     leg is exactly what broke it. agent became a singleton component, the same
#     precondition bloghtml has.
#   * The integration-api sync cut (CSDN/Juejin, 2026-09-18) removed agent's last outgoing
#     statements *and* the last platform-integration -> core-host statement
#     (`McpOAuthService` importing `identity.application.AuthService`), so core-host left
#     the component as well.
#   * The studio -> blogagent cut (2026-09-18) then removed one of core-host's two
#     remaining ways of reaching content-automation: `Img2ThreeModelClient` no longer reads
#     blogagent's `BlogAgentProperties`, the fallback moved into `application.yml`.
#   * ⭐ The MailPort cut (2026-09-18) removed the last leg of the last cycle:
#     `integration.mail.MailService` imported `automation.domain.EmailReminderMailTemplates`
#     to render reminder bodies, and that was the only platform-integration ->
#     content-automation implementation statement. Rendering moved back to `automation`
#     (`EmailReminderMailRenderer`) and the port only carries finished bodies. What is left
#     is a pure chain -- core-host -> content-automation -> platform-integration, 1 and 4
#     statements -- so **every Wave 2 unit is now a singleton component** and the graph is
#     acyclic. A cycle is what denies an extraction order; the chain gives one.
#
# ⚠️ MUTUAL_UNIT_PAIRS is now EMPTY and must stay empty. The criterion below is
# bidirectional, so a pair reappearing is reported as `added` and fails the gate -- an
# empty baseline is the strictest form of this check, not a disabled one.
#
# ⚠️ With every component of size 1, `outside` ("Wave 2 units minus the largest component")
# is no longer a meaningful summary: it names three units as "left the component" when all
# four are extractable. The detail string special-cases that below rather than printing a
# number that reads as "one unit is still tangled".
#
# This is NOT itself a defect. After the split these become ordinary service-to-service
# calls, and A calling B while B calls A is normal. What it does mean is that the remaining
# three have no "extract the leaf first" order available: each needs both its outgoing and
# its incoming cross-unit implementation imports converted, and a contract module is
# precisely what converts one (it lets the caller depend on the port without depending on
# the implementation). That is why the contract line, not the lib line, is the one that
# moves this graph.
#
# ⚠️ WAVE2_UNITS[0] is dinqor-agent, and agent is now a singleton -- so criterion 2 below
# MUST be evaluated over every Wave 2 unit. Rooting it at WAVE2_UNITS[0] would silently
# stop checking the graph: the component of a unit nobody depends on can never gain a
# member, so `joined` would always be empty and a new unit joining the *real* component
# would go unreported.
#
# ⚠️ dinqor-core-host also left the component on 2026-09-18, and it left because of a
# *single* implementation statement: platform-integration -> core-host was nothing but
# `McpOAuthService` importing `identity.application.AuthService` (the `integration ->
# identity` edge, 1 impl). The graph had been read as "core-host, content-automation and
# platform-integration are one tangle", but core-host's membership rested entirely on that
# one line -- platform-integration -> content-automation + content-automation ->
# platform-integration are what actually form the cycle. Lesson: a strongly-connected
# component's membership can hinge on an edge of size 1, so re-derive it after every cut
# instead of assuming the previous shape still holds.
# Pairs are ordered (the baseline keeps direction) and the criterion is bidirectional, so
# removing a pair means removing BOTH directions.
#
# EMPTY since the MailPort cut (2026-09-18) broke the last cycle. Both directions of the
# content-automation <-> platform-integration pair were deleted together, as the criterion
# requires. Do not repopulate this to "make the gate pass" -- an empty baseline means any
# new mutual pair is reported, which is the point.
MUTUAL_UNIT_PAIRS: frozenset[tuple[str, str]] = frozenset()

# The SIZE baseline for the cross-unit implementation graph, added 2026-09-20 once the
# count reached zero (the NotificationBroadcastPort cut).
#
# ⚠️ This constant could not be written before that cut: while the number was still moving,
# hard-coding it would have meant editing it in every cut. Zero is the first value that is
# stable, so zero is the first value that can be guarded. Before it, the gap was *masked*
# anyway -- the count was visibly non-zero to anyone reading cross-domain-imports.yaml.
#
# ⚠️ Why it lives here and not in `cross-domain-import-baseline`: that gate reads
# `docs/cross-domain-imports.yaml`, which is `scan.py`'s own product, so its baseline
# equals its live value under the documented "scan, then gates" order and it can never
# fail (ADR TODO 16). A literal constant is a baseline from OUTSIDE the criterion -- the
# same shape as MUTUAL_UNIT_PAIRS above. The two are a family: this one guards the *size*
# of the graph, that one guards the presence of cycles in it.
#
# ⚠️ Why criteria 1-3 cannot cover it: all three judge the *shape* of the graph. A new
# ONE-WAY cross-unit edge (A -> B, no edge back) joins no strongly-connected component and
# creates no mutual pair, so it passes all three.
#
# Measured on 2026-09-20, on a real injection (revert `AdminNotificationController` to the
# concrete `BlogPlanSseService`, i.e. re-create `dinqor-core-host -> dinqor-content-automation`,
# 1 statement) under the documented "scan, then gates" order:
#
#   constant = 1 (equivalent to this criterion not existing) -> 24 pass, 0 fail, 2 skip
#       ...and the PASS detail line still read "1 cross-unit edge(s) / 1 statement(s) left".
#       The gate was green while its own summary named the regression.
#   constant = 0 (this criterion)                            -> 23 pass, 1 fail, 2 skip
#       ...the single failure being this criterion's message, which is itself proof that
#       criteria 1-3 returned early and therefore passed.
#
# So this closes a hole that was real and measurable, not a hypothetical one.
#
# Bidirectional, like MUTUAL_UNIT_PAIRS: a count that DROPS below the constant is also a
# change to the recorded baseline and must be acknowledged by editing the constant, not
# silently absorbed. (At 0 the "below" direction is unreachable, but the check is written
# that way so the constant stays honest if it is ever raised.)
#
# ⚠️ Statements need no separate constant: `edges` is built by summing
# `implementation_statements` per unit pair and skipping pairs with none, so
# `len(edges) == 0` implies `sum(edges.values()) == 0`.
EXPECTED_CROSS_UNIT_IMPLEMENTATION_EDGES = 0


def gate_deployment_unit_coupling() -> None:
    """The deployment-unit graph must not get *more* tangled than it is.

    Four criteria. The first three are about the **shape** of the graph; the fourth is
    about its **size**, which became a stable baseline on 2026-09-20 (see
    ``EXPECTED_CROSS_UNIT_IMPLEMENTATION_EDGES``).

    1. **``bloghtml`` stays isolated.** V9 section 3 extracts it alone in Wave 1, ahead of
       every other domain. That is only possible because it currently has no cross-unit
       implementation edge in either direction -- a precondition, not a coincidence. A
       single new edge pointing at or from it would make the Wave 1 extraction depend on a
       conversion nobody has scheduled.
    2. **No new unit joins the strongly-connected component.** The Wave 2 units that reach
       each other in implementation terms -- directly or through a chord cycle -- cannot be
       extracted on their own. A unit that joins that component inherits the same problem,
       and the set is much easier to keep from growing than to untangle later. Evaluated
       over every Wave 2 unit rather than a single root: a unit that has already left the
       component collapses to a singleton, and a single-rooted check would silently stop
       looking at the rest of the graph.
    3. **No new mutually-dependent pair.** Weaker than criterion 2 and reported separately
       because it is the more actionable signal: a mutual pair is a concrete pair of
       statements to convert, whereas the component is a summary.
    4. **The cross-unit implementation edge count matches its constant.** This is the only
       one of the four that a **one-way** edge trips: it joins no component and creates no
       mutual pair, so criteria 1-3 pass it. Checked last so the more specific messages win
       when an edge trips both.

    The reverse direction is reported too: pairs that stopped being mutual, or a component
    that shrank, are progress and should be removed from the baselines so the remaining
    tangle stays visible.
    """
    gate = "deployment-unit-coupling"
    live = scan.build_cross_domain_imports()

    edges: dict[tuple[str, str], int] = {}
    for edge in live["edges"]:
        if not edge["implementation_statements"] or not edge["crosses_deployment_unit"]:
            continue
        key = (edge["from_unit"], edge["to_unit"])
        edges[key] = edges.get(key, 0) + edge["implementation_statements"]

    bloghtml_unit = scan.DEPLOYMENT_UNIT["bloghtml"]
    touching_bloghtml = sorted(f"{a} -> {b}" for a, b in edges if bloghtml_unit in (a, b))
    if touching_bloghtml:
        report(gate, False,
               f"{bloghtml_unit} has cross-unit implementation dependency/ies "
               f"{touching_bloghtml}, but V9 section 3 extracts it alone in Wave 1 -- "
               f"convert or remove them first, or move the domain out of Wave 1")
        return

    # Reachability closure over <= 10 units, so a plain transitive closure beats
    # pulling in a graph library. A unit reaches itself by definition.
    units = sorted({unit for pair in edges for unit in pair} | set(WAVE2_UNITS))
    reach = {unit: {unit} for unit in units}
    for source, target in edges:
        reach[source].add(target)
    for via in units:
        for unit in units:
            if via in reach[unit]:
                reach[unit] |= reach[via]

    def component_of(unit: str) -> set[str]:
        return {other for other in units
                if other in reach[unit] and unit in reach[other]}

    # Criterion 2 is evaluated over EVERY Wave 2 unit, not just WAVE2_UNITS[0].
    #
    # Rooting it at one unit is only sound while that unit is actually in the component.
    # A unit that leaves collapses to a singleton, and the component of a unit nobody
    # depends on can never gain a member -- so `joined` would be permanently empty and a
    # new unit joining the *real* component would go unreported. That is not hypothetical:
    # dinqor-agent is WAVE2_UNITS[0] and left the component on 2026-09-17 (the
    # automation-api cut removed the last edge into it), which made the single-root version
    # vacuous. The union over all four roots is the same check before that happened and
    # stays correct after.
    components = {unit: component_of(unit) for unit in WAVE2_UNITS}
    joined: set[str] = set()
    for component in components.values():
        joined |= component - set(WAVE2_UNITS)
    if joined:
        # Name the component that actually gained a member, not components[WAVE2_UNITS[0]]
        # -- the root may be a singleton by now, and pointing at it would send the reader
        # to a unit that has nothing to do with the new edge.
        for unit in WAVE2_UNITS:
            if components[unit] & joined:
                report(gate, False,
                       f"{sorted(joined)} joined the Wave 2 strongly-connected component "
                       f"of {unit} ({sorted(components[unit])}): those units now reach each "
                       f"other in implementation terms, so none of them can be extracted "
                       f"alone. Convert the new direction through a contract first")
                return

    # MUTUAL_UNIT_PAIRS records ordered pairs (so the baseline keeps direction), but a
    # mutual dependency is a property of the *pair*: reporting (a, b) and (b, a) as two
    # findings would double every line and read as two problems instead of one. Compare
    # and report on the canonicalised pair, keeping the ordered baseline for the record.
    def canonical(pair: tuple[str, str]) -> tuple[str, str]:
        return tuple(sorted(pair))

    mutual = {canonical((a, b)) for a, b in edges if (b, a) in edges}
    baseline_pairs = {canonical(pair) for pair in MUTUAL_UNIT_PAIRS}
    added = sorted(mutual - baseline_pairs)
    if added:
        report(gate, False,
               f"new mutually-dependent unit pair(s) {added}: the units now reach each "
               f"other in implementation terms, so neither can be extracted without the "
               f"other. Convert one direction through a contract first")
        return

    # Criterion 4: the SIZE of the graph, against a constant baseline. Deliberately placed
    # LAST, after the three shape criteria, so that an edge which also trips one of them
    # keeps its more specific message (bloghtml's Wave 1 precondition, a new component
    # member, a new mutual pair). This criterion fires for the residual case the other
    # three cannot see: a one-way edge that joins nothing and pairs with nothing.
    measured_edges = len(edges)
    if measured_edges != EXPECTED_CROSS_UNIT_IMPLEMENTATION_EDGES:
        detail = (f"{measured_edges} cross-unit implementation edge(s) / "
                  f"{sum(edges.values())} statement(s), but the baseline is "
                  f"{EXPECTED_CROSS_UNIT_IMPLEMENTATION_EDGES} edge(s)")
        if measured_edges > EXPECTED_CROSS_UNIT_IMPLEMENTATION_EDGES:
            detail += (f". Present: {sorted(f'{a} -> {b}' for a, b in edges)} -- these units "
                       f"now reach each other's *implementations* rather than a contract, so "
                       f"they cannot be deployed separately. Convert the new edge through a "
                       f"port in the consumer's own contract module first")
        else:
            detail += (f". That is progress, not a failure: lower "
                       f"EXPECTED_CROSS_UNIT_IMPLEMENTATION_EDGES to {measured_edges} so the "
                       f"remaining work stays visible")
        report(gate, False, detail)
        return

    cleared = sorted(baseline_pairs - mutual)

    # Report the MEASURED component, not len(WAVE2_UNITS): agent left on 2026-09-17, so
    # printing the constant would overstate the component by one and hide real progress.
    wave2_component = max(components.values(), key=len)
    # ⚠️ And report the MEASURED edge count. Once the graph empties, the word "acyclic"
    # starts hiding the result: "every unit is a singleton and the graph is acyclic" reads
    # the same for an empty graph and for a graph with a few one-way edges, and those two
    # mean very different things for the migration (one is finished, the other still has
    # work). Neither criterion above can tell them apart either -- a new *one-way* edge
    # joins no component and creates no mutual pair, so it passes this gate silently.
    # This detail line is therefore the only place that says the count reached zero.
    cross_unit_edges = len(edges)
    cross_unit_statements = sum(edges.values())
    # ⚠️ Once every component is a singleton, `outside` stops meaning "the units that left
    # the component": `max` breaks ties on iteration order, so it would name
    # len(WAVE2_UNITS) - 1 arbitrary units and the line would read as "one unit is still
    # tangled" when in fact none is. The acyclic state gets its own sentence.
    if len(wave2_component) == 1:
        detail = (f"0 of {len(WAVE2_UNITS)} Wave 2 unit(s) share a component "
                  f"({len(mutual)} mutually-dependent pair(s)); every unit is a singleton "
                  f"and the cross-unit implementation graph is acyclic "
                  f"({cross_unit_edges} cross-unit edge(s) / {cross_unit_statements} "
                  f"statement(s) left), so no unit can block another's extraction; "
                  f"{bloghtml_unit} still isolated")
    else:
        outside = sorted(set(WAVE2_UNITS) - wave2_component)
        detail = (f"{len(wave2_component)} of {len(WAVE2_UNITS)} Wave 2 unit(s) in one component "
                  f"({len(mutual)} mutually-dependent pair(s)); "
                  f"{cross_unit_edges} cross-unit edge(s) / {cross_unit_statements} "
                  f"statement(s) left; {bloghtml_unit} still isolated")
        if outside:
            detail += (f"; {outside} left the component (no unit points back at them) and can "
                       f"now be extracted alone")
    if cleared:
        detail += (f"; {len(cleared)} pair(s) no longer mutual, delete both directions "
                   f"from MUTUAL_UNIT_PAIRS: {cleared}")
    report(gate, True, detail)


def gate_contract_changes_completeness() -> None:
    gate = "contract-changes-completeness"
    baseline = load("capability-baseline.yaml")
    changes = {entry["id"] for entry in load("contract-changes.yaml")["changes"]}
    problems: list[str] = []
    for entry in baseline["capabilities"]:
        status = entry.get("status")
        ref = entry.get("contract_change_ref")
        if status == "changed":
            if not ref:
                problems.append(f"{entry['id']}: status=changed without contract_change_ref")
            elif ref not in changes:
                problems.append(f"{entry['id']}: contract_change_ref={ref} not found")
        if status == "retired" and not entry.get("approval"):
            problems.append(f"{entry['id']}: retired without approval record")
    for entry in baseline.get("changed") or []:
        if entry.get("contract_change_ref") not in changes:
            problems.append(f"changed registry -> {entry.get('contract_change_ref')} not found")
    if problems:
        report(gate, False, "; ".join(problems[:8]))
        return
    report(gate, True, f"{len(changes)} contract changes, all capability refs resolve")


def gate_capability_baseline_diff() -> None:
    """Capability list is append-only (V9 section 12).

    A committed lock file records the ids seen so far. Removing an id without
    marking it `retired` (plus an approval) fails the gate — this is the
    mechanism that stops the "补了又丢" cycle.
    """
    gate = "capability-baseline-diff"
    baseline = load("capability-baseline.yaml")
    ids = [entry["id"] for entry in baseline["capabilities"]]
    duplicates = sorted({i for i in ids if ids.count(i) > 1})
    if duplicates:
        report(gate, False, f"duplicate capability ids: {duplicates}")
        return

    current = set(ids)
    if not CAPABILITY_LOCK.exists():
        CAPABILITY_LOCK.write_text("\n".join(sorted(current)) + "\n",
                                   encoding="utf-8", newline="\n")
        report(gate, True, f"baseline lock created with {len(current)} capabilities")
        return

    locked = {line.strip() for line in CAPABILITY_LOCK.read_text(encoding="utf-8").splitlines()
              if line.strip()}
    removed = sorted(locked - current)
    added = sorted(current - locked)
    retired = {entry["id"] for entry in baseline["capabilities"]
               if entry.get("status") == "retired" and entry.get("approval")}
    unapproved = sorted(set(removed) - retired)
    if unapproved:
        report(gate, False,
               f"capabilities removed without status=retired + approval: {unapproved}")
        return
    if added:
        CAPABILITY_LOCK.write_text("\n".join(sorted(current)) + "\n",
                                   encoding="utf-8", newline="\n")
    report(gate, True, f"{len(current)} capabilities ({len(added)} added, {len(removed)} retired)")


def gate_legacy_redis_reference_check() -> None:
    """No NEW production code may depend on the legacy Redis key families.

    Wave 0A cannot remove them yet (they are the current session model), so the
    gate pins the set of files allowed to reference them: adding a fourth call
    site fails. Retirement (V9 §57 T3) must shrink this set down to Constants.java
    and then delete the constants -- see docs/migration/legacy-redis-retirement.md §9.

    Two details that matter after the Wave 0B library extraction:

    * the scan covers ``libs/*/src/main/java`` as well as ``src/main/java``
      (``scan.java_files()``). Constants.java now lives in libs/xander-model, and a
      scan of ``src/`` alone would have reported "0 offenders" -- the file that
      *declares* the legacy keys would simply have stopped existing as far as this
      gate is concerned.
    * every allow-listed path must actually exist. Otherwise a future move leaves a
      stale entry that can never match, and the gate passes while claiming to pin
      three files. (A move is caught anyway, because the new path shows up as an
      offender -- but only if the file still references the constants.)
    """
    gate = "legacy-redis-reference-check"
    allowed = {
        "libs/xander-model/src/main/java/com/xander/lab/common/Constants.java",
        "apps/core-host/src/main/java/com/xander/lab/identity/application/AuthService.java",
        "apps/core-host/src/main/java/com/xander/lab/identity/application/UserAdminService.java",
    }
    missing = sorted(rel for rel in allowed if not (REPO / rel).exists())
    if missing:
        report(gate, False,
               f"allow-list points at file(s) that no longer exist: {missing} -- "
               f"update the path, do not delete the entry")
        return
    offenders: list[str] = []
    for path in scan.java_files():
        text = path.read_text(encoding="utf-8", errors="replace")
        if "REDIS_TOKEN_PREFIX" in text or "REDIS_USER_TOKENS_PREFIX" in text:
            rel = path.relative_to(REPO).as_posix()
            if rel not in allowed:
                offenders.append(rel)
    if offenders:
        report(gate, False, f"new references to legacy token keys: {offenders}")
        return
    report(gate, True, f"legacy key references confined to {len(allowed)} known files")


def gate_gateway_forbidden_dependency() -> None:
    gate = "gateway-forbidden-dependency-check"
    gateway_pom = REPO / "apps" / "gateway" / "pom.xml"
    if not gateway_pom.exists():
        skip(gate, "apps/gateway does not exist yet (Wave 1 deliverable)")
        return
    text = gateway_pom.read_text(encoding="utf-8")
    forbidden = [token for token in ("spring-boot-starter-web", "xander-webmvc-support",
                                     "xander-mybatis-support")
                 if token in text]
    if forbidden:
        report(gate, False, f"gateway depends on {forbidden}")
        return
    report(gate, True, "gateway dependency graph is WebFlux-clean")


def gate_gateway_stream_proxy_config() -> None:
    gate = "gateway-stream-proxy-config-check"
    config = REPO / "infrastructure" / "gateway"
    if not config.exists():
        skip(gate, "infrastructure/gateway does not exist yet (Wave 1 deliverable)")
        return
    text = "".join(p.read_text(encoding="utf-8") for p in sorted(config.rglob("*.y*ml")))
    required = ["X-Accel-Buffering", "Last-Event-ID"]
    missing = [token for token in required if token not in text]
    if missing:
        report(gate, False, f"gateway stream proxy config missing: {missing}")
        return
    report(gate, True, "stream proxy settings present")


def _archived_script_problems() -> tuple[list[str], int]:
    """V9 §64：那 80 个历史脚本是**冻结的历史**，改了必须有人发现。

    ``migrations/legacy/SHA256SUMS`` 记录 ``apps/core-host/src/main/resources/db/``
    下每个归档脚本的 sha256。这些脚本**不会被重放**（Flyway 从线上 schema baseline），
    所以改动它们不会让任何构建或测试失败 —— 只会让"生产库当初到底执行了什么"
    与仓库里的记录**静默分叉**。审计脚本是手工运行的，不会自己再跑一遍。

    ⚠️ **哈希算法必须与生成方 ``audit_legacy_sql.py`` 逐字节一致**，即
    ``path.read_text(encoding="utf-8", errors="replace").encode("utf-8")``，
    **不是** ``read_bytes()``。前者走 Python 的通用换行（把 ``\\r\\n`` 归一成 ``\\n``），
    后者是磁盘上的原始字节。本仓库 ``core.autocrlf=true``，checkout 之后
    80 个文件里有 30 个带 CRLF —— 用 ``read_bytes()`` 会报 **30 个假失败**。
    这条是实测过的（见 ``docs/migration/lib-extraction.md``）。

    判据：清单里的每个文件都要在；磁盘上的每个 ``*.sql`` 都要在清单里；
    逐个比对哈希。三件事都做，否则"新增一个归档脚本"会无人察觉。
    """
    if not LEGACY_SUMS.exists():
        return [f"{LEGACY_SUMS.relative_to(REPO)} is missing"], 0

    recorded: dict[str, str] = {}
    for lineno, line in enumerate(LEGACY_SUMS.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        parts = line.split("  ", 1)
        if len(parts) != 2:
            return [f"SHA256SUMS:{lineno}: expected '<sha256>  <file>'"], 0
        recorded[parts[1].strip()] = parts[0].strip()

    db_dir = Path(scan.DB_DIR)
    on_disk = sorted(path.name for path in db_dir.glob("*.sql"))
    problems: list[str] = []

    for name in sorted(set(recorded) - set(on_disk)):
        problems.append(f"{name}: in SHA256SUMS but absent from {db_dir.name}/")
    for name in sorted(set(on_disk) - set(recorded)):
        problems.append(f"{name}: in {db_dir.name}/ but absent from SHA256SUMS")
    for name in sorted(set(on_disk) & set(recorded)):
        actual = hashlib.sha256(
            (db_dir / name).read_text(encoding="utf-8", errors="replace").encode("utf-8")
        ).hexdigest()
        if actual != recorded[name]:
            problems.append(
                f"{name}: content changed (recorded {recorded[name][:12]}, now {actual[:12]})"
            )
    return problems, len(recorded)


def gate_flyway_validation() -> None:
    """V9 §62 / §64：Flyway 接线与"冻结历史"两道判据。

    判据一：``migrations/versioned/V*.sql`` 命名合法且版本号唯一
    （Flyway 遇到重复版本会在启动时失败，而那时已经在部署窗口里了）。

    判据二：归档脚本清单仍然为真（见 ``_archived_script_problems``）。
    """
    gate = "flyway-validation"
    migrations = REPO / "migrations"
    if not migrations.exists():
        skip(gate, "migrations/ does not exist yet (Wave 0A deliverable in progress)")
        return
    versioned = sorted((migrations / "versioned").glob("V*.sql")) \
        if (migrations / "versioned").exists() else []
    versions: list[str] = []
    problems: list[str] = []
    for path in versioned:
        match = re.match(r"V(\d+)__", path.name)
        if not match:
            problems.append(f"{path.name}: does not match V<version>__<description>.sql")
            continue
        versions.append(match.group(1))
    duplicates = sorted({v for v in versions if versions.count(v) > 1})
    if duplicates:
        problems.append(f"duplicate versions: {duplicates}")

    archived_problems, archived_count = _archived_script_problems()
    problems.extend(archived_problems)

    if problems:
        report(gate, False, "; ".join(problems))
        return
    report(gate, True,
           f"{len(versioned)} versioned scripts (versions unique); "
           f"{archived_count} archived scripts match migrations/legacy/SHA256SUMS")


def gate_stream_ban_boundary_coverage() -> None:
    """V9 §12 / §50 / §51：每条长连接流都必须有封禁边界实现。

    这是 §12「补了又丢」的机器化防线。机器能回答"哪些路由是流"
    （route-inventory.yaml 的 stream 字段），但"边界实现在哪个文件里"是语义信息，
    登记在 stream-hosts.yaml。本门禁同时校验三件事：

    1. 每条 ``stream != none`` 的路由都被 stream-hosts.yaml 覆盖（或显式列进
       ``unregistered_routes`` 并给出理由）；
    2. 登记的 boundary_files 真实存在；
    3. 每个 boundary_files 确实引用了 ``ConnectionRegistry``。

    第 3 条是关键：它防止"文件在、但里面没真的登记连接"这种空壳登记。
    """
    gate = "stream-ban-boundary-coverage"
    inventory = load("route-inventory.yaml")
    hosts_file = DOCS / "stream-hosts.yaml"
    if not hosts_file.exists():
        report(gate, False, "docs/stream-hosts.yaml missing — stream hosts are unregistered")
        return
    with open(hosts_file, encoding="utf-8") as handle:
        registry = yaml.safe_load(handle)

    stream_routes = {
        route["id"]: route.get("stream")
        for route in inventory.get("routes", [])
        if route.get("stream") not in (None, "none")
    }
    unregistered = set(registry.get("unregistered_routes") or [])

    covered: set[str] = set()
    problems: list[str] = []
    for host in registry.get("hosts") or []:
        host_id = host.get("id", "<unnamed>")
        routes = host.get("routes") or []
        if not routes:
            problems.append(f"{host_id}: no routes declared")
        for route_id in routes:
            if route_id not in stream_routes:
                problems.append(f"{host_id}: route '{route_id}' is not a stream route in route-inventory")
            covered.add(route_id)

        files = host.get("boundary_files") or []
        if not files:
            problems.append(f"{host_id}: no boundary_files declared")
        for rel in files:
            path = REPO / rel
            if not path.exists():
                problems.append(f"{host_id}: boundary file missing: {rel}")
                continue
            text = path.read_text(encoding="utf-8")
            if "ConnectionRegistry" not in text:
                problems.append(
                    f"{host_id}: {rel} does not reference ConnectionRegistry "
                    "(connection is never registered)")

    missing = sorted(set(stream_routes) - covered - unregistered)
    if missing:
        problems.append(
            f"{len(missing)} stream route(s) have no registered ban boundary: {missing}")

    stale = sorted(covered - set(stream_routes))
    if stale:
        problems.append(f"stream-hosts.yaml references unknown routes: {stale}")

    if problems:
        report(gate, False, "; ".join(problems))
        return
    report(gate, True,
           f"{len(stream_routes)} stream routes covered by {len(registry.get('hosts') or [])} host(s)"
           + (f", {len(unregistered)} explicitly unregistered" if unregistered else ""))


XML_COMMENT_RE = re.compile(r"<!--(.*?)-->", re.S)


def gate_xml_authoring_safety() -> None:
    """Every ``*.xml`` in the repo must be well-formed, checked before Maven sees it.

    Two criteria, because the repo has now been broken by both:

    1. **No ``--`` inside a comment.** This is not a style rule: it makes the
       document *non-parseable*, and Maven stops with

           Non-parseable POM pom.xml: in comment after two dashes (--) next
           character must be > not ...

       and the whole reactor fails before a single class is compiled. Easy to
       write by accident, because an em-dash or a range in a prose comment looks
       harmless and the file is only validated at build time. Hit six times now
       (twice in ``pom.xml``, twice in ``libs/xander-model/pom.xml``, once more
       in ``pom.xml`` when the MyBatis-Plus property was added, once in
       ``apps/core-host/pom.xml`` when the ``content-api`` dependency was added).
       The fix is always the same: reword the comment, use a comma, a colon, or
       "to" instead of the dash pair.

    2. **The file parses.** This is the catch-all, and it exists because a
       *different* authoring hazard has the same blast radius: a POM
       ``<description>`` is prose, so writing ``List<BlogPublisher>`` or a bare
       ``&`` in it produces

           Non-parseable POM ...: TEXT must be immediately followed by END_TAG
           and not START_TAG

       That one was hit while writing ``contracts/content-api/pom.xml``, whose
       whole job is to document itself in prose. Criterion 1 does not catch it,
       and the failure mode is identical: reactor down, zero classes compiled.

    Criterion 1 is kept alongside criterion 2 even though a parse failure would
    also be reported by 2, because the ``--`` message points straight at the
    offending comment while a raw parse error just says "not well-formed".

    Scope is every ``*.xml`` outside ``target/``, not just POMs: mapper XML and
    any future module descriptor have the same rule.
    """
    gate = "xml-authoring-safety"
    problems: list[str] = []
    scanned = 0
    for path in sorted(REPO.rglob("*.xml")):
        rel = path.relative_to(REPO).as_posix()
        if "/target/" in rel or rel.startswith("target/"):
            continue
        scanned += 1
        text = path.read_text(encoding="utf-8", errors="replace")
        for match in XML_COMMENT_RE.finditer(text):
            if "--" not in match.group(1):
                continue
            line = text[:match.start()].count("\n") + 1
            snippet = " ".join(match.group(1).split())[:60]
            problems.append(f"{rel}:{line} comment contains '--' ({snippet!r})")
        try:
            xml.etree.ElementTree.fromstring(text)
        except xml.etree.ElementTree.ParseError as exc:
            problems.append(f"{rel} is not well-formed: {exc}")

    if problems:
        report(gate, False,
               f"{len(problems)} XML problem(s), each of which fails the Maven reactor "
               f"before any class is compiled: " + "; ".join(problems[:5])
               + (" ..." if len(problems) > 5 else ""))
        return
    report(gate, True,
           f"{scanned} XML file(s) well-formed, no '--' inside a comment")


def gate_dockerfile_module_coverage() -> None:
    """Every module in the root POM must be COPYed into the Docker build context.

    The Dockerfile builds the reactor, so a module that exists on disk but is not copied
    in fails the image build with "Child module ... does not exist" -- and nothing else in
    this repo notices, because ``mvn test`` runs against the working tree and the Docker
    build is not part of either the Maven or the gate run.

    That makes it the same shape of bug as the ``.dockerignore`` secrets path: a change that
    is correct locally and broken only in the artifact that ships. Adding a module means
    editing the Dockerfile twice (its ``pom.xml`` for the cached dependency layer, its
    sources for the build), and both halves are checked here.
    """
    gate = "dockerfile-module-coverage"
    root_pom = REPO / "pom.xml"
    dockerfile = REPO / "Dockerfile"
    if not dockerfile.is_file():
        report(gate, True, "no Dockerfile")
        return

    text = root_pom.read_text(encoding="utf-8")
    block = re.search(r"<modules>(.*?)</modules>", text, re.S)
    if not block:
        report(gate, True, "root POM declares no <modules>")
        return
    modules = re.findall(r"<module>\s*([^<\s]+)\s*</module>", block.group(1))
    docker = dockerfile.read_text(encoding="utf-8")

    problems: list[str] = []
    for module in modules:
        if f"{module}/pom.xml" not in docker:
            problems.append(f"{module}: its pom.xml is not COPYed (the cached dependency "
                            f"layer cannot resolve it)")
        top = module.split("/", 1)[0]
        if not re.search(rf"^COPY\s+{re.escape(top)}\s+\./{re.escape(top)}\s*$",
                         docker, re.M):
            problems.append(f"{module}: sources are not COPYed (`COPY {top} ./{top}` "
                            f"missing), so the reactor cannot build it")

    if problems:
        report(gate, False,
               f"{len(problems)} module(s) missing from the Docker build: "
               + "; ".join(problems[:5]) + (" ..." if len(problems) > 5 else ""))
        return
    report(gate, True,
           f"{len(modules)} module(s) all COPYed into the Docker build context")


# V9 section 80 bans the global ``classpath*:/mapper/*.xml`` swallow once services
# are split. It is still *correct* today: there is exactly one application module,
# so there is nothing else on the classpath for it to swallow. And changing it now
# would be an unverifiable config change -- the five ``@SpringBootTest`` classes
# are all sliced (explicit ``classes = {...}``) and never boot MyBatis-Plus, so a
# broken ``mapper-locations`` would be caught by production, not by CI.
#
# Wave 0B therefore freezes the current state instead of editing it: this gate
# fails if a NEW config file introduces the pattern, and reports the surviving one
# so it cannot be forgotten. Wave 1 moves MyBatis config into Nacos Service Private
# DataId, which is the natural moment to narrow it.
KNOWN_MYBATIS_GLOBAL_SCAN = {
    "apps/core-host/src/main/resources/application.yml",
}

MAPPED_LOCATIONS_RE = re.compile(r"^\s*mapper-locations:\s*(\S+)", re.M)
CLASSPATH_PREFIX_RE = re.compile(r"^classpath\*?:")


def ant_to_regex(pattern: str) -> str:
    """Translate an Ant-style resource pattern to an anchored regex."""
    out = []
    index = 0
    while index < len(pattern):
        char = pattern[index]
        if char == "*":
            if pattern[index:index + 2] == "**":
                out.append(".*")
                index += 2
                continue
            out.append("[^/]*")
        elif char == "?":
            out.append("[^/]")
        else:
            out.append(re.escape(char))
        index += 1
    return "^" + "".join(out) + "$"


def mapper_xml_files() -> list[Path]:
    """Every mapper XML in the tree, keyed by its classpath path."""
    found = []
    for path in sorted(REPO.rglob("*.xml")):
        rel = path.relative_to(REPO).as_posix()
        if "/target/" in rel or rel.startswith("target/"):
            continue
        if "/resources/mapper/" in rel:
            found.append(path)
    return found


def gate_mybatis_scan_scope() -> None:
    """V9 section 80: per-service mapper ownership, and no new global swallow.

    Three criteria, in the order the failure would hurt:

    1. A mapper XML must live under ``apps/<module>/src/main/resources/mapper/``.
       A mapper XML inside ``libs/`` is the global swallow expressed as a file: a
       shared jar is on every service's classpath, so every service would load it
       and silently own another domain's SQL.
    2. Every mapper XML must actually be reachable from its module's
       ``mapper-locations``. A mapper XML that nothing loads is a dead file -- the
       queries in it would fail at runtime with "Invalid bound statement", which
       reads like a code bug rather than a config bug.
    3. The ``classpath*:/mapper/`` global swallow must not spread to a new config
       file (see ``KNOWN_MYBATIS_GLOBAL_SCAN``).
    """
    gate = "mybatis-scan-scope"
    problems: list[str] = []

    # --- criterion 1: ownership -------------------------------------------
    owned: dict[str, list[Path]] = {}
    for path in mapper_xml_files():
        rel = path.relative_to(REPO).as_posix()
        parts = rel.split("/")
        if parts[0] != "apps" or len(parts) < 3:
            problems.append(
                f"{rel} is a mapper XML outside apps/<module>/src/main/resources/mapper/ "
                f"-- a mapper XML in a shared lib is loaded by every service (section 80)")
            continue
        owned.setdefault(parts[1], []).append(path)

    # --- criterion 2: reachable from the module's own config ---------------
    for module, paths in sorted(owned.items()):
        resources = REPO / "apps" / module / "src" / "main" / "resources"
        patterns: list[str] = []
        for config in sorted(resources.rglob("application*.y*ml")):
            text = config.read_text(encoding="utf-8", errors="replace")
            for raw in MAPPED_LOCATIONS_RE.findall(text):
                patterns.append(CLASSPATH_PREFIX_RE.sub("", raw.strip().strip('"\'')))
        if not patterns:
            problems.append(
                f"apps/{module} ships {len(paths)} mapper XML but declares no "
                f"mapper-locations, so nothing loads them: "
                + ", ".join(p.name for p in paths))
            continue
        regexes = [re.compile(ant_to_regex(pattern)) for pattern in patterns]
        for path in paths:
            classpath_path = "/" + path.relative_to(resources).as_posix()
            if not any(regex.match(classpath_path) for regex in regexes):
                problems.append(
                    f"{path.relative_to(REPO).as_posix()} is not matched by any "
                    f"mapper-locations in apps/{module} ({patterns}) -- the file "
                    f"would never be loaded")

    # --- criterion 3: the global swallow must not spread -------------------
    offenders: list[str] = []
    survivors: list[str] = []
    for config in sorted(list((REPO / "apps").rglob("*.y*ml"))
                         + list((REPO / "libs").rglob("*.y*ml"))):
        rel = config.relative_to(REPO).as_posix()
        if "/target/" in rel:
            continue
        text = config.read_text(encoding="utf-8", errors="replace")
        has_swallow = any("classpath*:/mapper/" in raw
                          for raw in MAPPED_LOCATIONS_RE.findall(text))
        if has_swallow:
            if rel in KNOWN_MYBATIS_GLOBAL_SCAN:
                survivors.append(rel)
            else:
                offenders.append(rel)
    if offenders:
        problems.append(
            f"new classpath*:/mapper/ global swallow in {offenders} -- section 80 "
            f"forbids adding one; narrow it to the module's own mapper directory")
    if problems:
        report(gate, False, "; ".join(problems))
        return

    detail = (f"{len(owned)} module(s) own {len(mapper_xml_files())} mapper XML, "
              f"all reachable from their own config")
    stale = sorted(KNOWN_MYBATIS_GLOBAL_SCAN - set(survivors))
    if stale:
        detail += (f"; {stale} no longer use the global swallow, remove from "
                   f"KNOWN_MYBATIS_GLOBAL_SCAN")
    if survivors:
        detail += (f"; global swallow still present in {survivors} "
                   f"(deliberate until Wave 1 moves MyBatis config to Nacos)")
    report(gate, True, detail)


# ``@SchedulerLock`` and ``@net.javacrumbs...SchedulerLock`` must both be caught:
# a fully-qualified annotation is legal Java, and a gate that only matches the short
# form would let a per_instance task carry a global lock straight through (§69).
SCHEDULER_LOCK_RE = re.compile(r'@(?:[\w.]+\.)?SchedulerLock\s*\(\s*name\s*=\s*"([^"]+)"')


def scheduler_lock_at(path: Path, scheduled_line: int) -> str | None:
    """Return the ``@SchedulerLock`` name declared for a ``@Scheduled`` method.

    ``scheduled_line`` is the 1-based line of the ``@Scheduled`` annotation, which
    the scanner records. The annotation block may span several lines (the
    cron/fixedDelay arguments), so the argument list is skipped by balancing
    parentheses before looking for sibling annotations.

    Returns ``None`` both when there is no lock and when the recorded line does not
    hold ``@Scheduled`` (a stale inventory); the caller distinguishes the two.
    """
    lines = path.read_text(encoding="utf-8").splitlines()
    index = max(0, scheduled_line - 1)
    if index >= len(lines) or not lines[index].strip().startswith("@Scheduled"):
        return None
    depth = 0
    for offset in range(index, min(len(lines), index + 60)):
        line = lines[offset]
        if offset == index or depth > 0:
            depth += line.count("(") - line.count(")")
            continue
        match = SCHEDULER_LOCK_RE.search(line)
        if match:
            return match.group(1)
        if not line.strip().startswith("@"):
            # Annotation block ended without a lock annotation.
            return None
    return None


def gate_scheduler_lock_scope_consistency() -> None:
    """Enforce V9 §68/§69: a global lock belongs to ``global_once`` and ONLY there.

    Putting a global lock on a ``per_instance`` task makes N-1 instances stop
    refreshing their local state while the acceptance criterion ("did not run
    twice") still passes -- a failure that cannot fail. The reverse mistake is
    equally real: a ``global_once`` task with no lock runs on every instance.
    """
    gate = "scheduler-lock-scope-consistency"
    inventory = load("scheduled-inventory.yaml")["scheduled"]
    ownership = {entry["id"]: entry for entry in load("scheduled-ownership.yaml")["scheduled"]}

    problems: list[str] = []
    stale: list[str] = []
    checked = 0
    for item in inventory:
        entry = ownership.get(item["id"])
        if entry is None:
            continue  # covered by scheduled-ownership-coverage
        scope = entry.get("execution_scope")
        if scope not in ("global_once", "per_instance"):
            continue  # covered by execution-scope-validation

        path = REPO / item["file"]
        if not path.exists():
            problems.append(f"{item['id']}: {item['file']} missing")
            continue

        # The recorded line must actually hold @Scheduled. If it does not, the
        # inventory predates a source edit and every conclusion below is unreliable
        # -- so say that instead of blaming the annotation.
        lines = path.read_text(encoding="utf-8").splitlines()
        index = item["line"] - 1
        if index >= len(lines) or not lines[index].strip().startswith("@Scheduled"):
            stale.append(item["id"])
            continue

        checked += 1
        lock = scheduler_lock_at(path, item["line"])

        if scope == "global_once" and lock is None:
            problems.append(
                f"{item['id']}: global_once without @SchedulerLock "
                "(every instance would execute it)")
        if scope == "per_instance" and lock is not None:
            problems.append(
                f"{item['id']}: per_instance WITH @SchedulerLock "
                "(N-1 instances would stop refreshing -- §69)")
        if lock is not None and lock != item["id"]:
            problems.append(
                f"{item['id']}: @SchedulerLock(name={lock!r}) must equal the ownership id")

    if stale:
        problems.append(
            f"stale scheduled-inventory.yaml for {stale} -- re-run "
            "scripts/migration/scan.py (the recorded @Scheduled line no longer matches)")
    if problems:
        report(gate, False, "; ".join(problems[:8]))
        return
    report(gate, True, f"{checked} scheduled entries match their execution_scope")


def gate_scheduled_scan_ref_freshness() -> None:
    """Keep the human-facing ``scan_ref`` pointers honest (V9 §13).

    ``scan_ref`` is the only pointer a reader follows to find where a task is
    declared, and its line number is machine output rather than human judgement.
    It therefore rots on edits that change nothing about the task: adding one
    import above a scheduled method shifted **seven of twelve** pointers by
    exactly one line, and nothing noticed because no gate read them.

    A stale pointer is worse than no pointer -- it sends the reader to an
    unrelated line, where they conclude the annotation was deleted. That is the
    §12 "补了又丢" cycle expressed as a broken hyperlink, so it gets a gate.

    The parser is imported from ``scan`` rather than duplicated: the writer and
    the verifier must agree on the line format, or the gate starts failing for
    reasons that have nothing to do with the data.
    """
    gate = "scheduled-scan-ref-freshness"
    inventory = {item["id"]: item for item in load("scheduled-inventory.yaml")["scheduled"]}
    path = DOCS / "scheduled-ownership.yaml"

    problems: list[str] = []
    checked = 0
    current = None
    for line in path.read_text(encoding="utf-8").splitlines():
        id_match = scan.OWNERSHIP_ID_RE.match(line)
        if id_match:
            current = id_match.group(1)
            continue
        ref_match = scan.OWNERSHIP_SCAN_REF_RE.match(line)
        if not ref_match or current is None:
            continue
        item = inventory.get(current)
        if item is None:
            continue  # a planned task has no source yet; covered by ownership-coverage
        checked += 1
        expected = f"{item['file']}:{item['line']}"
        if ref_match.group(2) != expected:
            problems.append(f"{current}: scan_ref says {ref_match.group(2)}, source says {expected}")

    if problems:
        report(gate, False, "; ".join(problems[:6])
               + " -- re-run scripts/migration/scan.py to refresh")
        return
    report(gate, True, f"{checked} scan_ref pointers point at their @Scheduled line")


# Every inventory that is keyed by an identifier, plus the `counts` entry that is
# supposed to equal its length. The §13 methodology only works if the machine-owned
# key set is actually a *set*: a duplicate key means two entities share one key, so
# every keyed gate silently sees only one of them. A stale `counts.total` is the
# same failure in disguise -- it is what the plan's "补了又丢" cycle looks like.
#
# The identity is a tuple because a config key is legitimately declared once per
# source file (``application.yml`` vs ``application-local.yml``); its identity is
# ``(key, source)``, not ``key``.
ID_KEYED_INVENTORIES = (
    ("route-inventory.yaml", "routes", ("id",), "total"),
    ("api-migration.yaml", "entries", ("id",), "total"),
    ("scheduled-inventory.yaml", "scheduled", ("id",), "total"),
    ("scheduled-ownership.yaml", "scheduled", ("id",), "total"),
    ("table-inventory.yaml", "tables", ("table",), "total"),
    ("table-ownership.yaml", "tables", ("table",), "total"),
    ("config-inventory.yaml", "config", ("key", "source"), "total"),
    ("config-ownership.yaml", "config", ("key", "source"), "total"),
    ("capability-baseline.yaml", "capabilities", ("id",), "total"),
    ("contract-changes.yaml", "changes", ("id",), "total"),
    ("stream-hosts.yaml", "hosts", ("id",), None),
)


def gate_inventory_integrity() -> None:
    gate = "inventory-integrity"
    problems: list[str] = []
    for name, list_key, id_fields, count_key in ID_KEYED_INVENTORIES:
        doc = load(name)
        items = doc.get(list_key) or []
        label = "+".join(id_fields)
        keys = [tuple(item.get(field) for field in id_fields) for item in items]

        blank = sum(1 for key in keys if any(part in (None, "") for part in key))
        if blank:
            problems.append(f"{name}: {blank} entr(y|ies) missing {label}")

        present = [key for key in keys if all(part not in (None, "") for part in key)]
        duplicates = sorted({key for key in present if present.count(key) > 1})
        if duplicates:
            problems.append(
                f"{name}: duplicate {label} ({len(duplicates)}): {duplicates[:4]}")

        if count_key:
            declared = (doc.get("counts") or {}).get(count_key)
            if declared is not None and declared != len(items):
                problems.append(
                    f"{name}: counts.{count_key}={declared} but {len(items)} entries")

    if problems:
        report(gate, False, "; ".join(problems))
        return
    total = sum(len(load(name).get(list_key) or [])
                for name, list_key, _, _ in ID_KEYED_INVENTORIES)
    report(gate, True,
           f"{len(ID_KEYED_INVENTORIES)} inventories, {total} entries, "
           "unique keys, declared counts match")


GATES = {
    "inventory-integrity": gate_inventory_integrity,
    "route-inventory-drift": gate_route_inventory_drift,
    "api-migration-key-coverage": gate_api_migration_key_coverage,
    "auth-policy-coverage": gate_auth_policy_coverage,
    "auth-policy-token-location": gate_auth_policy_token_location,
    "scheduled-ownership-coverage": gate_scheduled_ownership_coverage,
    "execution-scope-validation": gate_execution_scope_validation,
    "scheduler-lock-scope-consistency": gate_scheduler_lock_scope_consistency,
    "scheduled-scan-ref-freshness": gate_scheduled_scan_ref_freshness,
    "table-ownership-drift": gate_table_ownership_drift,
    "config-ownership-drift": gate_config_ownership_drift,
    "shared-kernel-reverse-dependency": gate_shared_kernel_reverse_dependency,
    "shared-kernel-classification": gate_shared_kernel_classification,
    "contract-dependency-boundary": gate_contract_dependency_boundary,
    "cross-domain-import-baseline": gate_cross_domain_import_baseline,
    "deployment-unit-coupling": gate_deployment_unit_coupling,
    "contract-changes-completeness": gate_contract_changes_completeness,
    "capability-baseline-diff": gate_capability_baseline_diff,
    "legacy-redis-reference-check": gate_legacy_redis_reference_check,
    "gateway-forbidden-dependency-check": gate_gateway_forbidden_dependency,
    "gateway-stream-proxy-config-check": gate_gateway_stream_proxy_config,
    "flyway-validation": gate_flyway_validation,
    "stream-ban-boundary-coverage": gate_stream_ban_boundary_coverage,
    "xml-authoring-safety": gate_xml_authoring_safety,
    "dockerfile-module-coverage": gate_dockerfile_module_coverage,
    "mybatis-scan-scope": gate_mybatis_scan_scope,
}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("gates", nargs="*", help="run only these gates")
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--json", action="store_true", help="emit machine-readable output")
    args = parser.parse_args()

    if args.list:
        for name in GATES:
            print(name)
        return 0

    selected = args.gates or list(GATES)
    unknown = [name for name in selected if name not in GATES]
    if unknown:
        print(f"unknown gates: {unknown}")
        return 2

    for name in selected:
        GATES[name]()

    if args.json:
        print(json.dumps([{"gate": g, "status": s, "detail": d} for g, s, d in RESULTS],
                         ensure_ascii=False, indent=2))
    else:
        width = max(len(gate) for gate, _, _ in RESULTS)
        for gate, status, detail in RESULTS:
            print(f"{status:4}  {gate.ljust(width)}  {detail}")

    failures = [gate for gate, status, _ in RESULTS if status == "FAIL"]
    skipped = [gate for gate, status, _ in RESULTS if status == "SKIP"]
    print()
    print(f"{len(RESULTS)} gates: {len(RESULTS) - len(failures) - len(skipped)} pass, "
          f"{len(failures)} fail, {len(skipped)} skip")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())

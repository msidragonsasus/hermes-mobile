#!/usr/bin/env python3
"""Pure correction propagation and conservative material selection, not a model router."""
from __future__ import annotations

import copy
from typing import Any

from .state_v4 import COLLECTION_PREFIXES, DOCUMENT_PATH, PURPOSES, REFERENCE_PREFIXES, record_id
from .validate_state_v4 import validate_state


def require_valid(state: Any) -> None:
    errors = validate_state(state)
    if errors:
        raise ValueError("invalid state: " + "; ".join(errors))


def references(value: Any) -> set[str]:
    refs: set[str] = set()
    if isinstance(value, dict):
        for key, item in value.items():
            if key == "legacy_validation":
                continue
            if key in REFERENCE_PREFIXES:
                refs.update(item if isinstance(item, list) else [item])
            refs.update(references(item))
    elif isinstance(value, list):
        for item in value:
            refs.update(references(item))
    return refs


def material_references(record: dict) -> set[str]:
    """Historical version evidence must not taint an unrelated current draft."""
    current = {key: value for key, value in record.items() if key not in {"version_evidence", "review_fact_ids"}}
    path = record.get("path", record.get("file_path"))
    current["current_evidence"] = record.get("version_evidence", {}).get(path, [])
    return references(current)


def review_causes(state: dict) -> dict[str, set[str]]:
    causes = {f["fact_id"]: {f["fact_id"]} for f in state["facts"]
              if f["classification"] != "CONFIRMED" or f.get("needs_review")}
    records = [(c, r) for c in ("hypotheses", "stories", "documents") for r in state[c]]
    changed = True
    while changed:
        changed = False
        for collection, record in records:
            rid = record_id(collection, record)
            invalid = set().union(*(causes.get(ref, set()) for ref in material_references(record)))
            if invalid and invalid != causes.get(rid):
                causes[rid] = invalid
                changed = True
    return causes


def mark_reviews(state: dict) -> dict:
    """Keep review flags until explicitly resolved; never rewrite submitted history."""
    result = copy.deepcopy(state)
    causes = review_causes(result)
    for collection in ("hypotheses", "stories", "documents"):
        for record in result[collection]:
            if record.get("status") in {"submitted", "archived", "retired"}:
                continue
            rid = record_id(collection, record)
            if causes.get(rid):
                record["needs_review"] = True
                record["review_fact_ids"] = sorted(set(record.get("review_fact_ids", [])) | causes[rid])
    return result


def prepare_update(previous: dict, updated: dict) -> dict:
    """Enforce local transition invariants before a single atomic state write."""
    require_valid(updated)
    old_facts = {r["fact_id"]: r for r in previous["facts"]}
    corrected = set()
    for decision in updated["decisions"]:
        if decision.get("kind") == "FACT_CORRECTION":
            corrected.update(decision["target_ids"])
    for fact in updated["facts"]:
        old = old_facts.get(fact["fact_id"])
        if not old:
            continue
        if fact["claim"] != old["claim"]:
            raise ValueError("fact claim is historical; supersede it and create a new fact ID")
        if old["classification"] == "SUPERSEDED" and any(fact.get(k) != old.get(k) for k in ("classification", "provenance")):
            raise ValueError("SUPERSEDED facts cannot be revived or have their evidence rewritten")
        if old["classification"] != "SUPERSEDED" and fact["classification"] == "SUPERSEDED" and fact["fact_id"] not in corrected:
            raise ValueError("superseding a fact requires a FACT_CORRECTION decision with user evidence")
    old_docs = {r["document_id"]: r for r in previous["documents"]}
    for doc in updated["documents"]:
        old = old_docs.get(doc["document_id"], {})
        if old.get("status") == "submitted" and doc != old:
            raise ValueError("submitted document metadata and evidence are immutable; create a separate document")
        for version, evidence in old.get("version_evidence", {}).items():
            if doc.get("version_evidence", {}).get(version) != evidence:
                raise ValueError("saved version evidence is immutable")
        path = doc.get("path", doc.get("file_path"))
        old_path = old.get("path", old.get("file_path"))
        if old_path and not path:
            raise ValueError("saved document path cannot be removed or cleared")
        if path and path != old_path:
            if path in old.get("version_evidence", {}):
                raise ValueError("previously saved document versions cannot be reused")
            if path not in doc.get("version_evidence", {}):
                raise ValueError("new document versions require version_evidence (empty only if no personal claims)")
            if old_path and int(DOCUMENT_PATH.fullmatch(path).group(2)) <= int(DOCUMENT_PATH.fullmatch(old_path).group(2)):
                raise ValueError("new document version must increase")
        if old_path and path == old_path and doc.get("checksum_sha256") != old.get("checksum_sha256"):
            raise ValueError("saved document content cannot change at the same path")
    result = mark_reviews(updated)
    causes = review_causes(result)
    for doc in result["documents"]:
        old = old_docs.get(doc["document_id"], {})
        path = doc.get("path", doc.get("file_path"))
        unresolved = doc.get("needs_review") or causes.get(doc["document_id"]) or not path or path not in doc.get("version_evidence", {})
        if unresolved and doc.get("status") in {"ready", "submitted"}:
            old_path = old.get("path", old.get("file_path"))
            if doc.get("status") == old.get("status") == "ready" and path == old_path:
                # Corrections invalidate an existing ready version; they are not a new
                # readiness request. Preserve saved content and accept the correction.
                doc["status"] = "review"
                doc["needs_review"] = True
            elif doc != old:
                raise ValueError("document with unresolved evidence cannot become ready/submitted")
    require_valid(result)
    return result


def scope_matches(scope: dict, request: dict) -> bool | None:
    """None means context is missing: do not silently ignore a scoped exclusion."""
    missing = False
    for key, value in scope.items():
        if key == "purpose" and value == "all":
            continue
        if key not in request:
            missing = True
        elif request[key] != value:
            return False
    return None if missing else True


def select_context(state: dict, purpose: str, *, scope: dict | None = None,
                   unavailable_source_ids: list[str] | None = None) -> dict:
    """Select structurally eligible material. Semantic relevance/support still needs review."""
    require_valid(state)
    if purpose not in PURPOSES:
        raise ValueError("unknown dialogue purpose")
    request = dict(scope or {}, purpose=purpose)
    blocked_sources = set(unavailable_source_ids or []) | {
        s["source_id"] for s in state["sources"] if s.get("access_status") in {"blocked", "removed", "login_required"}
    }
    causes = review_causes(state)
    blocked = set(causes)
    excluded, unresolved, decisions = set(), [], []
    for decision in state["decisions"]:
        if decision.get("status") == "retired":
            continue
        match = scope_matches(decision.get("scope", {"purpose": "all"}), request)
        if match is False:
            continue
        decisions.append(copy.deepcopy(decision))
        if match is None:
            unresolved.append(decision["decision_id"])
        if decision.get("kind") == "SCOPE_EXCLUSION":
            excluded.update(decision["target_ids"])
    # A story exclusion applies to that story's constituent material for this request only.
    for story in state["stories"]:
        if story["story_id"] in excluded:
            excluded.update(ref for ref in material_references(story) if ref.startswith("FACT-"))
    for fact in state["facts"]:
        if references(fact) & blocked_sources:
            blocked.add(fact["fact_id"])
    for collection in ("hypotheses", "stories", "documents"):
        for record in state[collection]:
            rid = record_id(collection, record)
            if record.get("needs_review") or record.get("status") in {"submitted", "archived", "retired", "rejected"}:
                blocked.add(rid)
            if collection == "stories" and not any(ref.startswith("FACT-") for ref in material_references(record)):
                blocked.add(rid)
            if collection == "documents":
                path = record.get("path", record.get("file_path"))
                if not path or path not in record.get("version_evidence", {}):
                    blocked.add(rid)
    changed = True
    while changed:
        changed = False
        for collection in ("hypotheses", "stories", "documents"):
            for record in state[collection]:
                rid = record_id(collection, record)
                if rid not in blocked and material_references(record) & (blocked | excluded):
                    blocked.add(rid)
                    changed = True
    result = {c: [copy.deepcopy(r) for r in state[c] if record_id(c, r) not in blocked | excluded]
              for c in ("facts", "hypotheses", "stories", "documents")}
    result.update({"candidate": copy.deepcopy(state["candidate"]), "candidate_is_evidence": False,
                   "decisions": decisions, "last_session": copy.deepcopy(state["last_session"]),
                   "excluded_ids": sorted(excluded), "needs_review_ids": sorted(blocked),
                   "unresolved_scope_decision_ids": sorted(unresolved), "semantic_support_verified": False})
    result["historical_documents"] = [dict(copy.deepcopy(doc), historical_only=True,
        needs_reconfirmation=bool(causes.get(doc["document_id"]))) for doc in state["documents"]
        if doc.get("status") == "submitted" and ("application_id" not in request or doc.get("application_id") == request["application_id"])]
    for answer in result["last_session"].get("answered_questions", []):
        answer["needs_reconfirmation"] = bool(set(answer["fact_ids"]) & blocked)
    return result


def main() -> int:
    import argparse
    import json
    from pathlib import Path
    from .migrate_state_v40 import load_state_compatible
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("state", type=Path)
    parser.add_argument("--purpose", required=True, choices=sorted(PURPOSES))
    parser.add_argument("--scope", default="{}", help="JSON object with question_type/application_id/company_id")
    args = parser.parse_args()
    state, report = load_state_compatible(json.loads(args.state.read_text(encoding="utf-8")))
    if not report["write_ready"]:
        print(json.dumps({"structural_errors": report["errors"], "context_selected": False}, ensure_ascii=False))
        return 2
    scope = json.loads(args.scope)
    if not isinstance(scope, dict):
        parser.error("scope must be an object")
    result = select_context(state, args.purpose, scope=scope)
    result["migration_report"] = report
    print(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

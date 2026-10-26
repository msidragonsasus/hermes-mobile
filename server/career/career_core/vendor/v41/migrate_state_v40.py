#!/usr/bin/env python3
"""Read/preview schema 4.0 without modifying it; never certifies unsupported old claims."""
from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path

from .state_v4 import CLASSIFICATIONS, ID_PATTERN, REFERENCE_PREFIXES, SCHEMA_VERSION, increment_revision
from .validate_state_v4 import enum, provenance_errors, validate_state
from .coaching_state import mark_reviews

LEGACY_SOURCE = {
    "USER_DIRECT": ("CHAT_HISTORY_SURFACED", "USER_STATEMENT"),
    "USER_PRIMARY": ("USER_PRIMARY", "USER_PRIMARY"),
    "DRAFT_TEXT": ("CHAT_HISTORY_SURFACED", "GENERATED_DOCUMENT"),
    "SYSTEM_INFERENCE": ("CHAT_HISTORY_SURFACED", "MODEL_SUMMARY"),
    "EXTERNAL": ("EXTERNAL", "EXTERNAL"),
}


def load_state_compatible(state: dict) -> tuple[dict, dict]:
    if not isinstance(state, dict) or not isinstance(state.get("schema_version"), str) or state["schema_version"] not in {"4.0", SCHEMA_VERSION}:
        raise ValueError("only schema 4.0 and 4.1 are supported; migrate v3 through its existing converter")
    if state["schema_version"] == SCHEMA_VERSION:
        errors = validate_state(state)
        return copy.deepcopy(state), {"source_schema": SCHEMA_VERSION, "target_schema": SCHEMA_VERSION,
            "source_modified": False, "requires_migration_consent": False, "write_ready": not errors,
            "changes": [], "errors": errors}
    result = copy.deepcopy(state)
    result["schema_version"] = SCHEMA_VERSION
    changes = []

    def changed(path, reason):
        changes.append({"path": path, "reason": reason})

    def normalize(value, path):
        if isinstance(value, dict):
            for key, item in list(value.items()):
                if key == "legacy_validation":
                    continue
                if key in REFERENCE_PREFIXES and key.endswith("_ids") and isinstance(item, str):
                    refs = [r.strip() for r in item.split("|")]
                    if refs and all(ID_PATTERN.fullmatch(r) and r.split("-", 1)[0] in REFERENCE_PREFIXES[key] for r in refs):
                        value[key] = refs
                        changed(f"{path}.{key}", "legacy_pipe_references_to_array")
                if key == "provenance" and isinstance(item, dict):
                    # Transport alone is not evidence of authorship.
                    kind = item.get("source_kind")
                    role = {"CURRENT_USER_STATEMENT": "USER_STATEMENT", "USER_PRIMARY": "USER_PRIMARY", "EXTERNAL": "EXTERNAL"}.get(kind, "UNKNOWN") if isinstance(kind, str) else "UNKNOWN"
                    if "source_role" not in item:
                        item["source_role"] = role
                        changed(f"{path}.provenance.source_role", "explicit_legacy_author_or_unknown")
                    item.setdefault("source_date", "unknown")
                normalize(value[key], f"{path}.{key}")
        elif isinstance(value, list):
            for index, item in enumerate(value):
                normalize(item, f"{path}[{index}]")

    normalize(result, "$")
    facts = result.get("facts", [])
    for index, fact in enumerate(facts if isinstance(facts, list) else []):
        if not isinstance(fact, dict):
            continue
        path = f"$.facts[{index}]"
        original = copy.deepcopy(state["facts"][index])
        old_class, old_status = original.get("classification"), original.get("status")
        value = old_class if old_class is not None else old_status
        classification = value.upper() if isinstance(value, str) else None
        reasons = []
        if not enum(classification, CLASSIFICATIONS):
            classification = "UNKNOWN"
            reasons.append("missing_or_invalid_classification")
        elif old_class is not None and old_status is not None and str(old_class).upper() != str(old_status).upper():
            classification = "CONFLICTED"
            reasons.append("classification_status_disagree")
        fact.pop("status", None)
        fact["classification"] = classification
        if old_status is not None or old_class != classification:
            changed(path + ".classification", "canonical_classification")
        if "provenance" not in fact and any(k in fact for k in ("source_kind", "source_ref")):
            legacy_kind = fact.get("source_kind")
            kind, role = LEGACY_SOURCE.get(legacy_kind, ("LEGACY_NOTE", "UNKNOWN")) if isinstance(legacy_kind, str) else ("LEGACY_NOTE", "UNKNOWN")
            fact["provenance"] = {
                "source_kind": kind, "source_role": role,
                "source_ref": fact.get("source_ref", ""), "source_date": "unknown",
                "imported_at_jst": result.get("updated_at_jst"), "confidence": "low",
            }
            for key in ("source_kind", "source_ref"):
                fact.pop(key, None)
            changed(path + ".provenance", "legacy_flat_evidence")
        evidence = fact.get("provenance")
        if evidence is not None and provenance_errors(evidence, path + ".provenance"):
            # Preserve the rejected evidence only as diagnostics, not as an active source.
            fact.pop("provenance")
            reasons.append("invalid_legacy_provenance")
            evidence = None
        if classification == "CONFIRMED" and provenance_errors(evidence, path + ".provenance", primary=True):
            fact["classification"] = "UNKNOWN"
            fact["needs_review"] = True
            reasons.append("confirmed_evidence_not_traceable")
        if reasons or old_status is not None or old_class != fact["classification"]:
            fact["legacy_validation"] = {
                "classification": old_class, "status": old_status,
                "provenance": original.get("provenance"), "source_kind": original.get("source_kind"),
                "source_ref": original.get("source_ref"), "reasons": reasons or ["legacy_alias_normalized"],
            }
            for reason in reasons:
                changed(path, reason)
    docs = result.get("documents", [])
    for index, doc in enumerate(docs if isinstance(docs, list) else []):
        if isinstance(doc, dict) and not enum(doc.get("status"), {"submitted", "archived"}):
            path = doc.get("path", doc.get("file_path"))
            evidence = doc.get("version_evidence", {})
            if not isinstance(path, str) or not path or not isinstance(evidence, dict) or path not in evidence:
                doc["needs_review"] = True
                doc["review_reason"] = "legacy_evidence_unmapped"
                changed(f"$.documents[{index}]", "legacy_evidence_unmapped")
    errors = validate_state(result)
    if not errors:
        result = mark_reviews(result)
        errors = validate_state(result)
    return result, {"source_schema": "4.0", "target_schema": SCHEMA_VERSION, "source_modified": False,
                    "requires_migration_consent": True, "write_ready": not errors,
                    "changes": changes, "errors": errors}


def prepare_migration(state: dict, updated_at_jst: str, operations: list | None = None) -> tuple[dict, dict]:
    """Build a reviewable write candidate, not a write. Caller must obtain migration consent and fresh SHA."""
    from .merge_state_v4 import apply_patch
    from .state_v4 import PATCH_TYPE
    normalized, report = load_state_compatible(state)
    if not report["write_ready"]:
        raise ValueError("migration has unresolved structural errors: " + "; ".join(report["errors"]))
    candidate = apply_patch(normalized, {"patch_type": PATCH_TYPE, "schema_version": SCHEMA_VERSION,
        "base_revision": normalized["revision"], "operations": [] if operations is None else operations}, updated_at_jst)
    if state["schema_version"] == "4.0" and candidate["revision"] == state["revision"]:
        candidate = increment_revision(candidate, updated_at_jst)
    report["preview_only"] = True
    report["proposed_revision"] = candidate["revision"]
    return candidate, report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", required=True, type=Path, help="New local preview file; never overwrites an existing file.")
    args = parser.parse_args()
    if args.output.resolve() == args.source.resolve() or args.output.exists():
        parser.error("output must be a new path different from source")
    value, report = load_state_compatible(json.loads(args.source.read_text(encoding="utf-8")))
    if not report["write_ready"]:
        print(json.dumps({"preview_only": True, "state_included": False, "report": report}, ensure_ascii=False))
        return 2
    # Preview includes unresolved records explicitly. It is not a write-through payload.
    with args.output.open("x", encoding="utf-8", newline="\n") as handle:
        json.dump({"preview_only": True, "state": value, "report": report}, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
    print(json.dumps(report, ensure_ascii=False, sort_keys=True))
    return 0 if report["write_ready"] else 2


if __name__ == "__main__":
    raise SystemExit(main())

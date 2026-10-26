#!/usr/bin/env python3
"""Structural validation for schema 4.1. It does not certify semantic truth."""
from __future__ import annotations
import argparse
import json
from datetime import datetime
from pathlib import Path
from typing import Any

from .state_v4 import (
    APPLICATION_STATUSES, CLASSIFICATIONS, COLLECTION_PREFIXES, CONFIDENCE_LEVELS,
    DATE_ONLY, DEADLINE_PRECISIONS, DECISION_KINDS, DOCUMENT_PATH, DOCUMENT_STATUSES,
    EVENT_OUTCOMES, EVENT_TYPES, EXACT_JST, FORBIDDEN_FIELD_NAMES, HYPOTHESIS_STATUSES,
    ID_FIELDS, ID_PATTERN, KIND_ROLES, MAX_STATE_BYTES, PRIMARY_ROLES, PURPOSES,
    REFERENCE_PREFIXES, SCHEMA_VERSION, SHA256, SOURCE_ACCESS, SOURCE_FRESHNESS,
    SOURCE_KINDS, SOURCE_ROLES, SOURCE_TYPES, TASK_STATUSES, deterministic_dumps,
    record_id, scan_secrets,
)
ENUM_FIELDS = {
    "hypotheses": {"status": HYPOTHESIS_STATUSES, "confidence": CONFIDENCE_LEVELS},
    "applications": {"status": APPLICATION_STATUSES},
    "tasks": {"status": TASK_STATUSES},
    "documents": {"status": DOCUMENT_STATUSES},
    "selection_events": {"event_type": EVENT_TYPES, "outcome": EVENT_OUTCOMES},
    "sources": {"source_type": SOURCE_TYPES, "freshness_status": SOURCE_FRESHNESS, "access_status": SOURCE_ACCESS},
}
ROOT_FIELDS = {"schema_version", "revision", "updated_at_jst", "candidate", "last_session", *COLLECTION_PREFIXES}
SCOPE_FIELDS = {"purpose", "question_type", "application_id", "company_id"}


def nonempty(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def enum(value: Any, allowed: set[str]) -> bool:
    return isinstance(value, str) and value in allowed


def valid_date(value: Any, exact: bool = False) -> bool:
    if not isinstance(value, str) or not (EXACT_JST if exact else DATE_ONLY).fullmatch(value):
        return False
    try:
        datetime.strptime(value, "%Y-%m-%d %H:%M JST" if exact else "%Y-%m-%d")
    except ValueError:
        return False
    return True


def provenance_errors(value: Any, path: str, *, primary: bool = False) -> list[str]:
    if not isinstance(value, dict):
        return [f"{path}: provenance must be an object"]
    errors = []
    kind, role = value.get("source_kind"), value.get("source_role")
    if not enum(kind, SOURCE_KINDS):
        errors.append(f"{path}.source_kind: invalid value")
    if not enum(role, SOURCE_ROLES):
        errors.append(f"{path}.source_role: invalid value")
    elif isinstance(kind, str) and role not in KIND_ROLES.get(kind, SOURCE_ROLES):
        errors.append(f"{path}.source_role: inconsistent with source_kind")
    if primary and not enum(role, PRIMARY_ROLES):
        errors.append(f"{path}: CONFIRMED requires user statement or primary evidence, never model text")
    if not nonempty(value.get("source_ref")):
        errors.append(f"{path}.source_ref: evidence locator is required")
    if not enum(value.get("confidence"), CONFIDENCE_LEVELS):
        errors.append(f"{path}.confidence: invalid value")
    if not valid_date(value.get("imported_at_jst"), exact=True):
        errors.append(f"{path}.imported_at_jst: invalid timestamp")
    if value.get("source_date") != "unknown" and not valid_date(value.get("source_date")):
        errors.append(f"{path}.source_date: use a date or unknown")
    return errors


def _walk(value, path, ids, errors, *, identity=None, opaque=False):
    if isinstance(value, dict):
        for key, item in value.items():
            child = f"{path}.{key}"
            if key.casefold() in FORBIDDEN_FIELD_NAMES:
                errors.append(f"{child}: forbidden field")
            # Old values remain diagnostics; still recurse for privacy.
            diagnostic = opaque or key == "legacy_validation"
            if not diagnostic:
                if key in REFERENCE_PREFIXES and key != identity:
                    many = key.endswith("_ids")
                    if many and not isinstance(item, list):
                        errors.append(f"{child}: references must be an array")
                    else:
                        for index, reference in enumerate(item if many else [item]):
                            refpath = f"{child}[{index}]" if many else child
                            if not isinstance(reference, str) or not ID_PATTERN.fullmatch(reference):
                                errors.append(f"{refpath}: invalid reference ID")
                            elif reference.split("-", 1)[0] not in REFERENCE_PREFIXES[key]:
                                errors.append(f"{refpath}: wrong reference collection")
                            elif reference not in ids:
                                errors.append(f"{refpath}: unknown reference {reference}")
                elif key.endswith("_ids") and key not in REFERENCE_PREFIXES:
                    errors.append(f"{child}: unrecognized reference field")
                if key == "provenance":
                    errors.extend(provenance_errors(item, child))
                if key in {"source_role", "classification"} and not enum(item, SOURCE_ROLES if key == "source_role" else CLASSIFICATIONS):
                    errors.append(f"{child}: invalid enum value")
                if key == "needs_review" and not isinstance(item, bool):
                    errors.append(f"{child}: must be a boolean")
                if key == "status" and not isinstance(item, str):
                    errors.append(f"{child}: status must be a string")
            _walk(item, child, ids, errors, opaque=diagnostic)
    elif isinstance(value, list):
        for index, item in enumerate(value):
            _walk(item, f"{path}[{index}]", ids, errors, opaque=opaque)


def _decision(record, path, errors):
    # Selection reads scope even on historical decisions without a kind.
    if "kind" in record or "scope" in record:
        scope = record.get("scope")
        if not isinstance(scope, dict) or not scope or set(scope) - SCOPE_FIELDS:
            errors.append(f"{path}.scope: specify purpose, question_type, application_id or company_id")
        else:
            if "purpose" in scope and not enum(scope["purpose"], PURPOSES | {"all"}):
                errors.append(f"{path}.scope.purpose: invalid value")
            if any(not nonempty(value) for value in scope.values()):
                errors.append(f"{path}.scope: values must be non-empty strings")
    if "kind" not in record:  # Historical records remain readable with a valid or absent scope.
        return
    kind = record["kind"]
    if not enum(kind, DECISION_KINDS):
        errors.append(f"{path}.kind: invalid decision kind")
        return
    if not nonempty(record.get("reason")):
        errors.append(f"{path}.reason: required")
    targets = record.get("target_ids")
    if kind in {"FACT_CORRECTION", "SCOPE_EXCLUSION"}:
        if not isinstance(targets, list) or not targets:
            errors.append(f"{path}.target_ids: at least one target is required")
    if kind == "FACT_CORRECTION":
        if isinstance(targets, list) and any(not isinstance(r, str) or not r.startswith("FACT-") for r in targets):
            errors.append(f"{path}.target_ids: corrections target facts")
        errors.extend(provenance_errors(record.get("provenance"), path + ".provenance", primary=True))
    elif kind in {"SCOPE_EXCLUSION", "STYLE_PREFERENCE", "TEMPORARY_POLICY", "STRATEGY_CHOICE"}:
        errors.extend(provenance_errors(record.get("provenance"), path + ".provenance", primary=True))
    if kind == "STYLE_PREFERENCE" and (not nonempty(record.get("preferred")) or not nonempty(record.get("rejected"))):
        errors.append(f"{path}: style preference requires a preferred/rejected pair")
    if kind in {"TEMPORARY_POLICY", "STRATEGY_CHOICE"} and not nonempty(record.get("review_condition")):
        errors.append(f"{path}.review_condition: required")
    if "status" in record and not enum(record["status"], {"active", "retired"}):
        errors.append(f"{path}.status: invalid value")


def _session(value, errors):
    if "purpose" in value and not enum(value["purpose"], PURPOSES):
        errors.append("$.last_session.purpose: invalid value")
    for field in ("topic", "next_question"):
        if field in value and not isinstance(value[field], str):
            errors.append(f"$.last_session.{field}: must be a string")
    if "open_questions" in value and (not isinstance(value["open_questions"], list) or any(not nonempty(q) for q in value["open_questions"])):
        errors.append("$.last_session.open_questions: must be an array of non-empty questions")
    if "answered_questions" in value:
        if not isinstance(value["answered_questions"], list):
            errors.append("$.last_session.answered_questions: must be an array")
        else:
            for index, question in enumerate(value["answered_questions"]):
                if not isinstance(question, dict) or not nonempty(question.get("question")) or not isinstance(question.get("fact_ids"), list):
                    errors.append(f"$.last_session.answered_questions[{index}]: question and fact_ids are required")


def _document(record, path, rid, errors):
    docpath = record.get("path", record.get("file_path"))
    for alias in ("path", "file_path"):
        value = record.get(alias)
        if value is not None:
            match = DOCUMENT_PATH.fullmatch(value) if isinstance(value, str) else None
            if not match or match.group(1) != rid:
                errors.append(f"{path}.{alias}: invalid immutable document path")
    if "path" in record and "file_path" in record and record["path"] != record["file_path"]:
        errors.append(f"{path}: path and file_path must identify the same document version")
    checksum = record.get("checksum_sha256")
    if "checksum_sha256" in record and (not isinstance(checksum, str) or not SHA256.fullmatch(checksum)):
        errors.append(f"{path}.checksum_sha256: invalid SHA-256")
    evidence = record.get("version_evidence", {})
    if not isinstance(evidence, dict):
        errors.append(f"{path}.version_evidence: must be an object keyed by immutable document path")
        return
    for version_path, statements in evidence.items():
        match = DOCUMENT_PATH.fullmatch(version_path)
        if not match or match.group(1) != rid:
            errors.append(f"{path}.version_evidence: invalid document version path")
        if not isinstance(statements, list):
            errors.append(f"{path}.version_evidence: statements must be an array")
            continue
        for statement in statements:
            if not isinstance(statement, dict) or not nonempty(statement.get("claim")) or not isinstance(statement.get("fact_ids"), list) or not statement["fact_ids"]:
                errors.append(f"{path}.version_evidence: each personal claim requires fact_ids")
    current = evidence.get(docpath) if isinstance(docpath, str) else None
    if isinstance(current, list) and isinstance(record.get("verified_fact_ids"), list):
        refs = [ref for item in current if isinstance(item, dict) and isinstance(item.get("fact_ids"), list) for ref in item["fact_ids"]]
        if all(isinstance(ref, str) for ref in refs + record["verified_fact_ids"]) and set(refs) != set(record["verified_fact_ids"]):
            errors.append(f"{path}.verified_fact_ids: must match the current version's statement evidence")


def validate_state(state: Any) -> list[str]:
    """Strict write validation. Use load_state_compatible for a 4.0 read/preview."""
    errors: list[str] = []
    if not isinstance(state, dict):
        return ["$: state must be an object"]
    try:
        json.dumps(state, ensure_ascii=False, sort_keys=True, allow_nan=False).encode("utf-8")
        def valid_keys(v):
            return all(isinstance(k, str) and valid_keys(x) for k, x in v.items()) if isinstance(v, dict) else all(valid_keys(x) for x in v) if isinstance(v, list) else True
        if not valid_keys(state):
            return ["$: object keys must be strings"]
        if len(deterministic_dumps(state).encode("utf-8")) > MAX_STATE_BYTES:
            errors.append(f"$: state exceeds the {MAX_STATE_BYTES}-byte automatic write limit")
    except (ValueError, TypeError, UnicodeError, RecursionError):
        return ["$: state must contain finite, serializable JSON values"]
    if set(state) - ROOT_FIELDS:
        errors.append("$: unsupported root fields: " + ", ".join(sorted(set(state) - ROOT_FIELDS)))
    if state.get("schema_version") != SCHEMA_VERSION:
        errors.append(f"$.schema_version: must be {SCHEMA_VERSION}; preview legacy migration before writing")
    revision = state.get("revision")
    if not isinstance(revision, int) or isinstance(revision, bool) or revision < 1:
        errors.append("$.revision: must be a positive integer")
    if not valid_date(state.get("updated_at_jst"), exact=True):
        errors.append("$.updated_at_jst: must use YYYY-MM-DD HH:MM JST")
    for field in ("candidate", "last_session"):
        if not isinstance(state.get(field), dict):
            errors.append(f"$.{field}: must be an object")
    ids, records = {}, []
    for collection, prefix in COLLECTION_PREFIXES.items():
        items = state.get(collection)
        if not isinstance(items, list):
            errors.append(f"$.{collection}: must be an array")
            continue
        for index, record in enumerate(items):
            path = f"$.{collection}[{index}]"
            if not isinstance(record, dict):
                errors.append(f"{path}: must be an object")
                continue
            rid = record_id(collection, record)
            if not rid or not ID_PATTERN.fullmatch(rid) or not rid.startswith(prefix + "-"):
                errors.append(f"{path}: invalid or missing {prefix} ID")
                continue
            if rid in ids:
                errors.append(f"{path}: duplicate ID {rid}; first seen at {ids[rid]}")
            ids[rid] = path
            records.append((collection, record, path, rid))
    for collection, record, path, rid in records:
        for field, allowed in ENUM_FIELDS.get(collection, {}).items():
            if field in record and not enum(record[field], allowed):
                errors.append(f"{path}.{field}: invalid enum value")
        _walk(record, path, ids, errors, identity=ID_FIELDS[collection])
        if collection == "facts":
            if "status" in record:
                errors.append(f"{path}.status: use classification; legacy alias is read-only")
            if not enum(record.get("classification"), CLASSIFICATIONS):
                errors.append(f"{path}.classification: required valid classification")
            if not nonempty(record.get("claim")):
                errors.append(f"{path}.claim: non-empty claim is required")
            if record.get("classification") == "CONFIRMED":
                errors.extend(provenance_errors(record.get("provenance"), path + ".provenance", primary=True))
            if record.get("superseded_by_fact_id") == rid:
                errors.append(f"{path}: a fact cannot replace itself")
            if "superseded_by_fact_id" in record and record.get("classification") != "SUPERSEDED":
                errors.append(f"{path}: only SUPERSEDED facts have a replacement")
        if collection == "decisions":
            _decision(record, path, errors)
        for date_field, precision_field in (("deadline_jst", "deadline_precision"), ("due_jst", "due_precision")):
            precision = record.get(precision_field)
            if precision_field in record:
                if not enum(precision, DEADLINE_PRECISIONS):
                    errors.append(f"{path}.{precision_field}: invalid date precision")
                elif record.get(date_field) and (precision == "unknown" or not valid_date(record[date_field], exact=precision == "exact")):
                    errors.append(f"{path}.{date_field}: date does not match precision {precision}")
        if collection == "documents":
            _document(record, path, rid, errors)
    for field in ("candidate", "last_session"):
        _walk(state.get(field), f"$.{field}", ids, errors)
    if isinstance(state.get("last_session"), dict):
        _session(state["last_session"], errors)
    errors.extend(scan_secrets(state))
    replacements = {r["fact_id"]: r["superseded_by_fact_id"] for r in state.get("facts", [])
                    if isinstance(r, dict) and isinstance(r.get("fact_id"), str) and isinstance(r.get("superseded_by_fact_id"), str)} if isinstance(state.get("facts"), list) else {}
    for start in replacements:
        seen, current = set(), start
        while current in replacements:
            if current in seen:
                errors.append("$.facts: replacement cycle")
                break
            seen.add(current)
            current = replacements[current]
    return list(dict.fromkeys(errors))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("state", type=Path)
    args = parser.parse_args()
    try:
        state = json.loads(args.state.read_text(encoding="utf-8"))
    except Exception as exc:
        print(f"ERROR: cannot read state: {exc}")
        return 2
    errors = validate_state(state)
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 2
    print(f"OK: structural validation only; state v{SCHEMA_VERSION} revision {state['revision']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

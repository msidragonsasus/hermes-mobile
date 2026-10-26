#!/usr/bin/env python3
"""Validate a GitHub-backed jp-job-hunting state v4 document."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from .state_v4 import (
    APPLICATION_STATUSES, CLASSIFICATIONS, COLLECTION_PREFIXES, CONFIDENCE_LEVELS, DATE_ONLY,
    DEADLINE_PRECISIONS, DOCUMENT_PATH, EXACT_JST, FORBIDDEN_FIELD_NAMES,
    DOCUMENT_STATUSES, EVENT_OUTCOMES, EVENT_TYPES, HYPOTHESIS_STATUSES,
    ID_PATTERN, MAX_STATE_BYTES, SCHEMA_VERSION, SHA256, SOURCE_ACCESS,
    SOURCE_FRESHNESS, SOURCE_KINDS, SOURCE_TYPES, TASK_STATUSES,
    deterministic_dumps, record_id, scan_secrets,
)

REFERENCE_FIELDS = {
    "application_id", "company_id", "document_id", "event_id", "fact_id",
    "hypothesis_id", "source_id", "story_id", "task_id",
}
ENUM_FIELDS = {
    "hypotheses": {"status": HYPOTHESIS_STATUSES, "confidence": CONFIDENCE_LEVELS},
    "applications": {"status": APPLICATION_STATUSES},
    "tasks": {"status": TASK_STATUSES},
    "documents": {"status": DOCUMENT_STATUSES},
    "selection_events": {"event_type": EVENT_TYPES, "outcome": EVENT_OUTCOMES},
    "sources": {"source_type": SOURCE_TYPES, "freshness_status": SOURCE_FRESHNESS, "access_status": SOURCE_ACCESS},
}


def _walk_forbidden(value: Any, path: str, errors: list[str]) -> None:
    if isinstance(value, dict):
        for key, item in value.items():
            if key.casefold() in FORBIDDEN_FIELD_NAMES:
                errors.append(f"{path}.{key}: forbidden field")
            _walk_forbidden(item, f"{path}.{key}", errors)
    elif isinstance(value, list):
        for index, item in enumerate(value):
            _walk_forbidden(item, f"{path}[{index}]", errors)


def _references(record: dict[str, Any]) -> list[tuple[str, str]]:
    refs: list[tuple[str, str]] = []
    for key, value in record.items():
        if key in REFERENCE_FIELDS and isinstance(value, str) and value:
            refs.append((key, value))
        elif key.endswith("_ids"):
            if isinstance(value, list):
                refs.extend((key, item) for item in value if isinstance(item, str) and item)
            elif isinstance(value, str):
                refs.extend((key, item.strip()) for item in value.split("|") if item.strip())
    return refs


def validate_state(state: Any) -> list[str]:
    errors: list[str] = []
    if not isinstance(state, dict):
        return ["$: state must be an object"]
    if len(deterministic_dumps(state).encode("utf-8")) > MAX_STATE_BYTES:
        errors.append(f"$: state exceeds the {MAX_STATE_BYTES}-byte automatic write limit")
    if state.get("schema_version") != SCHEMA_VERSION:
        errors.append(f"$.schema_version: must be {SCHEMA_VERSION}")
    revision = state.get("revision")
    if not isinstance(revision, int) or isinstance(revision, bool) or revision < 1:
        errors.append("$.revision: must be a positive integer")
    if not isinstance(state.get("updated_at_jst"), str) or not EXACT_JST.fullmatch(state["updated_at_jst"]):
        errors.append("$.updated_at_jst: must use YYYY-MM-DD HH:MM JST")
    if not isinstance(state.get("candidate"), dict):
        errors.append("$.candidate: must be an object")
    if not isinstance(state.get("last_session"), dict):
        errors.append("$.last_session: must be an object")

    ids: dict[str, str] = {}
    records: list[tuple[str, dict[str, Any], str]] = []
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
            else:
                ids[rid] = path
            for field, allowed in ENUM_FIELDS.get(collection, {}).items():
                if field in record and record[field] not in allowed:
                    errors.append(f"{path}.{field}: invalid enum value {record[field]!r}")
            records.append((path, record, rid))

    for path, record, rid in records:
        for key, reference in _references(record):
            if reference == rid:
                continue
            if ID_PATTERN.fullmatch(reference) and reference not in ids:
                errors.append(f"{path}.{key}: unknown reference {reference}")
        classification = record.get("classification")
        if classification is None and rid.startswith("FACT-"):
            classification = record.get("status")
        if classification is not None and isinstance(classification, str) and classification.upper() in CLASSIFICATIONS:
            pass
        elif record.get("classification") is not None:
            errors.append(f"{path}.classification: invalid value")
        provenance = record.get("provenance")
        if provenance is not None:
            if not isinstance(provenance, dict):
                errors.append(f"{path}.provenance: must be an object")
            else:
                if provenance.get("source_kind") not in SOURCE_KINDS:
                    errors.append(f"{path}.provenance.source_kind: invalid value")
                if provenance.get("confidence") not in CONFIDENCE_LEVELS:
                    errors.append(f"{path}.provenance.confidence: invalid value")
                imported = provenance.get("imported_at_jst")
                if not isinstance(imported, str) or not EXACT_JST.fullmatch(imported):
                    errors.append(f"{path}.provenance.imported_at_jst: invalid timestamp")
        precision = record.get("deadline_precision") or record.get("due_precision")
        date_value = record.get("deadline_jst") or record.get("due_jst")
        if precision is not None:
            if precision not in DEADLINE_PRECISIONS:
                errors.append(f"{path}: invalid date precision {precision!r}")
            elif date_value:
                valid = EXACT_JST.fullmatch(date_value) if precision == "exact" else DATE_ONLY.fullmatch(date_value) if precision == "date_only" else False
                if not valid:
                    errors.append(f"{path}: date does not match precision {precision}")

    for index, document in enumerate(state.get("documents", [])) if isinstance(state.get("documents"), list) else []:
        if not isinstance(document, dict):
            continue
        path = document.get("path") or document.get("file_path")
        if path:
            match = DOCUMENT_PATH.fullmatch(path)
            if not match or match.group(1) != record_id("documents", document):
                errors.append(f"$.documents[{index}]: invalid immutable document path")
        checksum = document.get("checksum_sha256")
        if checksum and (not isinstance(checksum, str) or not SHA256.fullmatch(checksum)):
            errors.append(f"$.documents[{index}].checksum_sha256: invalid SHA-256")

    _walk_forbidden(state, "$", errors)
    errors.extend(scan_secrets(state))
    return errors


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
    print(f"OK: state v{SCHEMA_VERSION} revision {state['revision']} is valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

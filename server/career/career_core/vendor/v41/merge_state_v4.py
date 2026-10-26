#!/usr/bin/env python3
"""Deterministic revision-aware patching and three-way merge for state v4."""

from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path
from typing import Any

from .state_v4 import COLLECTION_PREFIXES, PATCH_TYPE, ROOT_PATCH_FIELDS, SCHEMA_VERSION, deterministic_dumps, record_id
from .coaching_state import prepare_update, require_valid
from .validate_state_v4 import valid_date


class StaleRevisionError(ValueError):
    pass


def _maps(state: dict[str, Any], collection: str) -> dict[str, dict[str, Any]]:
    return {record_id(collection, item): copy.deepcopy(item) for item in state[collection]}


def apply_patch(state: dict[str, Any], patch: dict[str, Any], updated_at_jst: str) -> dict[str, Any]:
    require_valid(state)
    if not valid_date(updated_at_jst, exact=True):
        raise ValueError("invalid update timestamp")
    if not isinstance(patch, dict) or patch.get("patch_type") != PATCH_TYPE or patch.get("schema_version") != SCHEMA_VERSION:
        raise ValueError("invalid v4 patch envelope")
    if set(patch) != {"patch_type", "schema_version", "base_revision", "operations"}:
        raise ValueError("patch envelope fields must match schema")
    if not isinstance(patch.get("base_revision"), int) or isinstance(patch["base_revision"], bool):
        raise ValueError("base_revision must be an integer")
    if patch.get("base_revision") != state.get("revision"):
        raise StaleRevisionError("stale base_revision; refetch before applying")
    if not isinstance(patch.get("operations"), list):
        raise ValueError("operations must be an array")
    result = copy.deepcopy(state)
    for operation in patch["operations"]:
        if not isinstance(operation, dict):
            raise ValueError("operation must be an object")
        if operation.get("op") == "update_root_fields":
            if set(operation) != {"op", "field", "values"} or not isinstance(operation.get("field"), str) or operation["field"] not in ROOT_PATCH_FIELDS or not isinstance(operation.get("values"), dict):
                raise ValueError("root operation supports only candidate/last_session field updates")
            result[operation["field"]].update(copy.deepcopy(operation["values"]))
            continue
        if operation.get("op") != "upsert_record" or set(operation) != {"op", "collection", "record"}:
            raise ValueError("only upsert_record and update_root_fields operations are supported")
        collection = operation.get("collection")
        record = operation.get("record")
        if not isinstance(collection, str) or collection not in COLLECTION_PREFIXES or not isinstance(record, dict):
            raise ValueError("invalid patch collection or record")
        rid = record_id(collection, record)
        if not rid:
            raise ValueError("record ID is required")
        mapped = _maps(result, collection)
        mapped[rid] = copy.deepcopy(record)
        result[collection] = list(mapped.values())
    result = prepare_update(state, result)
    if result == state:
        return copy.deepcopy(state)
    result["revision"] = state["revision"] + 1
    result["updated_at_jst"] = updated_at_jst
    require_valid(result)
    return json.loads(deterministic_dumps(result))


def _changed_fields(base: dict[str, Any] | None, newer: dict[str, Any] | None) -> set[str]:
    keys = set(base or {}) | set(newer or {})
    missing = object()
    return {key for key in keys if (base or {}).get(key, missing) != (newer or {}).get(key, missing)}


def three_way_merge(
    base: dict[str, Any], latest: dict[str, Any], incoming: dict[str, Any], updated_at_jst: str
) -> tuple[dict[str, Any], list[dict[str, str]]]:
    """Merge incoming changes made from base into latest, reporting same-field conflicts."""
    for value in (base, latest, incoming):
        require_valid(value)
    if not valid_date(updated_at_jst, exact=True):
        raise ValueError("invalid update timestamp")
    if latest.get("revision", 0) < base.get("revision", 0):
        raise StaleRevisionError("latest revision predates base")
    result = copy.deepcopy(latest)
    conflicts: list[dict[str, str]] = []
    for collection in COLLECTION_PREFIXES:
        base_map = _maps(base, collection)
        latest_map = _maps(latest, collection)
        incoming_map = _maps(incoming, collection)
        result_map = _maps(result, collection)
        for rid in sorted(set(base_map) | set(incoming_map)):
            base_record = base_map.get(rid)
            incoming_record = incoming_map.get(rid)
            if base_record is not None and incoming_record is None:
                raise ValueError("record deletion is not supported; use a retired/cancelled status")
            if base_record is not None and rid not in latest_map:
                raise ValueError("latest state removed a record; reconcile explicitly before merging")
            incoming_changes = _changed_fields(base_record, incoming_record)
            if not incoming_changes:
                continue
            latest_record = latest_map.get(rid)
            latest_changes = _changed_fields(base_record, latest_record)
            overlap = incoming_changes & latest_changes
            for field in sorted(overlap):
                missing = object()
                if (incoming_record or {}).get(field, missing) != (latest_record or {}).get(field, missing):
                    conflicts.append({"collection": collection, "record_id": rid, "field": field})
            if any(item["record_id"] == rid and item["collection"] == collection for item in conflicts):
                continue
            merged_record = copy.deepcopy(latest_record or {})
            if incoming_record is None:
                raise ValueError("record deletion is not supported; use a retired/cancelled status")
            for field in incoming_changes:
                if field in incoming_record:
                    merged_record[field] = copy.deepcopy(incoming_record[field])
                else:
                    merged_record.pop(field, None)
            result_map[rid] = merged_record
        result[collection] = list(result_map.values())
    for root_field in sorted(ROOT_PATCH_FIELDS):
        incoming_changes = _changed_fields(base[root_field], incoming[root_field])
        latest_changes = _changed_fields(base[root_field], latest[root_field])
        for field in sorted(incoming_changes):
            if field not in incoming[root_field]:
                raise ValueError("root field deletion is not supported; use an explicit retired decision")
            if field in latest_changes and (field not in latest[root_field] or incoming[root_field][field] != latest[root_field][field]):
                conflicts.append({"collection": root_field, "record_id": "$", "field": field})
            else:
                result[root_field][field] = copy.deepcopy(incoming[root_field][field])
    if conflicts:
        return latest, conflicts
    result = prepare_update(latest, result)
    if result != latest:
        result["revision"] = latest["revision"] + 1
        result["updated_at_jst"] = updated_at_jst
    require_valid(result)
    return json.loads(deterministic_dumps(result)), []


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base", type=Path)
    parser.add_argument("latest", type=Path)
    parser.add_argument("incoming", type=Path)
    parser.add_argument("--updated-at-jst", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    values = [json.loads(path.read_text(encoding="utf-8")) for path in (args.base, args.latest, args.incoming)]
    merged, conflicts = three_way_merge(*values, args.updated_at_jst)
    if conflicts:
        print(json.dumps({"conflicts": conflicts}, ensure_ascii=False, sort_keys=True, indent=2))
        return 3
    args.output.write_text(deterministic_dumps(merged), encoding="utf-8")
    print(f"Merged state revision {merged['revision']}: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

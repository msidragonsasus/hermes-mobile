"""Read-only compatibility preview; no migration apply endpoint exists."""
import copy

from .vendor.v41.migrate_state_v40 import load_state_compatible
from .vendor.v41.state_v4 import ID_FIELDS


def preview(state):
    source = copy.deepcopy(state)
    aliases = []
    if source.get("schema_version") == "4.0":
        for collection, canonical in ID_FIELDS.items():
            for index, record in enumerate(source.get(collection, [])):
                if "id" in record and canonical not in record:
                    record[canonical] = record.pop("id")
                    aliases.append({"path": f"$.{collection}[{index}]", "reason": "legacy_id_alias"})
        for fact in source.get("facts", []):
            if "statement" in fact and "claim" not in fact:
                fact["claim"] = fact.pop("statement")
    candidate, report = load_state_compatible(source)
    report["changes"] = aliases + report["changes"]
    return {"preview_only": True, "report": report, "candidate": candidate,
            "compatibility": "既存ChatGPT側との互換確認と個別承認後に別途反映。ここでは変更しません。"}

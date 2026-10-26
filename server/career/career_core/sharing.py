"""Deterministic shared self-analysis and durable, versioned context patches."""
import copy
import hashlib
import json
import re

from .context_bundle import select_context, validate_context_bundle
from .store import CareerError, SHARED, encoded, now

CURRENT_FORMAT = "jobhunt-current/1"
GENERATOR_VERSION = 1
CURRENT_JSON = ".jobhunt/current.json"
CURRENT_MD = ".jobhunt/current.md"
SECTIONS = ("generation_guards", "corrections", "open_questions", "answered_topics")


def blob_sha(text):
    raw = text.encode("utf-8")
    return hashlib.sha1(b"blob " + str(len(raw)).encode("ascii") + b"\0" + raw).hexdigest()


def build_current(state, bundle, source_hashes, source_commit):
    material, _ = select_context(state, "exploration", bundle=bundle)
    result = {"format": CURRENT_FORMAT, "generator_version": GENERATOR_VERSION,
              "source_revision": state["revision"], "source_commit": source_commit,
              "source_hashes": dict(sorted(source_hashes.items())),
              "material": material, "context": copy.deepcopy(bundle)}
    # Preserve withdrawn originals for audit without making them eligible evidence.
    excluded = set(material.get("excluded_ids", []))
    result["excluded_history"] = {kind: [copy.deepcopy(item) for item in state.get(kind, [])
        if (item.get("id") or item.get({"hypotheses": "hypothesis_id", "stories": "story_id", "documents": "document_id"}[kind])) in excluded]
        for kind in ("hypotheses", "stories", "documents")}
    result["content_hash"] = hashlib.sha256(encoded(result).encode("utf-8")).hexdigest()
    return result


def render_current(value):
    """Exact JSON records retain uncertainty/provenance without model summarisation."""
    lines = ["# 現在の自己分析データ", "", "自動生成。原本のstate・訂正を更新してください。",
             "本人の直接発言とAI仮説を区別し、訂正・利用制限を先に確認してください。",
             "", f"- revision: {value['source_revision']}", f"- source commit: {value['source_commit']}",
             f"- content hash: {value['content_hash']}", "",
             "## 訂正・利用制限・回答済み事項・再開位置", "", "```json",
             json.dumps(value["context"], ensure_ascii=False, indent=2, sort_keys=True), "```", "",
             "## 本人情報と素材（仮説・履歴・未確認事項を含む）", "", "```json",
             json.dumps(value["material"], ensure_ascii=False, indent=2, sort_keys=True), "```", "",
             "## 撤回・利用禁止の原記録（根拠として再利用しない）", "", "```json",
             json.dumps(value["excluded_history"], ensure_ascii=False, indent=2, sort_keys=True), "```", "",
             "## 原本のハッシュ", "", "```json",
             json.dumps(value["source_hashes"], ensure_ascii=False, indent=2, sort_keys=True), "```", ""]
    return "\n".join(lines)


def record_id(record):
    if isinstance(record, dict):
        return record.get("id")
    if isinstance(record, str):
        return "ANSWER-" + hashlib.sha256(record.encode("utf-8")).hexdigest()[:16]


def records(bundle):
    return {(section, record_id(item)): copy.deepcopy(item)
            for section in SECTIONS for item in (bundle or {}).get(section, []) if record_id(item)}


def update_versions(store, db, bundle):
    versions = store.meta(db, "jobhunt_context_versions", {})
    for (section, entity_id), data in records(bundle).items():
        key = section + ":" + entity_id
        digest = hashlib.sha256(encoded(data).encode("utf-8")).hexdigest()
        previous = versions.get(key, {})
        if previous.get("hash") != digest:
            versions[key] = {"version": previous.get("version", 0) + 1, "hash": digest}
    store.set_meta(db, "jobhunt_context_versions", versions)
    return versions


def context_records(bundle, versions):
    return [{"section": section, "id": entity_id,
             "version": versions.get(section + ":" + entity_id, {}).get("version", 0), "data": data}
            for (section, entity_id), data in records(bundle).items()]


def merge_pending(bundle, pending, state, merge_fields):
    merged = copy.deepcopy(bundle)
    current = records(bundle)
    conflicts = []
    for key, change in pending.items():
        section, entity_id = change["section"], change["id"]
        remote = current.get((section, entity_id))
        value, fields = merge_fields(change.get("base"), change["data"], remote)
        if fields:
            conflicts.append({"id": key, "kind": "self_analysis_context", "section": section,
                              "entity_id": entity_id, "fields": fields, "base": change.get("base"),
                              "local": change["data"], "remote": remote})
            continue
        merged[section] = [item for item in merged.get(section, []) if record_id(item) != entity_id]
        if value is not None:
            merged[section].append(value)
    if not conflicts:
        merged["snapshot_state_revision"] = state["revision"]
        merged = validate_context_bundle(merged, state)
    return merged, conflicts


def align_fact_corrections(bundle, previous, updated):
    """A quoted fact edit also updates its exact-statement sidecar in the same commit."""
    if not bundle:
        return bundle
    result = copy.deepcopy(bundle)
    def facts(state):
        return {(item.get("id") or item.get("fact_id")): item for item in state.get("facts", [])}
    old, new = facts(previous), facts(updated)
    for correction in result.get("corrections", []):
        entity_id = correction.get("record_id")
        prior, current = old.get(entity_id), new.get(entity_id)
        if not prior or not current:
            continue
        before = prior.get("statement", prior.get("claim"))
        after = current.get("statement", current.get("claim"))
        if before == after or correction.get("current_statement") != before:
            continue
        history = correction.setdefault("statement_history", [])
        history.append({"statement": before, "provenance": copy.deepcopy(prior.get("provenance"))})
        correction["current_statement"] = after
        correction["provenance"] = copy.deepcopy(current.get("provenance"))
    return result


def save_context_patch(store, db, request, proof):
    """No deletion or implicit promotion of a statement to a personality fact."""
    section = request["data"].get("section")
    record = copy.deepcopy(request["data"].get("record"))
    if section not in SECTIONS or not isinstance(record, dict) or record.get("id") != request["id"]:
        raise CareerError("invalid_context_patch", "訂正・制約・質問のIDを確認してください。", 409)
    prefixes = {"generation_guards": "GUARD", "corrections": "COR", "open_questions": "OQ", "answered_topics": "ANSWER"}
    if not re.fullmatch(prefixes[section] + r"-[A-Za-z0-9-]+", request["id"]):
        raise CareerError("invalid_context_patch", "訂正・制約・質問のIDを確認してください。", 409)
    bundle = store.meta(db, "jobhunt_context_bundle")
    if not bundle:
        raise CareerError("sync_required", "訂正・制約を読み込んでから保存してください。", 409)
    key = section + ":" + request["id"]
    versions = store.meta(db, "jobhunt_context_versions", {})
    version = versions.get(key, {}).get("version", 0)
    if type(request.get("expected_version")) is not int or request["expected_version"] != version:
        raise CareerError("version_conflict", "訂正・制約が更新されています。最新の版を確認してください。", 409)
    record["provenance"] = {"source_kind": "CURRENT_USER_STATEMENT", "source_ref":
                            "hermes-career:message:" + proof["message_id"], "quote": proof["quote"]}
    # Reuse the established sensitive/credential detector, including newly added fields.
    from .vendor.v40.state_v4 import scan_secrets
    from .vendor.v40.validate_state_v4 import _walk_forbidden
    unsafe = []
    _walk_forbidden(record, "$", unsafe)
    if unsafe or scan_secrets(record):
        raise CareerError("unsafe_context_patch", "秘密情報を保存できません。", 409)
    pending = store.meta(db, "jobhunt_context_pending", {})
    remote_bundle = store.meta(db, "jobhunt_context_base", bundle)
    current = records(remote_bundle).get((section, request["id"]))
    change = {"section": section, "id": request["id"], "data": record,
              "base": pending.get(key, {}).get("base", current)}
    from .shared import current_state
    from .sync import merge_fields
    proposed = copy.deepcopy(pending)
    proposed[key] = change
    local, conflicts = merge_pending(remote_bundle, proposed, current_state(store, db), merge_fields)
    if conflicts:
        raise CareerError("context_conflict", "同じ訂正・制約に異なる更新があります。双方を確認してください。", 409)
    store.set_meta(db, "jobhunt_context_pending", proposed)
    versions[key] = {"version": version + 1, "hash": hashlib.sha256(encoded(record).encode("utf-8")).hexdigest()}
    store.set_meta(db, "jobhunt_context_versions", versions)
    store.set_meta(db, "jobhunt_context_bundle", local)
    store.set_meta(db, "sync_status", {"state": "unsynced", "reason": "context_pending", "checked_at": now()})
    return {"kind": "self_analysis_context", "id": request["id"], "version": version + 1,
            "server_saved": True, "github_synced": False}


def resolve_context_conflict(store, db, request):
    conflicts = store.meta(db, "jobhunt_context_conflicts", [])
    conflict = next((item for item in conflicts if item["id"] == request["id"]), None)
    if not conflict or request.get("choice") not in {"local", "remote"}:
        raise CareerError("conflict_changed", "競合情報を読み直してください。", 409)
    versions = store.meta(db, "jobhunt_context_versions", {})
    version = versions.get(conflict["id"], {}).get("version", 0)
    if type(request.get("expected_version")) is not int or request["expected_version"] != version:
        raise CareerError("version_conflict", "訂正・制約の版を確認してください。", 409)
    pending = store.meta(db, "jobhunt_context_pending", {})
    if request["choice"] == "local":
        pending[conflict["id"]]["base"] = conflict["remote"]
    else:
        pending.pop(conflict["id"], None)
    bundle = store.meta(db, "jobhunt_context_base")
    section, entity_id = conflict["section"], conflict["entity_id"]
    bundle[section] = [item for item in bundle.get(section, []) if record_id(item) != entity_id]
    if conflict["remote"] is not None:
        bundle[section].append(conflict["remote"])
    store.set_meta(db, "jobhunt_context_base", bundle)
    store.set_meta(db, "jobhunt_context_pending", pending)
    store.set_meta(db, "jobhunt_context_conflicts", [item for item in conflicts if item != conflict])
    store.set_meta(db, "sync_status", {"state": "unsynced", "reason": "conflict_resolved", "checked_at": now()})
    # The next sync rechecks the branch; this choice never bypasses GitHub CAS.
    return {"resolved": request["id"], "state": "unsynced"}


def consultation_context(pinned, purpose, scope=None):
    """Last verified material may inform advice, never unverified external generation."""
    cached = pinned.get("cached_reference")
    if pinned["status"]["state"] != "verified" and cached and purpose in {"operations", "exploration"}:
        material, policy = select_context(cached["state"], purpose, scope=scope, bundle=cached["bundle"], status={"state": "verified"})
        policy.update(state="cached_reference", external_generation_allowed=False, source=pinned["status"],
                      message="最新の同期は未確認です。前回確認済みの本人情報・訂正を参考として相談を続けます。外部提出用の生成・企業別推薦は保留します。")
        return material, policy
    return select_context(pinned["state"], purpose, scope=scope, bundle=pinned["bundle"], status=pinned["status"])


def pin_run_context(coach, run):
    from .shared import current_state
    with coach.store.transaction() as db:
        state = current_state(coach.store, db)
        status = coach.store.meta(db, "jobhunt_context_status", {"state": "not_imported"})
        sync = coach.store.sharing_status(db)
        fresh = sync.get("state") == "synced" and status.get("state") == "verified"
        bundle = coach.store.meta(db, "jobhunt_context_bundle") if fresh else None
        used = {"state": "verified" if fresh else "unavailable",
                "source_revision": state.get("revision") if fresh else None,
                "source_commit": status.get("commit") if fresh else None,
                "content_hash": status.get("content_hash") if fresh else None, "checked_at": now()}
        if not fresh:
            state = dict(state, candidate={}, facts=[], hypotheses=[], stories=[], documents=[],
                         decisions=[], sources=[], last_session={})
        entities = [dict(row, data=json.loads(row["data"])) for row in db.execute("SELECT * FROM entities")
                    if fresh and row["kind"] in SHARED]
        bodies = {row["path"]: row["body"] for row in db.execute("SELECT path,body FROM document_files")} if fresh else {}
        cached = None
        if not fresh and status.get("state") == "verified" and sync.get("state") != "conflict" and not coach.store.meta(db, "jobhunt_context_conflicts", []):
            prior_state = coach.store.meta(db, "github_base")
            prior_bundle = coach.store.meta(db, "jobhunt_context_base")
            if isinstance(prior_state, dict) and isinstance(prior_bundle, dict):
                cached = {"state": prior_state, "bundle": prior_bundle}
        pinned = {"state": state, "bundle": bundle, "status": used, "entities": entities, "document_bodies": bodies,
                  "cached_reference": cached, "versions": coach.store.meta(db, "jobhunt_context_versions", {})}
        coach.store.set_meta(db, "coach_shared_snapshot:" + run["id"], pinned)
    coach.progress(run["id"], "context_used", used)
    return pinned

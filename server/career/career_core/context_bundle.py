"""Conservative read-only projection of ChatGPT career context sidecars."""
import copy
import json
import re

from .store import CareerError
from .vendor.v41.coaching_state import select_context as select_v41_context

FORMAT = "jobhunt-self-analysis-context/1"
PURPOSES = {"operations", "exploration", "strategy", "creation", "practice"}
EXTERNAL_PURPOSES = {"strategy", "creation", "practice"}
DISPOSITIONS = {
    "do_not_use",
    "withdrawn_assistant_inference",
    "unsupported",
    "not_yet_verified",
    "hold_for_separate_review",
    "unconfirmed_assistant_proposal",
    "user_reported_totals_only_causality_unverified",
    "not_verified_do_not_promote_to_fact",
    "use_user_confirmed_chronology_only",
}
ID = re.compile(r"(?:FACT|HYP|ST|DOC)-[A-Za-z0-9-]+")


def _bounded_json(value, maximum=512 * 1024):
    try:
        size = len(json.dumps(value, ensure_ascii=False).encode("utf-8"))
    except (TypeError, ValueError):
        raise CareerError("invalid_context_bundle", "就活コンテクストの形式を確認してください。", 409) from None
    if size > maximum:
        raise CareerError("context_bundle_too_large", "就活コンテクストの容量を確認してください。", 409)


def validate_manifest(manifest, state):
    if not isinstance(manifest, dict) or manifest.get("repository_marker") != "jp-job-hunting-coach-state":
        raise CareerError("invalid_career_manifest", "就活リポジトリの識別情報を確認してください。", 409)
    if manifest.get("privacy_mode") != "private-repo" or manifest.get("state_path") != ".jobhunt/state.json":
        raise CareerError("invalid_career_manifest", "就活リポジトリの保存境界を確認してください。", 409)
    if manifest.get("schema_version") != state.get("schema_version"):
        raise CareerError("career_schema_mismatch", "manifestとstateの版が一致しません。", 409)
    consents = manifest.get("consents")
    if not isinstance(consents, dict) or consents.get("github_state_read") is not True:
        raise CareerError("career_read_not_consented", "GitHub上の就活状態を読み取る同意を確認してください。", 409)


def validate_context_bundle(value, state):
    _bounded_json(value)
    if not isinstance(value, dict) or value.get("format") != FORMAT:
        raise CareerError("unsupported_context_bundle", "未対応の就活コンテクストです。", 409)
    revision = value.get("snapshot_state_revision")
    if type(revision) is not int or revision < 1 or revision > state.get("revision", 0):
        raise CareerError("context_revision_mismatch", "就活コンテクストとstateの版を確認してください。", 409)
    boundaries = value.get("source_boundaries")
    if not isinstance(boundaries, dict) or boundaries.get("private_only") is not True:
        raise CareerError("unsafe_context_boundary", "private専用の就活コンテクストだけを取り込めます。", 409)
    if boundaries.get("raw_transcript_stored") is not False or boundaries.get("sensitive_details_stored") is not False:
        raise CareerError("unsafe_context_boundary", "生会話や機微情報を含むコンテクストは取り込めません。", 409)

    known = {
        record.get("id") or record.get(field)
        for collection, field in (
            ("facts", "fact_id"),
            ("hypotheses", "hypothesis_id"),
            ("stories", "story_id"),
            ("documents", "document_id"),
        )
        for record in state.get(collection, [])
        if isinstance(record, dict)
    }
    guards = value.get("generation_guards")
    if not isinstance(guards, list) or not 1 <= len(guards) <= 100:
        raise CareerError("invalid_context_guards", "再利用制限を確認してください。", 409)
    guard_ids = set()
    clean_guards = []
    for guard in guards:
        if not isinstance(guard, dict) or not re.fullmatch(r"GUARD-[A-Za-z0-9-]+", str(guard.get("id", ""))):
            raise CareerError("invalid_context_guards", "再利用制限のIDを確認してください。", 409)
        if guard["id"] in guard_ids or guard.get("disposition") not in DISPOSITIONS:
            raise CareerError("invalid_context_guards", "再利用制限の状態を確認してください。", 409)
        if not isinstance(guard.get("scope"), list) or not all(isinstance(item, str) and item for item in guard["scope"]):
            raise CareerError("invalid_context_guards", "再利用制限の適用範囲を確認してください。", 409)
        references = list(guard.get("record_ids", [])) + list(guard.get("story_ids", []))
        if any(not isinstance(item, str) or not ID.fullmatch(item) or item not in known for item in references):
            raise CareerError("invalid_context_guards", "再利用制限の参照先を確認してください。", 409)
        for field in ("topic", "reason"):
            if not isinstance(guard.get(field), str) or not guard[field] or len(guard[field]) > 4000:
                raise CareerError("invalid_context_guards", "再利用制限の説明を確認してください。", 409)
        guard_ids.add(guard["id"])
        clean_guards.append(copy.deepcopy(guard))

    facts = {record.get("id") or record.get("fact_id"): record for record in state.get("facts", [])}
    clean_corrections = []
    for correction in value.get("corrections", []):
        record_id = correction.get("record_id") if isinstance(correction, dict) else None
        current = correction.get("current_statement") if isinstance(correction, dict) else None
        fact = facts.get(record_id)
        claim = fact.get("statement", fact.get("claim")) if isinstance(fact, dict) else None
        references = correction.get("record_ids") if isinstance(correction, dict) else None
        if references is not None:
            allowed = known | guard_ids
            if (not isinstance(references, list) or not references or
                    any(not isinstance(item, str) or item not in allowed for item in references) or
                    not isinstance(current, str) or not current.strip()):
                raise CareerError("context_correction_mismatch", "訂正内容の参照先を確認してください。", 409)
        elif not isinstance(record_id, str) or not isinstance(current, str) or current != claim:
            raise CareerError("context_correction_mismatch", "訂正内容とstateの現行記述が一致しません。", 409)
        clean_corrections.append(copy.deepcopy(correction))

    answered = value.get("answered_topics", [])
    open_questions = value.get("open_questions", [])
    if not isinstance(answered, list) or any(
            not (isinstance(item, str) and item or isinstance(item, dict) and
                 isinstance(item.get("id"), str) and isinstance(item.get("topic"), str) and item["topic"])
            for item in answered):
        raise CareerError("invalid_context_progress", "回答済み論点を確認してください。", 409)
    if not isinstance(open_questions, list) or any(not isinstance(item, dict) for item in open_questions):
        raise CareerError("invalid_context_progress", "未回答論点を確認してください。", 409)

    return dict(copy.deepcopy(value), **{
        "format": FORMAT,
        "snapshot_state_revision": revision,
        "imported_on": value.get("imported_on"),
        "source_id": value.get("source_id"),
        "source_boundaries": copy.deepcopy(boundaries),
        "generation_guards": clean_guards,
        "corrections": clean_corrections,
        "metrics": copy.deepcopy(value.get("metrics", [])),
        "artifact_ownership": copy.deepcopy(value.get("artifact_ownership", [])),
        "answered_topics": copy.deepcopy(answered),
        "open_questions": copy.deepcopy(open_questions),
        "assessment_policy": copy.deepcopy(value.get("assessment_policy", {})),
        "source_contexts": copy.deepcopy(value.get("source_contexts", [])),
    })


def choose_context_bundle(values, state, entry_path=None):
    """Resolve an explicit additive ancestry before validating cross-file references."""
    if not isinstance(values, dict):
        values = {str(index): value for index, value in enumerate(values)}
    candidates = {path: value for path, value in values.items()
                  if isinstance(value, dict) and value.get("format") == FORMAT}
    if not candidates:
        if entry_path:
            raise CareerError("context_base_missing", "訂正の参照先が見つかりません。", 409)
        return None
    if entry_path is None:
        entry_path = max(candidates, key=lambda path: (
            candidates[path].get("snapshot_state_revision", 0), str(candidates[path].get("imported_on") or ""), path))
    chain, seen = [], set()
    def visit(path):
        if path in seen or len(seen) >= 100:
            raise CareerError("context_base_cycle", "訂正の参照関係を確認してください。", 409)
        value = candidates.get(path)
        if value is None:
            raise CareerError("context_base_missing", "訂正の参照先が見つかりません。", 409)
        seen.add(path)
        if value.get("base_context_path"):
            visit(value["base_context_path"])
        chain.append((path, value))
    visit(entry_path)
    merged = {}
    for path, value in chain:
        _bounded_json(value, 1024 * 1024)
        revision = value.get("snapshot_state_revision")
        if type(revision) is not int or not 1 <= revision <= state.get("revision", 0):
            raise CareerError("context_revision_mismatch", "就活コンテクストとstateの版を確認してください。", 409)
        boundaries = value.get("source_boundaries", {})
        if (boundaries.get("private_only") is not True or boundaries.get("raw_transcript_stored") is not False
                or boundaries.get("sensitive_details_stored") is not False):
            raise CareerError("unsafe_context_boundary", "就活コンテクストの保存境界を確認してください。", 409)
        for key, item in value.items():
            if key in {"base_context_path", "turn_locators", "source_contexts"}:
                continue
            if key in {"generation_guards", "corrections", "metrics", "artifact_ownership", "open_questions", "answered_topics"}:
                if not isinstance(item, list):
                    raise CareerError("invalid_context_bundle", "就活コンテクストの一覧を確認してください。", 409)
                old = merged.setdefault(key, [])
                ids = [record.get("id") for record in item if isinstance(record, dict) and record.get("id")]
                if len(ids) != len(set(ids)):
                    raise CareerError("duplicate_context_id", "訂正・制約のIDが重複しています。", 409)
                for record in item:
                    identity = record.get("id") if isinstance(record, dict) else None
                    if identity:
                        old[:] = [prior for prior in old if not isinstance(prior, dict) or prior.get("id") != identity]
                    if record not in old:
                        old.append(copy.deepcopy(record))
            elif key == "assessment_policy":
                policy = merged.setdefault(key, {})
                for field, setting in item.items():
                    if isinstance(setting, list):
                        policy[field] = list(dict.fromkeys(policy.get(field, []) + setting))
                    else:
                        policy[field] = copy.deepcopy(setting)
            else:
                merged[key] = copy.deepcopy(item)
        for source in value.get("source_contexts", []):
            if isinstance(source, dict) and source not in merged.setdefault("source_contexts", []):
                merged["source_contexts"].append(copy.deepcopy(source))
        merged.setdefault("source_contexts", []).append({
            "path": path, "source_id": value.get("source_id"), "snapshot_state_revision": revision,
            "turn_locators": copy.deepcopy(value.get("turn_locators", {}))})
    return validate_context_bundle(merged, state)


def constraints(bundle, purpose, status=None):
    if purpose not in PURPOSES:
        raise ValueError("unknown dialogue purpose")
    if not bundle or status and status.get("state") not in {"verified", "fresh"}:
        return {
            "state": "missing",
            "external_generation_allowed": purpose not in EXTERNAL_PURPOSES,
            "message": "訂正・再利用制限を確認できません。ES・面接回答・企業別推薦には進みません。",
            "generation_guards": [],
            "blocked_ids": [],
        }
    blocked = set()
    guarded_facts = set()
    for guard in bundle["generation_guards"]:
        record_ids = set(guard.get("record_ids", []))
        guarded_facts.update(item for item in record_ids if item.startswith("FACT-"))
        if guard["disposition"] in {"do_not_use", "withdrawn_assistant_inference"}:
            blocked.update(item for item in record_ids if item.startswith(("HYP-", "ST-", "DOC-")))
        if guard["disposition"] == "hold_for_separate_review" and purpose in EXTERNAL_PURPOSES:
            blocked.update(guard.get("story_ids", []))
    withdrawn = bundle.get("assessment_policy", {}).get("withdrawn", [])
    blocked.update(item for item in withdrawn if isinstance(item, str) and ID.fullmatch(item))
    return {
        "state": "verified",
        "external_generation_allowed": True,
        "snapshot_state_revision": bundle["snapshot_state_revision"],
        "source": copy.deepcopy(status or {}),
        "generation_guards": copy.deepcopy(bundle["generation_guards"]),
        "corrections": copy.deepcopy(bundle["corrections"]),
        "answered_topics": copy.deepcopy(bundle["answered_topics"]),
        "open_questions": copy.deepcopy(bundle["open_questions"]),
        "metrics": copy.deepcopy(bundle.get("metrics", [])),
        "artifact_ownership": copy.deepcopy(bundle.get("artifact_ownership", [])),
        "assessment_policy": copy.deepcopy(bundle.get("assessment_policy", {})),
        "blocked_ids": sorted(blocked),
        "guarded_fact_ids": sorted(guarded_facts),
    }


def _record_id(record):
    return next((record.get(field) for field in ("id", "fact_id", "hypothesis_id", "story_id", "document_id")
                 if record.get(field)), None)


def _legacy_context(state):
    if state.get("schema_version") != "4.0" or not isinstance(state.get("candidate"), dict):
        raise ValueError("invalid legacy state")
    for collection in ("facts", "hypotheses", "stories", "documents"):
        if not isinstance(state.get(collection), list):
            raise ValueError("invalid legacy collection")
    return {
        "candidate": copy.deepcopy(state["candidate"]),
        "candidate_is_evidence": False,
        "facts": copy.deepcopy(state["facts"]),
        "hypotheses": copy.deepcopy(state["hypotheses"]),
        "stories": copy.deepcopy(state["stories"]),
        "documents": copy.deepcopy(state["documents"]),
        "historical_documents": [copy.deepcopy(item) for item in state["documents"] if item.get("status") == "submitted"],
        "decisions": copy.deepcopy(state.get("decisions", [])),
        "last_session": copy.deepcopy(state.get("last_session", {})),
        "semantic_support_verified": False,
        "legacy_read_only": True,
    }


def select_context(state, purpose, *, scope=None, bundle=None, status=None):
    selected = select_v41_context(state, purpose, scope=scope) if state.get("schema_version") == "4.1" else _legacy_context(state)
    policy = constraints(bundle, purpose, status)
    blocked = set(policy["blocked_ids"])
    for collection in ("hypotheses", "stories", "documents", "historical_documents"):
        selected[collection] = [item for item in selected.get(collection, []) if _record_id(item) not in blocked]
    selected["excluded_ids"] = sorted(set(selected.get("excluded_ids", [])) | blocked)
    return selected, policy


def source_work(state):
    return {key: copy.deepcopy(state.get(key, [])) for key in
            ("target_companies", "applications", "tasks", "selection_events", "imports")}


def filter_entities(entities, blocked_ids):
    blocked = set(blocked_ids)
    return [entity for entity in entities if entity.get("id") not in blocked]

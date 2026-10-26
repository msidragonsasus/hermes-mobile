"""Coaching-compatible shared edits, corrections and immutable ES files."""
import copy
import hashlib
import json
import re
from datetime import datetime, timedelta, timezone

from .store import CareerError, SHARED, encoded
from .vendor.v40.state_v4 import initial_state
from .vendor.v40.validate_state_v4 import validate_state as validate40
from .vendor.v41.coaching_state import prepare_update
from .vendor.v41.validate_state_v4 import validate_state as validate41

PREFIXES = {"facts": "FACT", "stories": "ST", "hypotheses": "HYP", "documents": "DOC", "decisions": "DEC", "sources": "SRC"}
IDENTIFIERS = {"facts": "fact_id", "stories": "story_id", "hypotheses": "hypothesis_id", "documents": "document_id", "decisions": "decision_id", "sources": "source_id"}


def jst_now():
    return datetime.now(timezone(timedelta(hours=9))).strftime("%Y-%m-%d %H:%M JST")


def current_state(store, db):
    state = copy.deepcopy(store.meta(db, "github_base") or initial_state(jst_now()))
    state["candidate"] = {}
    for kind in SHARED - {"profile"}:
        state[kind] = []
    for row in db.execute("SELECT kind,data FROM entities"):
        if row["kind"] == "profile":
            state["candidate"] = json.loads(row["data"])
        elif row["kind"] in SHARED:
            state[row["kind"]].append(json.loads(row["data"]))
    return state


def validate_shared(state, previous=None):
    view = copy.deepcopy(state)
    if view["schema_version"] == "4.0":
        # Historical Chat-generated state uses `id`; validate a view without migrating the original.
        from .vendor.v40.state_v4 import ID_FIELDS
        for collection, id_field in ID_FIELDS.items():
            for record in view.get(collection, []):
                if "id" in record and id_field not in record:
                    record[id_field] = record["id"]
    errors = (validate41 if state["schema_version"] == "4.1" else validate40)(view)
    if previous and state["schema_version"] == previous.get("schema_version") == "4.0":
        # Grandfather only unchanged legacy fields. Credentials, forbidden fields,
        # dangling references, new records and new values always remain strict.
        retained = []
        for error in errors:
            match = re.match(r"^\$\.(\w+)\[(\d+)\](\.[\w.]+)?: (.+)$", error)
            compatible = False
            if match:
                kind, index, suffix, reason = match.groups()
                record = view.get(kind, [])[int(index)]
                entity_id = record.get("id") or record.get(IDENTIFIERS.get(kind, "id"))
                old = next((item for item in previous.get(kind, []) if
                            (item.get("id") or item.get(IDENTIFIERS.get(kind, "id"))) == entity_id), None)
                if old is not None and suffix and (suffix.startswith(".provenance.") or suffix == ".status"):
                    sentinel = object()
                    def field(value):
                        for key in suffix[1:].split("."):
                            value = value.get(key, sentinel) if isinstance(value, dict) else sentinel
                        return value
                    compatible = field(old) == field(record) and (
                        reason.startswith("invalid enum value") or reason in {"invalid value", "invalid timestamp"})
                if old is not None and not suffix and reason == "invalid immutable document path":
                    from .sync import GitHub
                    path = record.get("path") or record.get("file_path")
                    compatible = path == (old.get("path") or old.get("file_path")) and GitHub.valid_document_path(path)
                if old is not None and not suffix and reason.startswith("invalid or missing ") and reason.endswith(" ID"):
                    prefix = reason[len("invalid or missing "):-len(" ID")]
                    # Existing date-labelled import/document IDs are valid historical
                    # identities. New records still require the canonical numeric ID.
                    pattern = r"(?:IMP|IMPORT)" if kind == "imports" and prefix == "IMP" else re.escape(prefix)
                    compatible = isinstance(entity_id, str) and re.fullmatch(pattern + r"-[A-Za-z0-9][A-Za-z0-9._-]{0,159}", entity_id) is not None
            if not compatible:
                retained.append(error)
        errors = retained
    if errors:
        # Do not echo personal values or secret detector matches in HTTP/journal.
        raise CareerError("invalid_shared_state", {"message": "本人事実・出典・参照関係を確認してください。", "error_count": len(errors)}, 409)


def apply_shared_state(store, db, previous, updated):
    if updated["schema_version"] == "4.1":
        try:
            updated = prepare_update(previous, updated)
        except ValueError:
            raise CareerError("invalid_shared_transition", "訂正・出典・文書の版を確認してください。", 409) from None
    validate_shared(updated, previous=previous)
    for kind in SHARED:
        values = [("candidate", updated["candidate"])] if kind == "profile" else [
            (r.get("id") or r.get(IDENTIFIERS[kind]), r) for r in updated[kind]]
        for entity_id, data in values:
            old = store.entity(db, kind, entity_id)
            if not old or old["data"] != data:
                store.put(db, kind, entity_id, old["version"] if old else 0, data)


def next_id(state, kind):
    identifiers = [r.get("id") or r.get(IDENTIFIERS[kind]) or "" for r in state[kind]]
    suffix = max([int(value.split("-")[1]) for value in identifiers if re.fullmatch(PREFIXES[kind] + r"-\d+", value)] or [0]) + 1
    return f"{PREFIXES[kind]}-{suffix:03d}"


def check_expected(store, db, request):
    old = store.entity(db, request["kind"], request["id"])
    if (old["version"] if old else 0) != request["expected_version"]:
        raise CareerError("version_conflict", {"current": old}, 409)
    return old


def user_provenance(request):
    return {"source_kind": "CURRENT_USER_STATEMENT", "source_role": "USER_STATEMENT",
            "source_ref": request.get("source_ref", "hermes-mobile:operation:" + request["operation_id"]),
            "source_date": jst_now()[:10], "imported_at_jst": jst_now(), "confidence": "high"}


def save_shared(store, db, request, *, from_proposal=False):
    kind = request["kind"]
    if kind in {"documents", "decisions", "sources"}:
        raise CareerError("dedicated_action_required", "専用の文書・訂正操作を使ってください。")
    old = check_expected(store, db, request)
    data = copy.deepcopy(request["data"])
    previous = current_state(store, db)
    state = copy.deepcopy(previous)
    if kind == "profile":
        if request["id"] != "candidate":
            raise CareerError("invalid_profile", "プロフィールのIDが不正です。")
        state["candidate"] = data
    else:
        entity_id = data.get("id") or data.get(IDENTIFIERS[kind])
        if entity_id != request["id"]:
            raise CareerError("identity_changed", "レコードIDを変更できません。")
        if kind == "facts":
            if old and data != old["data"]:
                raise CareerError("correction_required", "本人事実の訂正では元の記述を保持します。", 409)
            confirmed = not from_proposal or request.get("confirmed_by_user") is True
            data["provenance"] = user_provenance(request) if confirmed else {
                "source_kind": "CHAT_HISTORY_SURFACED", "source_role": "MODEL_SUMMARY",
                "source_ref": "hermes-proposal:" + request["operation_id"], "source_date": "unknown",
                "imported_at_jst": jst_now(), "confidence": "low"}
            if state["schema_version"] == "4.1":
                data["fact_id"] = data.pop("id", request["id"])
                data["claim"] = data.pop("statement", data.get("claim", ""))
                data["classification"] = "CONFIRMED" if confirmed else "UNKNOWN"
                data.pop("status", None)
            else:
                data["status"] = "CONFIRMED" if confirmed else "UNKNOWN"
        state[kind] = [r for r in state[kind] if (r.get("id") or r.get(IDENTIFIERS[kind])) != request["id"]] + [data]
    apply_shared_state(store, db, previous, state)
    return store.entity(db, kind, request["id"])


def correct_fact(store, request, transaction=None):
    def apply(db):
        if request["kind"] != "facts":
            raise CareerError("invalid_correction", "訂正対象が不正です。")
        old = check_expected(store, db, request)
        if not old or old["data"].get("classification", old["data"].get("status")) == "SUPERSEDED":
            raise CareerError("invalid_correction", "訂正済みの記述は変更できません。", 409)
        previous = current_state(store, db)
        state = copy.deepcopy(previous)
        fact_id, decision_id = next_id(state, "facts"), next_id(state, "decisions")
        fresh = copy.deepcopy(request["data"])
        fresh.pop("id", None)
        fresh.pop("fact_id", None)
        fresh["fact_id" if state["schema_version"] == "4.1" else "id"] = fact_id
        fresh["provenance"] = user_provenance(request)
        classification = "classification" if state["schema_version"] == "4.1" else "status"
        fresh[classification] = "CONFIRMED"
        for record in state["facts"]:
            if (record.get("id") or record.get("fact_id")) == request["id"]:
                record[classification] = "SUPERSEDED"
                record["superseded_by_fact_id"] = fact_id
        state["facts"].append(fresh)
        state["decisions"].append({"decision_id" if state["schema_version"] == "4.1" else "id": decision_id,
            "kind": "FACT_CORRECTION", "scope": {"purpose": "all"}, "target_ids": [request["id"]],
            "replacement_fact_id": fact_id, "reason": request.get("correction_reason", "本人がMobileの差分画面で訂正を確認"), "provenance": user_provenance(request)})
        affected = {request["id"]}
        changed = True
        while changed:
            changed = False
            for kind in ("hypotheses", "stories", "documents"):
                for record in state[kind]:
                    entity_id = record.get("id") or record.get(IDENTIFIERS[kind])
                    if entity_id not in affected and any(ref in encoded(record) for ref in affected):
                        affected.add(entity_id)
                        if record.get("status") != "submitted":
                            record["needs_review"] = True
                            record["review_fact_ids"] = sorted(set(record.get("review_fact_ids", [])) | {request["id"]})
                            if record.get("status") == "ready":
                                record["status"] = "review"
                        changed = True
        apply_shared_state(store, db, previous, state)
        return {"replacement": store.entity(db, "facts", fact_id), "decision_id": decision_id}
    return apply(transaction) if transaction is not None else store.operation(request["operation_id"], request, apply)


def save_document(store, request, transaction=None):
    def apply(db):
        if request["kind"] != "documents":
            raise CareerError("invalid_document", "文書の種類が不正です。")
        old = check_expected(store, db, request)
        previous = current_state(store, db)
        state = copy.deepcopy(previous)
        data = copy.deepcopy(request["data"])
        body = data.pop("body", None)
        from .collection_store import document_context, bind_document
        old_context = document_context(db, request["id"], old["data"].get("version", 1)) if old else {}
        career_scope = request.get("career_scope") or old_context
        if old_context and any(career_scope.get(k) != old_context.get(k) for k in ("company_id", "offer_id")):
            raise CareerError("target_scope_conflict", "別の募集へ回答を移すことはできません。", 409)
        bound_questions = request.get("career_questions", old_context.get("question_refs", []))
        if old_context.get("question_refs") and [q.get("id") for q in bound_questions] != [q.get("id") for q in old_context["question_refs"]]:
            raise CareerError("target_scope_conflict", "別の設問へ回答を移すことはできません。", 409)
        if career_scope.get("offer_id") and bound_questions:
            from .es_work import question_context, validate_body, check_source
            if len(bound_questions) != 1:
                raise CareerError("question_required", "設問ごとに回答を保存してください。", 409)
            bound = bound_questions[0]
            offer, question = question_context(store, db, career_scope["offer_id"], bound["id"], bound.get("version"))
            if offer["data"]["company_id"] != career_scope.get("company_id"):
                raise CareerError("target_scope_conflict", "回答の企業が一致しません。", 409)
            validate_body(question, body)
            check_source(store, db, offer)
        for field in ("career_context", "company_id", "offer_id", "question_id", "question_refs", "evidence_id", "source_excerpt"):
            data.pop(field, None)
        data.pop("history_paths", None)
        if not isinstance(body, str) or not body.strip() or len(body.encode()) > 262144:
            raise CareerError("invalid_document", "本文を入力してください。")
        doc_id = request["id"]
        version = int(old["data"].get("version", 0)) + 1 if old else 1
        if old and old["data"].get("status") == "submitted":
            doc_id, version = next_id(state, "documents"), 1
            data["previous_document_id"] = request["id"]
            data["status"] = "drafting"
        path = f".jobhunt/documents/{doc_id}-v{version:03d}.md"
        checksum = hashlib.sha256(body.encode()).hexdigest()
        data.pop("id", None)
        data.pop("file_path", None)
        data["document_id" if state["schema_version"] == "4.1" else "id"] = doc_id
        data.update(path=path, version=version, checksum_sha256=checksum)
        # User enters evidence explicitly for 4.1. Never invent support from the model's text.
        if state["schema_version"] == "4.1":
            evidence = data.get("version_evidence", {})
            if path not in evidence:
                raise CareerError("document_evidence_required", "4.1文書には本人主張と事実IDの対応が必要です。", 409)
        state["documents"] = [r for r in state["documents"] if (r.get("id") or r.get("document_id")) != doc_id] + [data]
        validate_shared(state, previous=previous)
        db.execute("INSERT INTO document_files VALUES(?,?,?,0)", (path, body, checksum))
        apply_shared_state(store, db, previous, state)
        if career_scope.get("company_id"):
            bind_document(db, doc_id, version, career_scope, request.get("career_questions", old_context.get("question_refs", [])))
        return store.entity(db, "documents", doc_id)
    return apply(transaction) if transaction is not None else store.operation(request["operation_id"], request, apply)

"""Question-scoped editing using existing document versions and private metadata."""
import copy
import re

from .collection_store import CollectionStore, digest, document_context
from .shared import current_state, next_id, save_document
from .store import CareerError


def work_key(offer_id, question_id):
    return "es-work:" + digest(offer_id, question_id)


def question_context(store, db, offer_id, question_id, version=None):
    if not isinstance(offer_id, str) or not isinstance(question_id, str):
        raise CareerError("question_required", "対象の募集・設問を選んでください。")
    offer = store.entity(db, "offers", offer_id)
    company = store.entity(db, "companies", offer["data"].get("company_id")) if offer else None
    question = next((q for q in offer["data"].get("questions", []) if q["id"] == question_id), None) if offer else None
    if not question or not company:
        raise CareerError("question_missing", "設問が変更または削除されています。入力は保持しています。", 409)
    if version is not None and question["version"] != version:
        raise CareerError("question_version_conflict", "設問が更新されています。原文の差分を確認してください。", 409)
    return offer, question


def validate_body(question, body):
    if not isinstance(body, str) or not body.strip() or len(body) > 20000:
        raise CareerError("invalid_document", "回答本文を1〜20,000文字で入力してください。")
    if question.get("input_kind", "text") != "text":
        raise CareerError("manual_portal_field", "この項目はマイページで選択・添付してください。", 409)
    maximum = question.get("max_characters")
    if maximum and len(body) > maximum:
        raise CareerError("document_too_long", "字数上限を超えています。本文を短くしてください。", 409)
    if (question.get("no_line_breaks") or re.search(r"改行(?:を)?(?:使用|入力)?(?:せず|しない|禁止|不可)", question.get("text", ""))) and ("\n" in body or "\r" in body):
        raise CareerError("line_break_forbidden", "この設問は改行できません。改行を取り除いてください。", 409)


def check_source(store, db, offer):
    data = offer["data"]
    if data.get("collection_managed"):
        states = [CollectionStore(store).status(g["id"], "portal", db)
                  for g in CollectionStore(store).grants() if g["company_id"] == data["company_id"]]
        if data.get("portal_state") != "available" or data.get("questions_stale") or any(
            s.get("state") in {"auth_required", "failed"} for s in states
        ):
            raise CareerError("questions_not_verified", "原文の取得状況を確認してください。端末の入力は保持しています。", 409)


def check_document(store, db, doc_id, offer_id, question_id, version=None):
    entity = store.entity(db, "documents", doc_id)
    if not entity:
        raise CareerError("document_missing", "回答の保存版が見つかりません。", 409)
    selected = version if version is not None else entity["data"].get("version", 1)
    context = document_context(db, doc_id, selected)
    if context.get("offer_id") != offer_id or [q.get("id") for q in context.get("question_refs", [])] != [question_id]:
        raise CareerError("target_scope_conflict", "別の募集・設問の回答は変更できません。", 409)
    return entity


def answer_summary(store, db, offer_id):
    offer = store.entity(db, "offers", offer_id)
    return {q["id"]: store.meta(db, work_key(offer_id, q["id"]), {"revision": 0})
            for q in offer["data"].get("questions", [])}


def validate_consultation(store, db, context, scope):
    if not isinstance(context, dict) or set(context) - {"offer_id", "question_id", "question_version", "body", "document_id", "document_version"}:
        raise CareerError("invalid_answer_context", "相談する設問を選び直してください。")
    if scope.get("offer_id") != context.get("offer_id"):
        raise CareerError("target_scope_conflict", "相談する募集が異なります。", 409)
    if not isinstance(context.get("question_version"), str):
        raise CareerError("question_version_conflict", "設問の版を確認してください。", 409)
    offer, question = question_context(store, db, context.get("offer_id"), context.get("question_id"), context["question_version"])
    body = context.get("body", "")
    if not isinstance(body, str) or len(body) > 20000:
        raise CareerError("invalid_document", "相談する本文が長すぎます。")
    if context.get("document_id"):
        if type(context.get("document_version")) is not int or context["document_version"] < 1:
            raise CareerError("invalid_document", "相談する文書版を確認してください。")
        check_document(store, db, context["document_id"], context["offer_id"], context["question_id"], context["document_version"])
    # A too-long or empty draft can be discussed; validation applies at save time.
    return dict(context, company_id=offer["data"]["company_id"], question=question, body=body)


def mutate_answer(store, payload):
    def apply(db):
        if not isinstance(payload.get("question_version"), str):
            raise CareerError("question_version_conflict", "設問の版を確認してください。", 409)
        offer_id, question_id = payload.get("offer_id"), payload.get("question_id")
        offer, question = question_context(store, db, offer_id, question_id, payload["question_version"])
        key = work_key(offer_id, question_id)
        work = store.meta(db, key, {"revision": 0})
        if type(payload.get("expected_work_version")) is not int or payload["expected_work_version"] != work["revision"]:
            raise CareerError("answer_version_conflict", "採用中の回答が更新されています。入力を残して比較してください。", 409)
        intent = payload.get("intent", "save")
        doc_id = payload.get("document_id")
        doc_version = payload.get("document_version")
        if intent not in {"save", "adopt", "review"}:
            raise CareerError("invalid_action", "回答の操作が不正です。")
        if intent == "save":
            validate_body(question, payload.get("body"))
            check_source(store, db, offer)
            old = check_document(store, db, doc_id, offer_id, question_id) if doc_id else None
            if type(payload.get("expected_version")) is not int or payload["expected_version"] != (old["version"] if old else 0):
                raise CareerError("document_version_conflict", "新しい回答版があります。入力を残して比較してください。", 409)
            state = current_state(store, db)
            doc_id = doc_id or next_id(state, "documents")
            data = copy.deepcopy(old["data"]) if old else {"title": offer["data"].get("title", "ES") + " · " + question.get("field", "回答")}
            data.update(body=payload["body"], status="drafting", needs_review=True)
            if state["schema_version"] == "4.1":
                version = old["data"].get("version", 0) + 1 if old else 1
                if old and old["data"].get("status") == "submitted":
                    doc_id, version = next_id(state, "documents"), 1
                evidence = data.get("version_evidence", {})
                previous = evidence.get(old["data"].get("path"), []) if old else []
                # Retain only existing support whose exact claim remains in this draft.
                retained = [s for s in previous if s.get("claim") and s["claim"] in payload["body"]]
                data["version_evidence"] = dict(evidence, **{f".jobhunt/documents/{doc_id}-v{version:03d}.md": retained})
                data["verified_fact_ids"] = sorted({f for s in retained for f in s["fact_ids"]})
            saved = save_document(store, {"kind": "documents", "id": old["id"] if old else doc_id,
                "expected_version": old["version"] if old else 0, "operation_id": payload["operation_id"], "data": data,
                "career_scope": {"company_id": offer["data"]["company_id"], "offer_id": offer_id}, "career_questions": [question]}, transaction=db)
            reference = {"id": saved["id"], "version": saved["data"]["version"]}
            work.update(adopted=reference, reviewed=None)
        else:
            check_source(store, db, offer)
            if type(doc_version) is not int or doc_version < 1:
                raise CareerError("invalid_document", "回答の版を選んでください。")
            entity = check_document(store, db, doc_id, offer_id, question_id, doc_version)
            context = document_context(db, doc_id, doc_version)
            if context["question_refs"][0].get("version") != question["version"]:
                raise CareerError("question_version_conflict", "この回答は以前の設問に対するものです。原文を確認して保存し直してください。", 409)
            reference = {"id": doc_id, "version": doc_version}
            if intent == "review":
                if work.get("adopted") != reference:
                    raise CareerError("answer_version_conflict", "採用中の版を確認してください。", 409)
                check_source(store, db, offer)
                work["reviewed"] = reference
            else:
                work.update(adopted=reference, reviewed=None)
            saved = entity
        work["revision"] += 1
        store.set_meta(db, key, work)
        return {"work": work, "document": saved, "server_saved": True}
    return store.operation(payload.get("operation_id"), payload, apply)

"""Hermes entry point: read-only Chat tools and accepted-run career coaching tools."""
import json
import sys
from pathlib import Path

plugin_root = str(Path(__file__).resolve().parent)
if plugin_root not in sys.path:
    sys.path.insert(0, plugin_root)

from career_core.store import CareerError, Store


def register(ctx):
    from career_core.coach_skills import register_skills
    from career_core.coach_tools import register_tools
    register_skills(ctx)
    from career_core.native_coach import register_native, native_store
    from career_core.coach_consult import register_consultations
    register_tools(ctx, store_factory=native_store)
    register_native(ctx)
    register_consultations(ctx)
    from career_core.mypage_agent import register_tools as register_mypage_tools
    register_mypage_tools(ctx)
    def read(args, **kwargs):
        from career_core.context_bundle import constraints, filter_entities, source_work
        from career_core.shared import current_state
        store = Store()
        snapshot = store.snapshot()
        purpose = args.get("purpose", "operations")
        with store.connect() as db:
            state = current_state(store, db)
            policy = constraints(store.meta(db, "jobhunt_context_bundle"), purpose,
                                 store.meta(db, "jobhunt_context_status", {"state": "not_imported"}))
        kind, entity_id = args.get("kind"), args.get("id")
        entities = [e for e in snapshot["entities"] if (not kind or e["kind"] == kind) and (not entity_id or e["id"] == entity_id)]
        entities = filter_entities(entities, policy["blocked_ids"])
        # Keep correction/uncertainty explicit; submitted documents are history, never fact evidence.
        return json.dumps({"context_constraints": policy, "entities": entities, "source_work": source_work(state),
            "sync": snapshot["sync"], "policy": (
            "本人の表現と訂正を優先。未確認・推測・モデル要約を本人事実にしない。"
            "相談では一つの質問から進め、仕上げの依頼で本文を作る。"
            "SUPERSEDED/needs_reviewの情報は現行の根拠に使わない。"
            "context_constraintsのgeneration_guardsを先に適用し、blocked_idsを根拠に使わない。"
            "external_generation_allowedがfalseならES・面接回答・企業別推薦を作らない。"
            "提出済み文書は履歴。変更はcareer_proposeで提案し、Mobileで本人が差分を確認して保存する。")}, ensure_ascii=False)

    def propose(args, **kwargs):
        try:
            return json.dumps(Store().propose(args), ensure_ascii=False)
        except CareerError as error:
            return json.dumps({"error": error.code}, ensure_ascii=False)

    ctx.register_tool(name="career_read", toolset="career", handler=read, schema={
        "name": "career_read", "description": "就活情報を版・出典・不確実性とともに読む。",
        "parameters": {"type": "object", "properties": {"kind": {"type": "string"}, "id": {"type": "string"},
                                                        "purpose": {"type": "string", "enum": ["operations", "exploration", "strategy", "creation", "practice"]}}}})
    ctx.register_tool(name="career_propose", toolset="career", handler=propose, schema={
        "name": "career_propose", "description": "保存前の差分案を作る。ユーザーによるMobile上の確認が必要。",
        "parameters": {"type": "object", "required": ["kind", "id", "expected_version", "operation_id", "data", "reason"],
                       "properties": {"kind": {"type": "string"}, "id": {"type": "string"},
                                      "expected_version": {"type": "integer"}, "operation_id": {"type": "string"},
                                      "data": {"type": "object"}, "reason": {"type": "string"}}}})

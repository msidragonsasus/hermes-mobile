"""Session-scoped agent tools; local mailbox and Hermes native completion delivery.

No Relay, external HTTP, extra API client, or transcript mutation. The detached
runner only waits for the Dashboard worker, then returns its saved result through
Hermes' existing async delegation lifecycle (including routing and delivery ACKs).
"""
import hashlib
import json
import sqlite3
import threading

from group_service import GroupError
from group_catalog import GroupCatalog
from group_supervision import DelegationStore, TERMINAL


def host_context(metadata):
    from gateway.session_context import async_delivery_supported, get_session_env
    from hermes_constants import get_hermes_home
    from hermes_cli.profiles import get_active_profile_name
    from tools.approval_context import get_current_session_key
    parent = metadata.get("parent_agent")
    session = str(metadata.get("session_id") or getattr(parent, "session_id", "") or "")
    key = get_current_session_key(default="") or session
    if get_session_env("HERMES_SESSION_SOURCE", "") == "tui":
        key = session
    if not session or not key or not async_delivery_supported():
        raise GroupError("background_delivery_unavailable", 503)
    # Model arguments cannot choose an owner, profile, database or return address.
    store = DelegationStore(get_hermes_home() / "group_chat" / "history.db")
    store.path.parent.mkdir(parents=True, exist_ok=True)
    store.initialize()
    return store, key, get_active_profile_name(), {
        "session_key": key, "parent_session_id": session,
        "origin_ui_session_id": get_session_env("HERMES_UI_SESSION_ID", ""),
        "origin_session_id": get_session_env("HERMES_SESSION_ID", ""),
    }


class AgentTools:
    def __init__(self, context=host_context, dispatch=None, durable=None, catalog=None):
        self.context, self.dispatch, self.durable = context, dispatch, durable
        self.catalog = catalog if catalog is not None else GroupCatalog()
        self.lock = threading.RLock()
        self.watches = set()

    def watch(self, store, owner, task, routing):
        # A stable native completion ID allows host recovery/delivery deduplication.
        identity = "group-" + hashlib.sha256((str(store.path) + "\0" + owner + "\0" + task["taskId"]).encode()).hexdigest()[:40]
        with self.lock:
            if identity in self.watches:
                return
            dispatch, durable = self.dispatch, self.durable
            if dispatch is None:
                from tools.async_delegation import dispatch_async_delegation_batch, get_durable_delegation
                dispatch, durable = dispatch_async_delegation_batch, get_durable_delegation
            existing = durable(identity)
            if existing and existing["state"] not in {"unknown", "error", "stalled"}:
                return
            self.watches.add(identity)

            def wait_result():
                try:
                    while True:
                        value = store.read(owner, task["taskId"])
                        if value["state"] in TERMINAL:
                            return {"results": [{"task_index": 0, "status": "completed",
                                "summary": json.dumps({"groupDelegation": value,
                                    "instruction": "依頼元の会話へ結果を報告してください。state=complete以外を解決済みと扱わず、異論・未解決点も伝えてください。"}, ensure_ascii=False)}]}
                        if not store.ready():
                            # Durable task survives. Reattach on the next main turn.
                            return {"results": [{"task_index": 0, "status": "error", "summary": json.dumps({
                                "taskId": task["taskId"], "state": "worker_unavailable", "instruction": "議論は完了していません。復旧後にgroup_task_statusで確認してください。"}, ensure_ascii=False)}]}
                        threading.Event().wait(1)
                finally:
                    with self.lock:
                        self.watches.discard(identity)

            try:
                response = dispatch(goals=["グループ議論の結果を依頼元へ返す"], context=None, toolsets=[],
                    role="group_monitor", model=None, delegation_id=identity, runner=wait_result,
                    interrupt_fn=lambda: store.stop(owner, task["taskId"]), **routing)
                if response.get("status") != "dispatched":
                    raise GroupError("background_delivery_unavailable", 503)
            except BaseException:
                self.watches.discard(identity)
                raise

    def call(self, name, args, metadata):
        try:
            store, owner, supervisor, routing = self.context(metadata)
            if not isinstance(args, dict):
                raise GroupError("invalid_request", 422)
            if name == "group_list":
                return json.dumps({"groups": self.catalog.list()}, ensure_ascii=False)
            if name == "group_tasks":
                return json.dumps({"tasks": store.list(owner)}, ensure_ascii=False)
            if name == "group_delegate":
                if not store.ready():
                    raise GroupError("group_worker_unavailable", 503)
                task = store.submit(owner, self.catalog.resolve(args), supervisor, hold=True)
                try:
                    self.watch(store, owner, task, routing)
                except Exception:
                    store.stop(owner, task["taskId"])
                    raise
                store.activate(owner, task["taskId"])
                task = store.read(owner, task["taskId"])
            else:
                task_id = args.get("taskId")
                if not isinstance(task_id, str) or not task_id:
                    raise GroupError("invalid_request", 422)
                task = store.stop(owner, task_id) if name == "group_task_stop" else store.read(owner, task_id)
                if name == "group_task_status":
                    self.watch(store, owner, task, routing)
                    store.activate(owner, task_id)
                    task = store.read(owner, task_id)
            return json.dumps({"task": task, "note": "結果は元の会話へ戻ります。待機や定期ポーリングをせず、通常の会話を続けてください。"}, ensure_ascii=False)
        except GroupError as failure:
            return json.dumps({"error": failure.code})
        except (OSError, sqlite3.Error):
            return json.dumps({"error": "group_storage_unavailable"})
        except Exception:
            # Neither exception details nor transcripts enter logs or tool errors.
            return json.dumps({"error": "group_tool_unavailable"})

    def recover(self, **metadata):
        try:
            store, owner, _, routing = self.context(metadata)
            for task in store.list(owner):
                # An outage notice wakes the main agent once. Do not attach a new
                # waiter from that turn while the same worker is still offline.
                if task["state"] not in TERMINAL and not store.ready():
                    continue
                self.watch(store, owner, task, routing)
                store.activate(owner, task["taskId"])
        except Exception:
            pass


def register_tools(ctx):
    tools = AgentTools()
    task_id = {"type": "string", "description": "この依頼に固定した再送用ID"}
    definitions = [
        ("group_delegate", "既存グループへ議論を依頼。先にgroup_listでgroupIdと参加者を確認する。保存済みメンバー・発言順・まとめ役を使い、新規グループ作成や全botの自動参加は行わない。見つからない/曖昧なら本人に確認する。結果は元の会話へ返却。同じ依頼の再送は同じtaskIdを使う。", {
            "taskId": task_id, "groupId": {"type": "string", "description": "group_listの既存groupId"},
            "groupName": {"type": "string", "description": "既存グループの正確な名前。groupIdでの指定を推奨。"},
            "goal": {"type": "string"}, "completionCriteria": {"type": "string"},
            "context": {"type": "string", "description": "議論に必要な背景・制約・既知の情報。会話全体ではなく必要な部分だけ渡す。"},
            "maxCycles": {"type": "integer", "minimum": 1, "maximum": 5},
            "maxMessages": {"type": "integer", "minimum": 2, "maximum": 50},
            "budgetSeconds": {"type": "integer", "minimum": 60, "maximum": 7200}}, ["taskId", "goal", "completionCriteria"]),
        ("group_list", "保存済みグループのID・名前・参加者・実行可否を確認。議論を依頼する前に使う。グループや会話は作成しない。", {}, []),
        ("group_task_status", "この会話から依頼した議論の進捗・結果を確認。復旧後の結果通知も再接続する。", {"taskId": task_id}, ["taskId"]),
        ("group_task_stop", "この会話から依頼した議論を停止。本人の停止を自動で再開しない。", {"taskId": task_id}, ["taskId"]),
        ("group_tasks", "この会話から依頼した議論の一覧。", {}, []),
    ]
    for name, description, properties, required in definitions:
        def handler(args, _name=name, **metadata):
            return tools.call(_name, args, metadata)
        ctx.register_tool(name=name, toolset="group_chat", handler=handler, schema={"name": name,
            "description": description, "parameters": {"type": "object", "properties": properties, "required": required, "additionalProperties": False}})
    ctx.register_hook("pre_llm_call", tools.recover)

"""GitHub Contents CAS and three-way field merge; credentials stay on the server."""
import base64
import copy
import contextlib
import json
import os
import hashlib
import re
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import quote

import httpx

from .store import CareerError, SHARED, Store, encoded, now
from .context_bundle import choose_context_bundle, validate_manifest
from .sharing import (CURRENT_JSON, CURRENT_MD, align_fact_corrections, blob_sha, build_current,
                      merge_pending, render_current, update_versions)

ID_FIELDS = {"facts": "fact_id", "hypotheses": "hypothesis_id", "stories": "story_id", "documents": "document_id", "decisions": "decision_id", "sources": "source_id"}
MISSING = object()


def identity(kind, record):
    return record.get("id") or record.get(ID_FIELDS.get(kind, "id"))


def merge_fields(base, local, remote):
    """Nested values/arrays are atomic, matching the coaching plugin contract."""
    if local == base or local == remote:
        return copy.deepcopy(remote), []
    if remote == base:
        return copy.deepcopy(local), []
    if not all(isinstance(x, dict) for x in (base, local, remote)):
        return local, ["record"]
    merged, conflicts = {}, []
    for key in base.keys() | local.keys() | remote.keys():
        b, l, r = (x.get(key, MISSING) for x in (base, local, remote))
        if l == b or l == r:
            value = r
        elif r == b:
            value = l
        else:
            conflicts.append(key)
            value = l
        if value is not MISSING:
            merged[key] = copy.deepcopy(value)
    return merged, conflicts


class GitHub:
    REPO = "msidragonsasus/jp-es-memory"
    STATE = ".jobhunt/state.json"
    MANIFEST = ".jobhunt/manifest.json"
    CONTEXT_DIRECTORY = ".jobhunt/context"

    def __init__(self, token_file=None, client=None, *, repository=None, branch=None):
        self.REPO = repository or os.environ.get("CAREER_GITHUB_REPOSITORY") or self.REPO
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", self.REPO):
            raise CareerError("invalid_career_repository", "GitHubの保存先を確認してください。", 409)
        self.branch = branch or os.environ.get("CAREER_GITHUB_BRANCH")
        path = token_file or os.environ.get("CAREER_GITHUB_TOKEN_FILE")
        if not path:
            raise CareerError("github_not_configured", "GitHub認証が未設定です。", 503)
        try:
            token = Path(path).read_text(encoding="utf-8").strip()
        except (OSError, UnicodeError):
            raise CareerError("github_not_configured", "GitHub認証ファイルを読み取れません。", 503) from None
        if not token:
            raise CareerError("github_not_configured", "GitHub認証が未設定です。", 503)
        self.client = client or httpx.Client(timeout=30, follow_redirects=False)
        self.headers = {"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json",
                        "X-GitHub-Api-Version": "2022-11-28"}
        self.context_bundle = None
        self.context_status = {"state": "not_checked"}
        self.source_commit = None
        self.source_tree = None
        self.source_hashes = {}
        self.manifest = {}
        self.context_values = {}
        self.entry_path = None
        self.pending_files = {}
        self.pending_context = None
        self.current = None

    def call(self, method, path, **kwargs):
        try:
            response = self.client.request(method, "https://api.github.com/repos/" + self.REPO + path,
                                           headers=self.headers, **kwargs)
        except httpx.HTTPError:
            raise CareerError("github_unreachable", "GitHubに接続できません。未同期の変更を保持しています。", 503) from None
        if response.status_code in {401, 403, 404}:
            raise CareerError("github_auth", "privateリポジトリへの認証・権限を確認してください。", 503)
        if response.status_code in {409, 422}:
            raise CareerError("github_changed", "GitHubが更新されました。次回同期で再照合します。", 409)
        if not response.is_success:
            raise CareerError("github_failed", "GitHub同期に失敗しました。", 503)
        return response.json()

    def contents(self, path, ref=None, optional=False):
        try:
            response = self.client.get(
                "https://api.github.com/repos/" + self.REPO + "/contents/" + quote(path, safe="/"),
                headers=self.headers,
                params={"ref": ref} if ref else None,
            )
        except httpx.HTTPError:
            raise CareerError("github_unreachable", "GitHubに接続できません。未同期の変更を保持しています。", 503) from None
        if response.status_code == 404 and optional:
            return None
        if response.status_code in {401, 403, 404}:
            raise CareerError("github_auth", "privateリポジトリへの認証・権限を確認してください。", 503)
        if not response.is_success:
            raise CareerError("github_failed", "GitHub同期に失敗しました。", 503)
        return response.json()

    @staticmethod
    def json_blob(blob, maximum):
        if not isinstance(blob, dict) or blob.get("type") != "file" or blob.get("size", maximum + 1) > maximum:
            raise CareerError("invalid_github_file", "GitHub上の就活ファイルを確認してください。", 409)
        try:
            raw = base64.b64decode("".join(blob["content"].split()), validate=True)
            if len(raw) > maximum:
                raise ValueError("too large")
            return json.loads(raw.decode("utf-8"))
        except (KeyError, ValueError, UnicodeError, json.JSONDecodeError):
            raise CareerError("invalid_github_file", "GitHub上の就活ファイルを確認してください。", 409) from None

    def read(self):
        repo = self.call("GET", "")
        if repo.get("private") is not True or not repo.get("permissions", {}).get("push"):
            raise CareerError("github_permissions", "書き込み可能なprivateリポジトリが必要です。", 503)
        branch = self.branch or repo.get("default_branch")
        if not isinstance(branch, str) or not branch:
            raise CareerError("invalid_career_repository", "GitHubの既定ブランチを確認してください。", 409)
        commit_object = self.call("GET", "/commits/" + quote(branch, safe=""))
        commit = commit_object.get("sha")
        if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
            raise CareerError("invalid_career_repository", "GitHubのcommitを確認してください。", 409)
        manifest_blob = self.contents(self.MANIFEST, commit)
        manifest = self.json_blob(manifest_blob, 262144)
        blob = self.contents(self.STATE, commit)
        state = self.json_blob(blob, 10 * 1024 * 1024)
        validate_manifest(manifest, state)
        explicit_entry = manifest.get("context_entry_path")
        if explicit_entry is not None and (not isinstance(explicit_entry, str) or
                not re.fullmatch(r"\.jobhunt/context/[A-Za-z0-9._-]+\.json", explicit_entry)):
            raise CareerError("invalid_context_path", "訂正の入口を確認してください。", 409)
        listing = None if explicit_entry else self.contents(self.CONTEXT_DIRECTORY, commit, optional=True)
        contexts = {}
        hashes = {self.MANIFEST: manifest_blob["sha"], self.STATE: blob["sha"]}
        if explicit_entry:
            path = explicit_entry
            while path:
                if path in contexts or len(contexts) >= 100 or not isinstance(path, str) or not re.fullmatch(r"\.jobhunt/context/[A-Za-z0-9._-]+\.json", path):
                    raise CareerError("context_base_cycle", "訂正の参照関係を確認してください。", 409)
                context_blob = self.contents(path, commit)
                value = self.json_blob(context_blob, 1024 * 1024)
                contexts[path] = value
                hashes[path] = context_blob["sha"]
                path = value.get("base_context_path")
        if listing is not None:
            if not isinstance(listing, list) or len(listing) > 100:
                raise CareerError("invalid_context_directory", "就活コンテクストの一覧を確認してください。", 409)
            for item in listing:
                if not isinstance(item, dict):
                    continue
                path = item.get("path")
                if item.get("type") != "file" or not isinstance(path, str) or not re.fullmatch(r"\.jobhunt/context/[A-Za-z0-9._-]+\.json", path):
                    continue
                context_blob = self.contents(path, commit)
                value = self.json_blob(context_blob, 1024 * 1024)
                contexts[path] = value
                hashes[path] = context_blob["sha"]
        selected_path = explicit_entry
        if selected_path is not None and (not isinstance(selected_path, str) or
                not re.fullmatch(r"\.jobhunt/context/[A-Za-z0-9._-]+\.json", selected_path)):
            raise CareerError("invalid_context_path", "訂正の入口を確認してください。", 409)
        if selected_path is None and contexts:
            supported = {path: value for path, value in contexts.items()
                         if isinstance(value, dict) and value.get("format") == "jobhunt-self-analysis-context/1"}
            if supported:
                selected_path = max(supported, key=lambda path: (
                    supported[path].get("snapshot_state_revision", 0), str(supported[path].get("imported_on") or ""), path))
        self.context_bundle = choose_context_bundle(contexts, state, selected_path)
        self.branch, self.manifest, self.entry_path = branch, manifest, selected_path
        self.context_values = contexts
        self.source_hashes = hashes
        self.source_commit = commit
        self.source_tree = commit_object.get("commit", {}).get("tree", {}).get("sha")
        self.context_status = {
            "state": "verified" if self.context_bundle else "missing",
            "commit": commit,
            "path": selected_path,
            "state_revision": state.get("revision"),
        }
        return state, blob["sha"]

    def commit_files(self, files, message):
        """One non-force branch update protects the entire source or output batch."""
        if any(path not in {self.STATE, self.MANIFEST, CURRENT_JSON, CURRENT_MD} and
               not self.valid_document_path(path, writable=True) and
               not re.fullmatch(r"\.jobhunt/context/hermes-r\d+-[0-9a-f]{12}\.json", path) for path in files):
            raise CareerError("invalid_commit_path", "共有データの保存先を確認してください。", 409)
        repo = self.call("GET", "")
        if repo.get("private") is not True or not repo.get("permissions", {}).get("push"):
            raise CareerError("github_permissions", "書き込み可能なprivateリポジトリが必要です。", 503)
        if self.manifest.get("consents", {}).get("github_write_through") is not True:
            raise CareerError("career_write_not_consented", "GitHubへの保存同意を確認してください。", 409)
        if not self.source_tree:
            self.source_tree = self.call("GET", "/git/commits/" + self.source_commit)["tree"]["sha"]
        tree = self.call("POST", "/git/trees", json={"base_tree": self.source_tree, "tree": [
            {"path": path, "mode": "100644", "type": "blob", "content": body}
            for path, body in sorted(files.items())]})
        commit = self.call("POST", "/git/commits", json={"message": message, "tree": tree["sha"],
                           "parents": [self.source_commit]})
        self.call("PATCH", "/git/refs/heads/" + quote(self.branch, safe="/"),
                  json={"sha": commit["sha"], "force": False})
        self.source_commit, self.source_tree = commit["sha"], tree["sha"]
        for path, body in files.items():
            if path not in {CURRENT_JSON, CURRENT_MD} and not self.valid_document_path(path):
                self.source_hashes[path] = blob_sha(body)
        return commit["sha"]

    def write(self, state, sha):
        if sha != self.source_hashes.get(self.STATE):
            raise CareerError("github_changed", "GitHubが更新されています。", 409)
        body = encoded(state) + "\n"
        files = dict(self.pending_files, **{self.STATE: body})
        if self.pending_context is not None:
            overlay = copy.deepcopy(self.pending_context)
            overlay["snapshot_state_revision"] = state["revision"]
            # A full resolved, immutable snapshot prevents mutable-base cycles when
            # another client adds an additive delta on top of a Hermes update.
            path = ".jobhunt/context/hermes-r" + str(state["revision"]) + "-" + hashlib.sha256(encoded(overlay).encode()).hexdigest()[:12] + ".json"
            files[path] = encoded(overlay) + "\n"
            self.manifest = dict(self.manifest, context_entry_path=path)
            files[self.MANIFEST] = encoded(self.manifest) + "\n"
            self.context_values = {path: overlay}
            self.entry_path = path
            self.source_hashes = {key: value for key, value in self.source_hashes.items() if key in {self.STATE, self.MANIFEST}}
        self.commit_files(files, "Update career facts and context from Hermes")
        self.pending_files = {}
        if self.pending_context is not None:
            self.context_bundle = choose_context_bundle(self.context_values, state, self.entry_path)
            self.pending_context = None
        return blob_sha(body)

    def publish_current(self, state):
        if not self.context_bundle:
            return self.context_status
        existing_blob = self.contents(CURRENT_JSON, self.source_commit, optional=True)
        existing = self.json_blob(existing_blob, 2 * 1024 * 1024) if existing_blob else None
        commit = existing.get("source_commit") if isinstance(existing, dict) else None
        unchanged_inputs = isinstance(existing, dict) and existing.get("source_hashes") == self.source_hashes
        value = build_current(state, self.context_bundle, self.source_hashes,
                              commit if unchanged_inputs and isinstance(commit, str) and re.fullmatch(r"[0-9a-f]{40}", commit) else self.source_commit)
        markdown = render_current(value)
        md_blob = self.contents(CURRENT_MD, self.source_commit, optional=True)
        md_matches = bool(md_blob and md_blob.get("sha") == blob_sha(markdown))
        if existing != value or not md_matches:
            self.commit_files({CURRENT_JSON: encoded(value) + "\n", CURRENT_MD: markdown},
                              "Generate current self-analysis context")
        self.current = value
        self.context_status = {"state": "verified", "commit": value["source_commit"],
                               "published_commit": self.source_commit, "path": self.entry_path,
                               "state_revision": state["revision"], "content_hash": value["content_hash"],
                               "source_hashes": self.source_hashes, "checked_at": now()}
        return self.context_status

    @staticmethod
    def valid_document_path(path, *, writable=False):
        pattern = r"\.jobhunt/documents/DOC-\d{3,}-v\d{3,}\.md" if writable else r"\.jobhunt/documents/[A-Za-z0-9][A-Za-z0-9._-]{0,159}\.md"
        return isinstance(path, str) and re.fullmatch(pattern, path) is not None

    def document(self, path):
        if not self.valid_document_path(path):
            raise CareerError("invalid_document_path", "文書の保存先が不正です。", 409)
        blob = self.contents(path, self.source_commit, optional=True)
        if blob is None:
            return None
        try:
            raw = base64.b64decode("".join(blob["content"].split()), validate=True)
        except (KeyError, ValueError):
            raise CareerError("document_unavailable", "文書を取得できません。", 503) from None
        if len(raw) > 262144 or blob.get("type") != "file":
            raise CareerError("document_too_large", "文書が容量上限を超えています。", 409)
        try:
            return raw.decode("utf-8")
        except UnicodeError:
            raise CareerError("document_unavailable", "文書をUTF-8として読み取れません。", 409) from None

    def write_document(self, path, body):
        if not self.valid_document_path(path, writable=True):
            raise CareerError("invalid_document_path", "新しい文書の保存先が不正です。", 409)
        existing = self.document(path)
        if existing is not None:
            if existing != body:
                raise CareerError("immutable_document_conflict", "同じ文書の版に異なる内容があります。", 409)
            return
        self.pending_files[path] = body


def state_records(state):
    if state.get("schema_version") not in {"4.0", "4.1"} or not isinstance(state.get("candidate"), dict):
        raise CareerError("unsupported_state", "未対応または不正なstateです。移行は実行していません。", 409)
    result = {("profile", "candidate"): state["candidate"]}
    for kind in SHARED - {"profile"}:
        records = state.get(kind)
        if not isinstance(records, list):
            raise CareerError("invalid_state", "stateのコレクションが不正です。", 409)
        for record in records:
            if not isinstance(record, dict):
                raise CareerError("invalid_state", "stateに不正なレコードがあります。", 409)
            entity_id = identity(kind, record)
            if not entity_id or (kind, entity_id) in result:
                raise CareerError("invalid_state", "stateのIDに欠落または重複があります。", 409)
            result[kind, entity_id] = record
    return result


SYNC_INPUT_METADATA = {"github_base", "github_sha", "jobhunt_context_pending", "coach_checkpoint_pending",
                       "jobhunt_context_bundle", "jobhunt_context_base", "jobhunt_context_status",
                       "jobhunt_context_versions", "jobhunt_context_conflicts", "jobhunt_current"}


class SyncSnapshot(Store):
    def __init__(self, connection):
        self.connection = connection

    def connect(self):
        return contextlib.nullcontext(self.connection)

    @contextlib.contextmanager
    def transaction(self):
        self.connection.execute("BEGIN IMMEDIATE")
        try:
            yield self.connection
            self.connection.commit()
        except BaseException:
            self.connection.rollback()
            raise


def sync_rows(db):
    return {
        "entities": [tuple(row) for row in db.execute("SELECT * FROM entities ORDER BY kind,id") if row["kind"] in SHARED],
        "document_files": [tuple(row) for row in db.execute("SELECT * FROM document_files ORDER BY path")],
        "conflicts": [tuple(row) for row in db.execute("SELECT * FROM conflicts WHERE id LIKE 'github:%' ORDER BY id")],
        "metadata": {row["key"]: row["value"] for row in db.execute("SELECT * FROM metadata")},
    }


def acknowledge_snapshot(store, db, before, current, after):
    guarded = {"github_base", "github_sha", "jobhunt_context_base"}
    if (any(current["metadata"].get(key) != before["metadata"].get(key) for key in guarded)
            or current["conflicts"] != before["conflicts"]):
        return False
    entity_maps = [{row[:2]: row for row in snapshot["entities"]} for snapshot in (before, current, after)]
    entity_plan = []
    for key in entity_maps[0].keys() | entity_maps[1].keys() | entity_maps[2].keys():
        previous, latest, acknowledged = [rows.get(key) for rows in entity_maps]
        data = [json.loads(row[3]) if row else None for row in (previous, latest, acknowledged)]
        merged, conflicts = merge_fields(*data)
        if conflicts:
            return False
        entity_plan.append((key, previous, latest, acknowledged, merged))
    def value(snapshot, key, default=None):
        raw = snapshot["metadata"].get(key)
        return json.loads(raw) if raw is not None else default
    outgoing = value(after, "github_base")
    if outgoing is None:
        return False
    bundle = value(after, "jobhunt_context_bundle")
    pending_context = value(current, "jobhunt_context_pending", {})
    if bundle is not None:
        from .sharing import records
        original = records(value(before, "jobhunt_context_bundle") or {})
        acknowledged = records(bundle)
        pending_context = copy.deepcopy(pending_context)
        for key, change in list(pending_context.items()):
            record = (change["section"], change["id"])
            merged, conflicts = merge_fields(original.get(record), change["data"], acknowledged.get(record))
            if conflicts:
                return False
            if merged == acknowledged.get(record):
                pending_context.pop(key)
            else:
                change.update(base=acknowledged.get(record), data=merged)
        try:
            local_bundle, conflicts = merge_pending(bundle, pending_context, outgoing, merge_fields)
        except CareerError:
            return False
        if conflicts:
            return False
    for key, previous, latest, acknowledged, merged in entity_plan:
        if latest == previous:
            db.execute("DELETE FROM entities WHERE kind=? AND id=?", key)
            if acknowledged:
                db.execute("INSERT INTO entities VALUES(?,?,?,?,?,?)", acknowledged)
        elif merged is not None:
            if latest is None or merged != json.loads(latest[3]):
                store.put(db, *key, latest[2] if latest else 0, merged, dirty=True)
            else:
                db.execute("UPDATE entities SET dirty=1 WHERE kind=? AND id=?", key)
    documents = [{row[0]: row for row in snapshot["document_files"]} for snapshot in (before, current, after)]
    for path, row in documents[2].items():
        if documents[1].get(path) == documents[0].get(path):
            db.execute("INSERT OR REPLACE INTO document_files VALUES(?,?,?,?)", row)
    excluded = {"sync_status", "jobhunt_context_pending", "coach_checkpoint_pending", "jobhunt_context_bundle",
                "jobhunt_context_versions", "jobhunt_context_base"}
    for key, raw in after["metadata"].items():
        if key not in excluded and current["metadata"].get(key) == before["metadata"].get(key):
            db.execute("INSERT OR REPLACE INTO metadata VALUES(?,?)", (key, raw))
    prior_checkpoints = value(before, "coach_checkpoint_pending", {})
    remaining = {key: item for key, item in value(current, "coach_checkpoint_pending", {}).items()
                 if key not in prior_checkpoints or item != prior_checkpoints[key]}
    store.set_meta(db, "coach_checkpoint_pending", remaining)
    if bundle is not None:
        store.set_meta(db, "jobhunt_context_base", bundle)
        store.set_meta(db, "jobhunt_context_bundle", local_bundle)
        store.set_meta(db, "jobhunt_context_pending", pending_context)
        update_versions(store, db, local_bundle)
    return True


def synchronize(store, github):
    from .locking import job_lock
    try:
        with job_lock(store.directory / "github-sync.lock") as acquired:
            if not acquired:
                return {"state": "running", "reason": "sync_in_progress", "checked_at": now()}
            with contextlib.closing(sqlite3.connect(":memory:")) as memory:
                memory.row_factory = sqlite3.Row
                with store.connect() as db:
                    db.backup(memory)
                before = sync_rows(memory)
                result = synchronize_snapshot(SyncSnapshot(memory), github)
                after = sync_rows(memory)
                changed_metadata = {key for key in before["metadata"].keys() | after["metadata"].keys()
                                    if before["metadata"].get(key) != after["metadata"].get(key)}
                with store.transaction() as db:
                    current = sync_rows(db)
                    observed_keys = SYNC_INPUT_METADATA | changed_metadata
                    if (any(current[table] != before[table] for table in ("entities", "document_files", "conflicts"))
                            or any(current["metadata"].get(key) != before["metadata"].get(key) for key in observed_keys)):
                        if result["state"] == "synced":
                            acknowledge_snapshot(store, db, before, current, after)
                        status = {"state": "unsynced", "reason": "local_changes_pending", "checked_at": now()}
                        store.set_meta(db, "sync_status", status)
                        return status
                    placeholders = ",".join("?" for _ in SHARED)
                    db.execute("DELETE FROM entities WHERE kind IN (" + placeholders + ")", tuple(SHARED))
                    db.executemany("INSERT INTO entities VALUES(?,?,?,?,?,?)", after["entities"])
                    db.execute("DELETE FROM document_files")
                    db.executemany("INSERT INTO document_files VALUES(?,?,?,?)", after["document_files"])
                    db.execute("DELETE FROM conflicts WHERE id LIKE 'github:%'")
                    db.executemany("INSERT INTO conflicts VALUES(?,?,?,?,?,?,?)", after["conflicts"])
                    for key in changed_metadata:
                        if key in after["metadata"]:
                            db.execute("INSERT OR REPLACE INTO metadata VALUES(?,?)", (key, after["metadata"][key]))
                        else:
                            db.execute("DELETE FROM metadata WHERE key=?", (key,))
                return result
    except sqlite3.OperationalError:
        status = {"state": "unsynced", "reason": "storage_busy", "checked_at": now()}
        try:
            with store.transaction() as db:
                store.set_meta(db, "sync_status", status)
        except sqlite3.OperationalError:
            pass
        return status


def synchronize_snapshot(store, github):
    try:
        with store.transaction() as db:
            remote, sha = github.read()
            context_bundle = getattr(github, "context_bundle", None)
            context_status = getattr(github, "context_status", None)
            context_pending = store.meta(db, "jobhunt_context_pending", {})
            incoming = state_records(remote)
            base_state = store.meta(db, "github_base")
            base = state_records(base_state) if base_state else {}
            outgoing = copy.deepcopy(remote)
            checkpoint_pending = store.meta(db, "coach_checkpoint_pending", {})
            if checkpoint_pending:
                history = outgoing.setdefault("last_session", {}).setdefault("hermes_coach", {})
                if not isinstance(history, dict) or any(key in history and history[key] != value for key, value in checkpoint_pending.items()):
                    raise CareerError("coach_checkpoint_conflict", "同じ作業の引き継ぎ情報が異なります。サーバー保存を保持しました。", 409)
                # Append only completed structured checkpoints. Preserve other clients' last_session
                # fields, and never serialize the coach_messages/history tables to GitHub.
                history.update(copy.deepcopy(checkpoint_pending))
            db.execute("DELETE FROM conflicts WHERE id LIKE 'github:%'")
            rows = { (r["kind"], r["id"]): dict(r, data=json.loads(r["data"]))
                    for r in db.execute("SELECT * FROM entities") if r["kind"] in SHARED }
            merged_records, conflicts = {}, []
            for key in incoming.keys() | rows.keys():
                row = rows.get(key)
                local = row["data"] if row else None
                other = incoming.get(key)
                if not row or not row["dirty"]:
                    merged, fields = other, []
                else:
                    merged, fields = merge_fields(base.get(key), local, other)
                if fields:
                    conflict_id = "github:" + ":".join(key)
                    db.execute("INSERT INTO conflicts VALUES(?,?,?,?,?,?,?)", (
                        conflict_id, *key, encoded(base.get(key)), encoded(local), encoded(other), now()))
                    conflicts.append(conflict_id)
                    continue
                merged_records[key] = merged
            # Do not partially push while unresolved conflicts exist. Safe remote reads still remain available.
            if conflicts:
                status = {"state": "conflict", "count": len(conflicts), "checked_at": now()}
                store.set_meta(db, "sync_status", status)
                return status
            outgoing["candidate"] = merged_records.get(("profile", "candidate"), outgoing["candidate"])
            for kind in SHARED - {"profile"}:
                seen = set()
                result = []
                for record in outgoing[kind]:
                    entity_id = identity(kind, record)
                    seen.add(entity_id)
                    data = merged_records.get((kind, entity_id), record)
                    if data is not None:
                        result.append(data)
                for key in sorted(merged_records):
                    if key[0] == kind and key[1] not in seen and merged_records[key] is not None:
                        result.append(merged_records[key])
                outgoing[kind] = result
            context_changed = False
            if context_pending:
                if not context_bundle:
                    raise CareerError("context_base_missing", "訂正・制約の同期元が見つかりません。", 409)
                context_bundle, context_conflicts = merge_pending(context_bundle, context_pending, outgoing, merge_fields)
                if context_conflicts:
                    store.set_meta(db, "jobhunt_context_conflicts", context_conflicts)
                    status = {"state": "conflict", "count": len(context_conflicts), "reason": "context_conflict", "checked_at": now()}
                    store.set_meta(db, "sync_status", status)
                    return status
            context_bundle = align_fact_corrections(context_bundle, remote, outgoing)
            context_changed = context_bundle != getattr(github, "context_bundle", None)
            if context_changed:
                github.pending_context = context_bundle
            if outgoing != remote or context_changed:
                from .shared import validate_shared
                validate_shared(outgoing, previous=remote)
                outgoing["revision"] = remote["revision"] + 1
                outgoing["updated_at_jst"] = datetime.now(timezone(timedelta(hours=9))).strftime("%Y-%m-%d %H:%M JST")
                for pending in db.execute("SELECT * FROM document_files WHERE synced=0"):
                    github.write_document(pending["path"], pending["body"])
                    db.execute("UPDATE document_files SET synced=1 WHERE path=?", (pending["path"],))
                sha = github.write(outgoing, sha)
            if hasattr(github, "publish_current"):
                context_status = github.publish_current(outgoing)
                context_bundle = github.context_bundle
            # A source commit may have succeeded before its acknowledgement was lost.
            # Verify immutable bodies before marking the durable local outbox delivered.
            for pending in db.execute("SELECT * FROM document_files WHERE synced=0"):
                if github.document(pending["path"]) != pending["body"]:
                    raise CareerError("document_not_synced", "文書の反映を確認できません。変更を保持しています。", 409)
                db.execute("UPDATE document_files SET synced=1 WHERE path=?", (pending["path"],))
            for doc in outgoing["documents"]:
                path = doc.get("path") or doc.get("file_path")
                if path and not db.execute("SELECT 1 FROM document_files WHERE path=?", (path,)).fetchone():
                    body = github.document(path)
                    if body is None:
                        raise CareerError("document_missing", "文書の本文がGitHubに見つかりません。", 409)
                    checksum = hashlib.sha256(body.encode()).hexdigest()
                    if doc.get("checksum_sha256") and checksum != doc["checksum_sha256"]:
                        raise CareerError("document_checksum", "文書の検証に失敗しました。", 409)
                    db.execute("INSERT INTO document_files VALUES(?,?,?,1)", (path, body, checksum))
            for (kind, entity_id), data in merged_records.items():
                row = rows.get((kind, entity_id))
                if data is None:
                    db.execute("DELETE FROM entities WHERE kind=? AND id=?", (kind, entity_id))
                elif not row or data != row["data"]:
                    store.put(db, kind, entity_id, row["version"] if row else 0, data, dirty=False)
                else:
                    db.execute("UPDATE entities SET dirty=0 WHERE kind=? AND id=?", (kind, entity_id))
            store.set_meta(db, "github_base", outgoing)
            store.set_meta(db, "github_sha", sha)
            if context_status is not None:
                store.set_meta(db, "jobhunt_context_bundle", context_bundle)
                store.set_meta(db, "jobhunt_context_base", context_bundle)
                store.set_meta(db, "jobhunt_context_status", context_status)
                update_versions(store, db, context_bundle)
            if getattr(github, "current", None):
                store.set_meta(db, "jobhunt_current", github.current)
            if context_pending:
                store.set_meta(db, "jobhunt_context_pending", {})
                store.set_meta(db, "jobhunt_context_conflicts", [])
            if checkpoint_pending:
                store.set_meta(db, "coach_checkpoint_pending", {})
            status = {"state": "synced", "checked_at": now(), "schema_version": outgoing["schema_version"]}
            if context_status is not None:
                status["context"] = context_status
            store.set_meta(db, "sync_status", status)
            return status
    except (CareerError, ValueError, KeyError, TypeError) as error:
        status = {"state": "unsynced", "reason": error.code if isinstance(error, CareerError) else "invalid_state", "checked_at": now()}
        with store.transaction() as db:
            store.set_meta(db, "sync_status", status)
        return status


def resolve_conflict(store, request):
    def apply(db):
        if any(item["id"] == request["id"] for item in store.meta(db, "jobhunt_context_conflicts", [])):
            from .sharing import resolve_context_conflict
            return resolve_context_conflict(store, db, request)
        row = db.execute("SELECT * FROM conflicts WHERE id=?", (request["id"],)).fetchone()
        if not row or request["choice"] not in {"local", "remote"}:
            raise CareerError("conflict_changed", "競合情報を読み直してください。", 409)
        current = store.entity(db, row["kind"], row["entity_id"])
        if not current or current["version"] != request["expected_version"]:
            raise CareerError("version_conflict", "保存後に内容が変わっています。", 409)
        chosen = json.loads(row[request["choice"]])
        # Keeping a local value changes only its merge base; the next remote fetch still uses fresh SHA.
        base = store.meta(db, "github_base")
        kind, entity_id = row["kind"], row["entity_id"]
        remote = json.loads(row["remote"])
        if not base:
            raise CareerError("sync_required", "最初の同期を完了してから編集してください。", 409)
        if kind == "profile":
            base["candidate"] = remote
        else:
            base[kind] = [r for r in base[kind] if identity(kind, r) != entity_id]
            if remote is not None:
                base[kind].append(remote)
        if chosen is None:
            db.execute("DELETE FROM entities WHERE kind=? AND id=?", (kind, entity_id))
        else:
            store.put(db, kind, entity_id, current["version"], chosen, dirty=request["choice"] == "local")
        store.set_meta(db, "github_base", base)
        db.execute("DELETE FROM conflicts WHERE id=?", (request["id"],))
        return {"resolved": request["id"], "state": "unsynced"}
    return store.operation(request["operation_id"], request, apply)

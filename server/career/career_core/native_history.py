"""Read only the native sessions explicitly bound to the current Career owner."""
import hashlib
import json
import sqlite3
from pathlib import Path


def records(store, run, allowed_threads=None):
    with store.connect() as db:
        exists = db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='coach_native_sessions'").fetchone()
        if not exists:
            return []
        sessions = db.execute("SELECT * FROM coach_native_sessions WHERE owner=?", (run["profile"],)).fetchall()
        sessions = [dict(row) for row in sessions]
        sources_exist = db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='coach_native_sources'").fetchone()
        if sources_exist:
            sessions += [dict(row) for row in db.execute("SELECT * FROM coach_native_sources WHERE owner=?", (run["profile"],))]
        proofs = db.execute("""SELECT e.* FROM coach_native_evidence e JOIN coach_runs r ON r.id=e.run_id
            WHERE r.profile=? AND e.run_id=?""", (run["profile"], run["id"])).fetchall()
    result = [dict(id=p["id"], kind="messages", title=p["text"][:80], text=p["text"], role="user",
                   source="native_original_user", thread_id=run.get("thread_id"), source_id=p["source_id"],
                   evidence_type="original_user_statement") for p in proofs]
    seen = set()
    for session in sessions:
        source_key = (session["state_path"], session["session_id"], session["thread_id"])
        if source_key in seen:
            continue
        seen.add(source_key)
        if allowed_threads is not None and session["thread_id"] not in allowed_threads:
            continue
        path = Path(session["state_path"])
        if not path.is_file():
            continue
        connection = None
        try:
            connection = sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True, timeout=2)
            connection.row_factory = sqlite3.Row
            columns = {row[1] for row in connection.execute("PRAGMA table_info(messages)")}
            if not {"id", "session_id", "role", "content"} <= columns:
                result.append(dict(id="native-schema:" + session["session_id"], kind="source_status",
                    title="Hermes会話の保存形式を確認してください", text="native_history_schema_unsupported", source="hermes_state"))
                continue
            # Include retained compacted originals; their content stays historical evidence.
            for message in connection.execute("SELECT id,role,content FROM messages WHERE session_id=? ORDER BY id DESC",
                                              (session["session_id"],)):
                if message["role"] not in {"user", "assistant"}:
                    continue
                text = message["content"]
                if not isinstance(text, str):
                    continue
                try:
                    parts = json.loads(text)
                    if isinstance(parts, list):
                        text = "\n".join(p.get("text", "") for p in parts if isinstance(p, dict) and p.get("type") in {"text", "input_text", "output_text"})
                except ValueError:
                    pass
                source_identity = hashlib.sha256(session["state_path"].encode()).hexdigest()[:12]
                result.append(dict(id="hermes-message:" + source_identity + ":" + session["session_id"] + ":" + str(message["id"]),
                    kind="messages", title=text[:80], text=text,
                    source="hermes_parent" if session.get("source_profile") else "hermes_state",
                    source_profile=session.get("source_profile", session.get("bot")),
                    role=message["role"], thread_id=session["thread_id"], session_id=session["session_id"],
                    evidence_type="historical_transcript"))
        except (OSError, sqlite3.Error):
            result.append(dict(id="native-unavailable:" + session["session_id"], kind="source_status",
                title="Hermes会話を取得できません", text="native_history_unavailable", source="hermes_state"))
        finally:
            if connection is not None:
                connection.close()
    return result

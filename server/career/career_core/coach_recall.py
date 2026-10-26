"""Deterministic, paged recall over the existing Career sources. No model or embeddings."""
import json
import re
from collections import Counter

from .context_bundle import select_context
from .shared import current_state
from .sharing import consultation_context
from .store import CareerError, SHARED, encoded


def _text(value):
    return value if isinstance(value, str) else encoded(value)


def _score(query, text):
    query, text = query.casefold().strip(), text.casefold()
    if not query:
        return 0
    words = re.findall(r"[\w-]+", query)
    # Japanese queries need substring recall without whitespace or a paid tokenizer.
    terms = set(words + [query])
    for word in words:
        if re.search(r"[^\x00-\x7f]", word):
            terms.update(word[i:i + 2] for i in range(len(word) - 1))
    return sum((20 if term == query else 2) for term in terms if term in text)


class CoachRecall:
    def __init__(self, coach):
        self.coach, self.store = coach, coach.store

    def records(self, run):
        owners = run["request"].get("history_profiles", [run["profile"]])
        if not isinstance(owners, list) or not owners or any(not isinstance(owner, str) for owner in owners):
            raise CareerError("invalid_history_scope", "履歴の参照先を確認してください。", 403)
        marks = ",".join("?" for _ in owners)
        with self.store.connect() as db:
            pinned = self.store.meta(db, "coach_shared_snapshot:" + run["id"])
            if pinned:
                material, policy = consultation_context(pinned, "operations")
            else:
                material, policy = select_context(current_state(self.store, db), "operations",
                    bundle=self.store.meta(db, "jobhunt_context_bundle"),
                    status=self.store.meta(db, "jobhunt_context_status", {"state": "not_imported"}))
            entities = self.store.snapshot()["entities"]
            if pinned:
                entities = [e for e in entities if e["kind"] not in SHARED] + pinned.get("entities", [])
            blocked = set(policy.get("blocked_ids", []))
            scope = run["request"].get("scope", {})
            company_id, offer_id = scope.get("company_id"), scope.get("offer_id")
            result = []
            seen = set()

            def add(identity, kind, title, body, **extra):
                if not identity or identity in blocked:
                    return
                if (kind, identity) in seen:
                    if extra.get("source") == "document_files":
                        result[:] = [row for row in result if (row["kind"], row["id"]) != (kind, identity)]
                    else:
                        return
                seen.add((kind, identity))
                result.append(dict(id=identity, kind=kind, title=title or identity, text=_text(body), **extra))

            # Current corrections/guards are always returned separately, never ranked away.
            for key, value in material.items():
                if key in {"facts", "stories", "hypotheses", "documents", "historical_documents"}:
                    for item in value if isinstance(value, list) else []:
                        identity = item.get("id") or next((item.get(k) for k in
                            ("fact_id", "story_id", "hypothesis_id", "document_id") if item.get(k)), None)
                        if key in {"documents", "historical_documents"} and company_id:
                            continue  # company-bound document visibility comes from scoped entities below
                        add(identity, key, item.get("title") or item.get("claim") or item.get("statement"), item,
                            source="shared_state", evidence_type="hypothesis" if key == "hypotheses" else "structured")
                elif key in {"candidate", "profile", "last_session", "decisions"} and value:
                    add("shared:" + key, key, key, value, source="shared_state")

            for entity in entities:
                identity, kind, data = entity["id"], entity["kind"], entity["data"]
                related = data.get("career_context", {})
                if not isinstance(related, dict):
                    related = {}
                company = identity if kind == "companies" else data.get("company_id", related.get("company_id"))
                offer = identity if kind == "offers" else data.get("offer_id", related.get("offer_id"))
                if company_id and kind not in {"profile", "facts", "stories", "hypotheses"} and company != company_id:
                    continue
                if offer_id and kind not in {"profile", "facts", "stories", "hypotheses", "companies"} and offer != offer_id:
                    continue
                if kind == "documents":
                    path = data.get("path")
                    if pinned:
                        body = pinned.get("document_bodies", {}).get(path, data.get("body", ""))
                    else:
                        row = db.execute("SELECT body FROM document_files WHERE path=?", (path,)).fetchone()
                        body = row[0] if row else data.get("body", "")
                    add(identity, kind, data.get("title"), body, source="document_files",
                        version=entity["version"], metadata=data, company_id=company, offer_id=offer)
                else:
                    add(identity, kind, data.get("title") or data.get("name"), data,
                        source="career_entities", version=entity["version"])

            # Every existing thread remains discoverable; never rewrite its IDs or content.
            for row in db.execute("SELECT * FROM coach_threads WHERE profile IN (" + marks + ") ORDER BY updated_at DESC,id", owners):
                if company_id and row["company_id"] != company_id or offer_id and row["offer_id"] != offer_id:
                    continue
                identity = row["profile"] + ":" + row["id"] if len(owners) > 1 else row["id"]
                add(identity, "conversations", row["title"], dict(row), source="coach_threads",
                    thread_id=row["id"], source_profile=row["profile"], archived=bool(row["archived"]))
            # Scope by thread metadata, not only the current thread, for company-wide recall.
            if company_id or offer_id:
                allowed_threads = {r["thread_id"] for r in result if r["kind"] == "conversations"}
                allowed_threads.add(run.get("thread_id", "global"))
            else:
                allowed_threads = None
            for row in db.execute("""SELECT m.id,m.role,m.text,m.created_at,m.profile,r.thread_id FROM coach_messages m
                    JOIN coach_runs r ON r.id=m.run_id WHERE m.profile IN (""" + marks + ") ORDER BY m.seq DESC", owners):
                if allowed_threads is not None and row["thread_id"] not in allowed_threads:
                    continue
                add(row["id"], "messages", row["text"][:80], row["text"], source="coach_messages",
                    role=row["role"], thread_id=row["thread_id"], source_profile=row["profile"], created_at=row["created_at"], evidence_type="historical_transcript")
            for row in db.execute("SELECT profile,thread_id,checkpoint FROM coach_thread_context WHERE profile IN (" + marks + ")", owners):
                if allowed_threads is None or row["thread_id"] in allowed_threads:
                    add("checkpoint:" + (row["profile"] + ":" if len(owners) > 1 else "") + row["thread_id"],
                        "checkpoints", row["thread_id"], json.loads(row["checkpoint"]),
                        source="coach_thread_context", thread_id=row["thread_id"], source_profile=row["profile"])
        from .native_history import records as native_records
        for row in native_records(self.store, run, allowed_threads):
            if (row["kind"], row["id"]) not in seen:
                result.append(row)
        return result, policy

    def read(self, args, run):
        offset, limit, position = args.get("offset", 0), args.get("limit", 30), args.get("text_offset", 0)
        if any(type(x) is not int for x in (offset, limit, position)) or offset < 0 or not 1 <= limit <= 100 or position < 0:
            raise CareerError("invalid_page", "取得位置を確認してください。")
        query = args.get("query", "")
        if not isinstance(query, str) or len(query) > 1000:
            raise CareerError("invalid_query", "検索語を確認してください。")
        records, policy = self.records(run)
        counts = dict(Counter(row["kind"] for row in records))
        identity, kind, thread = args.get("id"), args.get("kind"), args.get("thread_id")
        records = [row for row in records if (not identity or row["id"] == identity)
                   and (not kind or row["kind"] == kind) and (not thread or row.get("thread_id") == thread)]
        if query:
            records = [dict(row, relevance=_score(query, row["title"] + "\n" + row["text"])) for row in records]
            records = sorted((row for row in records if row["relevance"]), key=lambda row: (-row["relevance"], row["id"]))
        total, items = len(records), []
        for row in records[offset:offset + limit]:
            length = len(row["text"])
            size = 16000 if identity else 700
            items.append(dict(row, text=row["text"][position:position + size], characters=length,
                              next_text_offset=position + size if position + size < length else None))
        return {"items": items, "total": total, "counts": counts, "offset": offset,
                "next_offset": offset + limit if offset + limit < total else None,
                "context_constraints": policy,
                "source_policy": "本人の訂正・利用制限を優先。過去発言・Botの解釈は現行の本人事実と区別する。",
                "more_sources": "career_contextで企業別メール・マイページ案内・添付設問をページ取得できる。"}

    def bootstrap(self, run, query="", maximum=28000):
        """Coverage before relevance: all category counts and a paged retrieval contract."""
        page = self.read({"query": query[:1000], "limit": 25}, run)
        overview = self.read({"limit": 100}, run)
        all_records, _ = self.records(run)
        candidates = page["items"] + [dict(row, text=row["text"][:700], characters=len(row["text"]),
            next_text_offset=700 if len(row["text"]) > 700 else None) for row in all_records]
        result = {"counts": overview["counts"], "context_constraints": overview["context_constraints"],
                  "retrieval": "career_recall(query/kind/thread_id,offset,limit)。全文はidとtext_offset、続きはnext_offset。全履歴・全資料を探せる。",
                  "source_policy": overview["source_policy"], "items": [], "coverage_complete": False}
        seen = set()
        # Fair category round-robin stops one large category hiding every other source.
        groups = {}
        for row in candidates:
            key = (row["kind"], row["id"])
            if key not in seen:
                seen.add(key)
                groups.setdefault(row["kind"], []).append(row)
        while any(groups.values()):
            for values in groups.values():
                if not values:
                    continue
                row = values.pop(0)
                if len(encoded(result)) + len(encoded(row)) + 100 > maximum:
                    continue
                result["items"].append(row)
        result["loaded_items"] = len(result["items"])
        result["total_items"] = sum(overview["counts"].values())
        result["coverage_complete"] = result["loaded_items"] == result["total_items"] and all(
            row["next_text_offset"] is None for row in result["items"])
        return result

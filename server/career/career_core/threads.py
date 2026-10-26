"""Conversation metadata; history stays in the existing coach tables."""
import uuid

from .store import CareerError, now


def install(db):
    db.execute("""CREATE TABLE IF NOT EXISTS coach_threads(
        profile TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL,
        company_id TEXT, offer_id TEXT, archived INTEGER NOT NULL DEFAULT 0,
        version INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
        PRIMARY KEY(profile,id))""")


def migrate_legacy(store, db, profile):
    ids = {"global"}
    ids.update(r[0] for r in db.execute("SELECT DISTINCT thread_id FROM coach_runs WHERE profile=?", (profile,)))
    ids.update(r[0] for r in db.execute("SELECT thread_id FROM coach_thread_context WHERE profile=?", (profile,)))
    for identity in ids:
        if identity != "global" and not identity.startswith(("company:", "offer:")):
            continue
        company = offer = None
        title = "これまでの相談"
        if identity != "global":
            kind, value = identity.split(":", 1)
            entity = store.entity(db, "companies" if kind == "company" else "offers", value)
            company = value if kind == "company" else (entity or {}).get("data", {}).get("company_id")
            offer = value if kind == "offer" else None
            title = ((entity or {}).get("data", {}).get("name") or (entity or {}).get("data", {}).get("title") or "関連先不明") + "：これまでの相談"
        stamp = now()
        db.execute("INSERT OR IGNORE INTO coach_threads(profile,id,title,company_id,offer_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                   (profile, identity, title, company, offer, stamp, stamp))


def list_threads(coach, profile):
    from .coach_store import profile_key
    profile = profile_key(profile)
    with coach.store.transaction() as db:
        migrate_legacy(coach.store, db, profile)
        rows = db.execute("""SELECT t.*,
            (SELECT m.text FROM coach_messages m JOIN coach_runs r ON r.id=m.run_id
             WHERE r.profile=t.profile AND r.thread_id=t.id ORDER BY m.seq DESC LIMIT 1) AS last_message,
            (SELECT MAX(r.updated_at) FROM coach_runs r WHERE r.profile=t.profile AND r.thread_id=t.id) AS activity_at,
            (SELECT r.state FROM coach_runs r WHERE r.profile=t.profile AND r.thread_id=t.id
             AND r.state IN ('queued','running','paused','waiting_user')
             ORDER BY CASE r.state WHEN 'waiting_user' THEN 0 WHEN 'paused' THEN 1 ELSE 2 END,r.created_at LIMIT 1) AS run_state
            FROM coach_threads t WHERE t.profile=?""", (profile,)).fetchall()
        result = []
        for row in rows:
            item = dict(row)
            item['archived'] = bool(item['archived'])
            item['updated_at'] = max(item.pop('activity_at') or '', item['updated_at'])
            item['last_message'] = (item['last_message'] or '')[:160]
            item['context_missing'] = bool(item['company_id'] and not coach.store.entity(db, 'companies', item['company_id'])) or bool(item['offer_id'] and not coach.store.entity(db, 'offers', item['offer_id']))
            company = coach.store.entity(db, 'companies', item['company_id']) if item['company_id'] else None
            offer = coach.store.entity(db, 'offers', item['offer_id']) if item['offer_id'] else None
            item['company_name'] = (company or {}).get('data', {}).get('name') or (company or {}).get('data', {}).get('title')
            item['offer_title'] = (offer or {}).get('data', {}).get('title')
            result.append(item)
        return {"api_version": 1, "profile": profile, "threads": sorted(result, key=lambda r: (r['updated_at'], r['id']), reverse=True)}


def create_thread(coach, payload, profile):
    from .coach_store import profile_key
    profile = profile_key(profile)
    title = payload.get('title', '新しい相談')
    if not isinstance(title, str) or not 1 <= len(title.strip()) <= 120:
        raise CareerError('invalid_title', '会話名を1〜120文字で入力してください。')
    company, offer = payload.get('company_id'), payload.get('offer_id')
    if any(value is not None and (not isinstance(value, str) or not 1 <= len(value) <= 200) for value in (company, offer)):
        raise CareerError('invalid_target', '相談する企業・募集を選び直してください。')
    request = dict(action='create-thread', profile=profile, title=title.strip(), company_id=company, offer_id=offer)
    def apply(db):
        resolved = company
        if offer:
            entity = coach.store.entity(db, 'offers', offer)
            if not entity or (company and company != entity['data'].get('company_id')):
                raise CareerError('target_scope_conflict', '募集と企業の関連を確認してください。', 409)
            resolved = entity['data'].get('company_id')
        if (resolved and not coach.store.entity(db, 'companies', resolved)) or (offer and not resolved):
            raise CareerError('target_missing', '企業が見つかりません。', 404)
        identity, stamp = 'thread:' + str(uuid.uuid4()), now()
        db.execute('INSERT INTO coach_threads(profile,id,title,company_id,offer_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?)',
                   (profile, identity, title.strip(), resolved, offer, stamp, stamp))
        return dict(db.execute('SELECT * FROM coach_threads WHERE profile=? AND id=?', (profile, identity)).fetchone(), archived=False)
    return coach.store.operation(payload.get('operation_id'), request, apply)


def update_thread(coach, identity, payload, profile):
    from .coach_store import profile_key
    profile = profile_key(profile)
    if set(payload) - {'operation_id', 'expected_version', 'title', 'archived'}:
        raise CareerError('invalid_update', '企業・募集の関連は変更できません。')
    title = payload.get('title')
    if title is not None and (not isinstance(title, str) or not 1 <= len(title.strip()) <= 120):
        raise CareerError('invalid_title', '会話名を1〜120文字で入力してください。')
    if 'archived' in payload and type(payload['archived']) is not bool:
        raise CareerError('invalid_archive', '会話の表示状態を選び直してください。')
    def apply(db):
        migrate_legacy(coach.store, db, profile)
        row = db.execute('SELECT * FROM coach_threads WHERE profile=? AND id=?', (profile, identity)).fetchone()
        if not row:
            raise CareerError('thread_missing', '会話が見つかりません。', 404)
        if type(payload.get('expected_version')) is not int or row['version'] != payload['expected_version']:
            raise CareerError('version_conflict', '会話が更新されています。再読込してください。', 409)
        if payload.get('archived') and db.execute("SELECT 1 FROM coach_runs WHERE profile=? AND thread_id=? AND state IN ('queued','running','paused','waiting_user')", (profile, identity)).fetchone():
            raise CareerError('thread_busy', '作業を完了・停止してからアーカイブしてください。', 409)
        db.execute('UPDATE coach_threads SET title=?,archived=?,version=version+1,updated_at=? WHERE profile=? AND id=?',
                   (title.strip() if title is not None else row['title'], payload.get('archived', row['archived']), now(), profile, identity))
        row = dict(db.execute('SELECT * FROM coach_threads WHERE profile=? AND id=?', (profile, identity)).fetchone())
        row['archived'] = bool(row['archived'])
        return row
    return coach.store.operation(payload.get('operation_id'), dict(payload, action='update-thread', id=identity, profile=profile), apply)

"""One-company App workbench. Disabled unless an operator injects an exact grant.

The editable Bot is a tiny interpreted capability language expressed as Python;
uploaded Python is NEVER imported, compiled to executable code, or evaluated.
No shell, network, arbitrary path, credential or deployment capability exists.
The separate code-state DB is atomic with operation receipts. Existing Career
data, credentials and recipes are not copied into this workspace or changed.
"""
import ast
import contextlib
import copy
import difflib
import hashlib
import json
import re
import sqlite3
from pathlib import Path

from fastapi import APIRouter, BackgroundTasks, HTTPException, Request
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool

from .mobile_contract import validate_protocol
from .mobile_sdui import MobileRuntime, RUNTIME_ROOT
from .store import CareerError

FILES = ('protocol.json', 'mobile_screen.json', 'mypage_handler.py')
MODES = ('enqueue_existing', 'enqueue_ready')
LIMIT = 65536


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def digest(value):
    return hashlib.sha256(canonical(value).encode('utf-8')).hexdigest()


def refuse(code, status=409):
    raise CareerError(code, 'App operation refused.', status)


def bot_mode(source):
    from .mobile_bot_policy import kind
    return kind(source)


class RestrictedRuntime(MobileRuntime):
    def __init__(self, files, release_id):
        if not isinstance(files, dict) or set(files) != set(FILES):
            raise ValueError('exact_files_required')
        if any(not isinstance(v, str) or not v or len(v.encode('utf-8')) > LIMIT for v in files.values()):
            raise ValueError('file_too_large')
        if len(canonical(files).encode('utf-8')) > 200000:
            raise ValueError('bundle_too_large')
        self.protocol = json.loads(files['protocol.json'])
        validate_protocol(self.protocol)
        self.protocol['release_id'] = release_id
        self.template = json.loads(files['mobile_screen.json'])
        if not isinstance(self.template, dict) or set(self.template) != {'title', 'components'}:
            raise ValueError('unsupported_template')
        from .mobile_bot_policy import parse, evaluate
        body = parse(files['mypage_handler.py'])
        self.enqueue = lambda adapter, company_id, profile: evaluate(body, adapter, company_id, profile)
        # Validate all allowed bindings without invoking the worker or real data.
        company = {'id': 'synthetic', 'data': {'name': 'Synthetic'}}
        for state in ('idle', 'auth_required', 'queued', 'running', 'collecting', 'analyzing'):
            self.screen(company, 'default', {'status': {'state': state}})
        self.screen(company, 'default', None)
        self.screen({'id': 'synthetic', 'data': {'user_stage': 'closed'}}, 'default', {'status': {'state': 'idle'}})


class AppWorkbench:
    """Explicit operator injection only. Never discovered or enabled by the API.

    root must be an already allocated, dedicated empty code workspace. Creation
    or grant activation in production is an operator action, not an HTTP action.
    """
    def __init__(self, root, company_id, profile, *, apply_probe=None, editor_runner=None, editor_execution=None, editor_auto_run=False):
        self.root = Path(root).absolute()
        if not self.root.is_dir() or any(p.is_symlink() or getattr(p, "is_junction", lambda: False)() for p in [self.root, *self.root.parents]):
            raise ValueError('dedicated_workspace_required')
        if not company_id or not profile:
            raise ValueError('exact_target_required')
        self.company_id, self.profile = company_id, profile
        self.apply_probe = apply_probe or (lambda runtime: None)
        self.db_path = self.root / 'code-state.sqlite'
        if self.db_path.is_symlink():
            raise ValueError('unsafe_workspace')
        with self.locked() as db:
            db.execute('CREATE TABLE IF NOT EXISTS state (id INTEGER PRIMARY KEY CHECK(id=1), value TEXT NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS releases (id TEXT PRIMARY KEY, files TEXT NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS operations (id TEXT PRIMARY KEY, hash TEXT NOT NULL, receipt TEXT NOT NULL)')
            row = db.execute('SELECT value FROM state WHERE id=1').fetchone()
            if row:
                value = json.loads(row[0])
                if (value['company_id'], value['profile']) != (company_id, profile):
                    raise ValueError('grant_target_changed')
            else:
                files = {f: (RUNTIME_ROOT / f).read_text(encoding='utf-8') for f in FILES}
                active = 'wb-' + digest(files)[:48]
                RestrictedRuntime(files, active)
                db.execute('INSERT INTO releases VALUES (?,?)', (active, canonical(files)))
                self.save(db, dict(company_id=company_id, profile=profile, active=active, previous=None,
                                   revision=0, files=None, goal='', tested=None))

        from .mobile_editor import EditorJobs
        self.editor = EditorJobs(self, editor_runner, editor_execution, editor_auto_run)

    @contextlib.contextmanager
    def locked(self):
        db = sqlite3.connect(self.db_path, timeout=5)
        try:
            db.execute('BEGIN IMMEDIATE')
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    def matches(self, company_id, profile):
        return (company_id, profile) == (self.company_id, self.profile)

    def guard(self, company_id, profile):
        if not self.matches(company_id, profile):
            refuse('app_edit_disabled', 403)

    @staticmethod
    def load(db):
        return json.loads(db.execute('SELECT value FROM state WHERE id=1').fetchone()[0])

    @staticmethod
    def save(db, state):
        db.execute('INSERT OR REPLACE INTO state VALUES (1,?)', (canonical(state),))

    @staticmethod
    def release_files(db, identity):
        row = db.execute('SELECT files FROM releases WHERE id=?', (identity,)).fetchone()
        if not row:
            raise ValueError('missing_release')
        files = json.loads(row[0])
        if identity != 'wb-' + digest(files)[:48]:
            raise ValueError('changed_release')
        return files

    def runtime(self, db=None):
        if db is None:
            with self.locked() as connection:
                return self.runtime(connection)
        state = self.load(db)
        return RestrictedRuntime(self.release_files(db, state['active']), state['active'])

    def snapshot(self, company_id, profile):
        self.guard(company_id, profile)
        with self.locked() as db:
            state = self.load(db)
            active = self.release_files(db, state['active'])
            files = state['files'] or active
            diff = ''.join(''.join(difflib.unified_diff(active[f].splitlines(True), files[f].splitlines(True), fromfile=f, tofile=f)) for f in FILES)
            diff_bytes = diff.encode('utf-8')
            return dict(protocol_version=1, company_id=company_id, profile=profile, active=state['active'],
                        previous=state['previous'], revision=state['revision'], draft=state['files'] is not None,
                        goal=state['goal'], files=files, diff=diff_bytes[:16384].decode('utf-8', errors='ignore'), diff_truncated=len(diff_bytes)>16384,
                        tested=state['tested'] is not None and state['tested'] == digest(files))

    def receipt(self, company_id, profile, operation_id):
        self.guard(company_id, profile)
        if not re.fullmatch(r'[A-Za-z0-9_-]{16,80}', operation_id):
            refuse('invalid_operation', 400)
        with self.locked() as db:
            row = db.execute('SELECT receipt FROM operations WHERE id=?', (operation_id,)).fetchone()
            return json.loads(row[0]) if row else dict(protocol_version=1, company_id=company_id, profile=profile,
                                                       operation_id=operation_id, state='unknown', request_hash='', code='')

    def mutate(self, company_id, profile, payload, idle):
        self.guard(company_id, profile)
        if not isinstance(payload, dict):
            refuse('invalid_operation', 400)
        required = {'protocol_version', 'operation_id', 'action', 'expected_active', 'revision'}
        action = payload.get('action')
        extras = {'request': {'goal', 'title', 'mode'}, 'edit': {'files'}, 'test': set(), 'apply': set(), 'apply_reviewed': {'review_hash'},
                  'rollback': set(), 'cancel': set(), 'propose': {'goal'}, 'cancel_editor': set(), 'fence': {'request_hash', 'original_action'}}
        if action not in extras or set(payload) != required | extras[action] or type(payload.get('protocol_version')) is not int or payload['protocol_version'] != 1:
            refuse('invalid_operation', 400)
        op = payload['operation_id']
        if (not isinstance(op, str) or not re.fullmatch(r'[A-Za-z0-9_-]{16,80}', op)
                or not isinstance(payload['expected_active'], str) or not re.fullmatch(r'wb-[a-f0-9]{48}', payload['expected_active'])
                or type(payload['revision']) is not int or not 0 <= payload['revision'] <= 2147483647):
            refuse('invalid_operation', 400)
        request_hash = digest(payload)
        if action == 'fence':
            request_hash = payload['request_hash']
            if not isinstance(request_hash, str) or not re.fullmatch(r'[a-f0-9]{64}', request_hash) or payload['original_action'] not in set(extras)-{'fence'}:
                refuse('invalid_operation', 400)
        with self.locked() as db:
            previous = db.execute('SELECT hash,receipt FROM operations WHERE id=?', (op,)).fetchone()
            if previous:
                if previous[0] != request_hash:
                    refuse('operation_conflict')
                return json.loads(previous[1])
            state = self.load(db)
            code, outcome = '', 'done'
            if action == 'fence':
                outcome = 'cancelled'
            elif (state['active'], state['revision']) != (payload['expected_active'], payload['revision']):
                code, outcome = 'edit_conflict', 'refused'
            else:
                before = copy.deepcopy(state)
                try:
                    self.change(db, state, action, payload, idle)
                except CareerError as error:
                    state = before
                    code, outcome = error.code, 'refused'
                except (ValueError, TypeError, KeyError, SyntaxError, RecursionError):
                    state = before
                    code, outcome = 'test_failed' if action == 'test' else 'invalid_candidate', 'refused'
                except Exception:
                    state = before
                    code, outcome = 'apply_failed' if action == 'apply' else 'operation_failed', 'refused'
            self.save(db, state)
            receipt = dict(protocol_version=1, company_id=company_id, profile=profile, operation_id=op,
                           request_hash=request_hash, state=outcome, code=code)
            if action == 'cancel_editor' and outcome == 'done':
                receipt['editor_job_id'] = state['editor_job']
            db.execute('INSERT INTO operations VALUES (?,?,?)', (op, request_hash, canonical(receipt)))
            return receipt

    def change(self, db, state, action, payload, idle):
        self.editor.guard_manual(db, state, 'apply' if action == 'apply_reviewed' else action)
        if action == 'propose':
            self.editor.propose(db, state, payload)
        elif action == 'cancel_editor':
            self.editor.cancel(db, state)
        elif action == 'request':
            if state['files'] is not None:
                refuse('draft_exists')
            goal, title, mode = payload['goal'], payload['title'], payload['mode']
            if (not isinstance(goal, str) or not goal.strip() or len(goal) > 2000
                    or not isinstance(title, str) or not title.strip() or len(title) > 200 or mode not in MODES):
                raise ValueError('invalid_request')
            files = self.release_files(db, state['active'])
            screen = json.loads(files['mobile_screen.json'])
            screen['title'] = title
            files['mobile_screen.json'] = json.dumps(screen, ensure_ascii=False, indent=2) + '\n'
            files['mypage_handler.py'] = f'def enqueue(adapter, company_id, profile):\n    return adapter.{mode}(company_id, profile)\n'
            state.update(files=files, goal=goal, tested=None, revision=state['revision']+1)
        elif action == 'edit':
            if state['files'] is None:
                refuse('draft_missing')
            files = payload['files']
            if not isinstance(files, dict) or set(files) != set(FILES) or any(not isinstance(v, str) or not v or len(v.encode('utf-8')) > LIMIT for v in files.values()):
                raise ValueError('exact_files_required')
            if len(canonical(files).encode('utf-8')) > 200000:
                raise ValueError('bundle_too_large')
            state.update(files=files, tested=None, revision=state['revision']+1)
            self.editor.record(db, state, 'edit')
        elif action == 'test':
            if state['files'] is None:
                refuse('draft_missing')
            runtime = RestrictedRuntime(state['files'], 'wb-' + digest(state['files'])[:48])
            from .mobile_bot_policy import STATES, REFUSALS
            for status in sorted(STATES):
                class SyntheticRecords:
                    def get(self, identity):
                        return None if status == 'not_connected' else {'status': {'state': status}}
                class SyntheticAdapter:
                    records = SyntheticRecords()
                    company_id, profile = 'synthetic', 'default'
                    calls = 0
                    def enqueue_existing(self, company_id, profile):
                        if (company_id, profile) != ('synthetic', 'default'):
                            raise ValueError('target_changed')
                        self.calls += 1
                        return 'synthetic-run'
                adapter = SyntheticAdapter()
                try:
                    if runtime.enqueue(adapter, 'synthetic', 'default') != 'synthetic-run' or adapter.calls != 1:
                        raise ValueError('bot_contract_failed')
                except CareerError as failure:
                    if status == 'idle' or failure.code not in REFUSALS or adapter.calls:
                        raise ValueError('bot_contract_failed') from None
            state.update(tested=digest(state['files']), revision=state['revision']+1)
            self.editor.record(db, state, 'test', digest(state['files']))
        elif action in {'apply', 'apply_reviewed'}:
            if action == 'apply_reviewed' and (not isinstance(payload['review_hash'], str) or payload['review_hash'] != digest(state['files'])):
                refuse('review_changed')
            if state['files'] is None or state['tested'] != digest(state['files']):
                refuse('test_required')
            if not idle():
                refuse('mypage_busy')
            identity = 'wb-' + digest(state['files'])[:48]
            if identity == state['active']:
                refuse('empty_change')
            runtime = RestrictedRuntime(state['files'], identity)
            self.apply_probe(runtime)
            db.execute('INSERT OR IGNORE INTO releases VALUES (?,?)', (identity, canonical(state['files'])))
            if self.release_files(db, identity) != state['files']:
                raise ValueError('release_changed')
            self.editor.record(db, state, 'apply', digest(state['files']))
            state.update(active=identity, previous=state['active'], files=None, tested=None, goal='', revision=state['revision']+1)
        elif action == 'rollback':
            if not state['previous'] or state['files'] is not None:
                refuse('rollback_unavailable')
            if not idle():
                refuse('mypage_busy')
            RestrictedRuntime(self.release_files(db, state['previous']), state['previous'])
            state.update(active=state['previous'], previous=state['active'], revision=state['revision']+1)
            self.editor.record(db, state, 'rollback')
        elif action == 'cancel':
            self.editor.record(db, state, 'cancel')
            state.update(files=None, tested=None, goal='', revision=state['revision']+1)


def create_workbench_router(store_factory, workbench=None):
    router = APIRouter()
    if workbench is not None:
        router.add_event_handler('shutdown', workbench.editor.close)

    def target(company_id, profile):
        from .coach_store import profile_key
        profile = profile_key(profile)
        if workbench is None:
            refuse('app_edit_disabled', 403)
        workbench.guard(company_id, profile)
        store = store_factory()
        with store.connect() as db:
            company = store.entity(db, 'companies', company_id)
        if not company or company['data'].get('deleted_at'):
            refuse('company_missing', 404)
        return store, profile

    def handle(call):
        try:
            return JSONResponse(call(), headers={"Cache-Control": "no-store, private", "Pragma": "no-cache", "X-Content-Type-Options": "nosniff"})
        except CareerError as error:
            raise HTTPException(error.status, detail={'code': error.code}) from None
        except Exception:
            raise HTTPException(503, detail={'code': 'workbench_unavailable'}) from None

    @router.get('/mobile/workbench/{company_id}')
    def read(company_id: str, profile: str = 'default'):
        def call():
            _, normalized = target(company_id, profile)
            return workbench.snapshot(company_id, normalized)
        return handle(call)

    @router.get('/mobile/workbench/{company_id}/operations/{operation_id}')
    def receipt(company_id: str, operation_id: str, profile: str = 'default'):
        def call():
            _, normalized = target(company_id, profile)
            return workbench.receipt(company_id, normalized, operation_id)
        return handle(call)

    @router.get('/mobile/workbench/{company_id}/editor-job')
    def editor_job(company_id: str, profile: str = 'default'):
        def call():
            _, normalized = target(company_id, profile)
            return workbench.editor.summary(company_id, normalized)
        return handle(call)

    @router.post('/mobile/workbench/{company_id}')
    async def write(company_id: str, request: Request, background: BackgroundTasks, profile: str = 'default'):
        if ('origin' in request.headers or any(h.lower().startswith('sec-fetch-') for h in request.headers)
                or request.headers.get('x-hermes-app-workbench') != 'once'
                or request.headers.get('content-type', '').split(';')[0].strip().lower() != 'application/json'):
            raise HTTPException(403, detail={'code': 'native_workbench_required'})
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > 240000:
                raise HTTPException(413, detail={'code': 'payload_too_large'})
        try:
            payload = json.loads(body)
        except (ValueError, UnicodeError):
            raise HTTPException(400, detail={'code': 'invalid_operation'}) from None
        def call():
            from .mypage_bot import MyPageBotStore, connection_id
            store, normalized = target(company_id, profile)
            def idle():
                row = MyPageBotStore(store).get(connection_id(company_id, normalized))
                return not row or (row.get('status') or {}).get('state') not in {'queued', 'running', 'collecting', 'analyzing'}
            receipt = workbench.mutate(company_id, normalized, payload, idle)
            if receipt['state'] == 'done' and payload.get('action') == 'propose' and workbench.editor.auto_run:
                background.add_task(workbench.editor.run, payload['operation_id'])
            if receipt['state'] == 'done' and payload.get('action') == 'cancel_editor' and receipt.get('editor_job_id'):
                background.add_task(workbench.editor.interrupt_cancelled, receipt['editor_job_id'])
            return receipt
        return await run_in_threadpool(handle, call)

    return router

"""App SDUI API, mounted behind the existing Dashboard session boundary.

The packaged runtime is the default. Alternative releases are injected only by
the synthetic harness; no HTTP edit/apply endpoint or runtime editing grant.
"""
import contextlib
import copy
import hashlib
import importlib.util
import json
import re
from pathlib import Path

from fastapi import APIRouter, BackgroundTasks, HTTPException, Request
from starlette.concurrency import run_in_threadpool

from .mobile_contract import APP_ID, validate_components, validate_protocol, validate_screen
from .store import CareerError

RUNTIME_ROOT = Path(__file__).resolve().parents[1] / 'app_runtime'


class MobileRuntime:
    def __init__(self, root=RUNTIME_ROOT, *, release_id=None):
        self.root = Path(root)
        self.protocol = json.loads((self.root / 'protocol.json').read_text(encoding='utf-8'))
        if release_id:
            self.protocol['release_id'] = release_id
        validate_protocol(self.protocol)
        self.template = json.loads((self.root / 'mobile_screen.json').read_text(encoding='utf-8'))
        if set(self.template) != {'title', 'components'}:
            raise ValueError('unsupported_template')
        path = self.root / 'mypage_handler.py'
        spec = importlib.util.spec_from_file_location('career_mypage_release_' + hashlib.sha256(path.read_bytes()).hexdigest(), path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.enqueue = module.enqueue

    def manifest(self):
        return dict(self.protocol, permissions=['mypage.read', 'mypage.check'], route='company')

    def screen(self, company, profile, connection):
        status = (connection or {}).get('status') or {}
        state = status.get('state', 'not_connected' if not connection else 'idle')
        busy = state in {'queued', 'running', 'collecting', 'analyzing'}
        closed = company['data'].get('user_stage') == 'closed'
        text = ('選考が終了しています。' if closed else 'マイページの接続を設定してください。' if not connection
                else '確認中です。表示を更新して状況を確認できます。' if busy
                else 'ログインの確認が必要です。' if state == 'auth_required' else '手動で確認できます。')
        bindings = {'$company_name': str(company['data'].get('name', '企業'))[:2000], '$status_text': text,
                    '$status_tone': 'warning' if closed or not connection or state == 'auth_required' else 'info',
                    '$can_check': bool(connection) and not busy and not closed}

        def bind(value):
            if isinstance(value, dict):
                return {k: bind(v) for k, v in value.items()}
            if isinstance(value, list):
                return [bind(v) for v in value]
            return bindings.get(value, value) if isinstance(value, str) else value
        result = dict(self.protocol, company_id=company['id'], profile=profile,
                      title=self.template['title'], state=str(state), components=bind(copy.deepcopy(self.template['components'])))
        validate_screen(result)
        if len(json.dumps(result, ensure_ascii=False).encode('utf-8')) > 65536:
            raise ValueError('screen_too_large')
        return result


class ExistingMyPageAdapter:
    def __init__(self, records, company_id, profile):
        self.records = records
        self.company_id, self.profile = company_id, profile
        self.result = None

    def enqueue_existing(self, company_id, profile):
        from .mypage_bot import connection_id
        if (company_id, profile) != (self.company_id, self.profile):
            raise CareerError('mobile_target_mismatch', '操作の対象が一致しません。', 409)
        if self.result is not None:
            return self.result
        row = self.records.get(connection_id(company_id, profile))
        if not row:
            raise CareerError('mypage_not_connected', 'マイページを設定してください。', 409)
        run, created = self.records.enqueue(row['id'], with_created=True)
        self.result = row, run, created
        return self.result


def create_mobile_router(store_factory, *, run_worker=False, runtime=None, workbench=None):
    router = APIRouter()
    if runtime is None:
        try:
            runtime = MobileRuntime()
        except Exception:
            # A broken App release must not disable existing native Career APIs.
            # No import/config/exception text is returned to a client or logged.
            runtime = None

    def require_runtime():
        if runtime is None:
            raise HTTPException(503, detail={'code': 'mobile_contract_unavailable'})

    def selected(company_id, profile):
        return workbench.runtime() if workbench is not None and workbench.matches(company_id, profile) else runtime

    def normalized_profile(profile):
        from .coach_store import profile_key
        return profile_key(profile)

    def company(store, identity):
        with store.connect() as db:
            row = store.entity(db, 'companies', identity)
        if not row or row['data'].get('deleted_at'):
            raise CareerError('company_missing', '企業が見つかりません。', 404)
        return row

    @router.get('/mobile/manifest')
    def manifest(profile: str = 'default', company_id: str = ''):
        require_runtime()
        try:
            profile = normalized_profile(profile)
        except CareerError as error:
            raise HTTPException(error.status, detail={'code': error.code}) from None
        return selected(company_id, profile).manifest()

    @router.get('/mobile/sdui/company/{company_id}')
    def screen(company_id: str, profile: str = 'default'):
        from .mypage_bot import MyPageBotStore, connection_id
        require_runtime()
        try:
            profile = normalized_profile(profile)
            store = store_factory()
            return selected(company_id, profile).screen(company(store, company_id), profile,
                                  MyPageBotStore(store).get(connection_id(company_id, profile)))
        except CareerError as error:
            raise HTTPException(error.status, detail={'code': error.code}) from None
        except (ValueError, TypeError, KeyError):
            raise HTTPException(503, detail={'code': 'mobile_contract_unavailable'}) from None

    def check_sync(body, background, profile):
        from .mypage_bot import MyPageBotStore
        from .mypage_cron import HermesMyPageCron
        require_runtime()
        try:
            payload = json.loads(body)
            if not isinstance(payload, dict) or set(payload) != {'protocol_version', 'release_id', 'company_id', 'operation_id'}:
                raise ValueError()
            if type(payload['protocol_version']) is not int or payload['protocol_version'] != 1:
                raise ValueError()
            if not isinstance(payload['release_id'], str) or not re.fullmatch(r'[A-Za-z0-9._-]{1,80}', payload['release_id']):
                raise ValueError()
            if not isinstance(payload['company_id'], str) or not 1 <= len(payload['company_id']) <= 200:
                raise ValueError()
            if not isinstance(payload['operation_id'], str) or not re.fullmatch(r'[A-Za-z0-9_-]{16,80}', payload['operation_id']):
                raise ValueError()
            profile = normalized_profile(profile)
            store = store_factory()
            records = MyPageBotStore(store)
            target = company(store, payload['company_id'])
            target_runtime = selected(target['id'], profile)
            operation_key = 'mobile_mypage_operation:' + hashlib.sha256((profile + ':' + payload['operation_id']).encode()).hexdigest()
            # Atomic operation receipts: one thread reserves the operation while
            # existing enqueue supplies independent active-run deduplication.
            with store.transaction() as db:
                previous = store.meta(db, operation_key, None)
                if previous:
                    if previous['company_id'] != target['id'] or previous['release_id'] != payload['release_id']:
                        raise CareerError('operation_conflict', '操作の対象が一致しません。', 409)
                    if previous.get('receipt'):
                        return previous['receipt']
                    if previous.get('error'):
                        raise CareerError(previous['error'], '操作は受け付けられませんでした。', previous['status'])
                    raise CareerError('operation_pending', '受付を確認中です。', 503)
                if payload['release_id'] != target_runtime.protocol['release_id']:
                    raise CareerError('mobile_release_changed', '表示を更新してください。', 409)
                store.set_meta(db, operation_key, {'company_id': target['id'], 'release_id': payload['release_id']})
            try:
                adapter = ExistingMyPageAdapter(records, target['id'], profile)
                # Serialize this company's enqueue with code pointer changes.
                guard = workbench.locked() if workbench is not None and workbench.matches(target['id'], profile) else contextlib.nullcontext(None)
                with guard as code_db:
                    target_runtime = workbench.runtime(code_db) if code_db is not None else target_runtime
                    if payload['release_id'] != target_runtime.protocol['release_id']:
                        raise CareerError('mobile_release_changed', '表示を更新してください。', 409)
                    returned = target_runtime.enqueue(adapter, target['id'], profile)
                if adapter.result is None or returned != adapter.result:
                    raise CareerError('mobile_bot_contract_failed', '受付を確認できません。', 503)
                row, run, created = adapter.result
                if run_worker and run['state'] == 'queued':
                    cron = HermesMyPageCron(records)
                    try:
                        claim = cron.claim(row)
                        background.add_task(cron.fire, claim, run)
                    except CareerError as error:
                        if error.code != 'collection_running' or created:
                            records.finish(run, 'failed', 'collection_interrupted')
                            raise CareerError('collection_interrupted', '実行を開始できませんでした。', 503) from None
                        # Another request already owns this existing queued Run.
                        # Never fail it or schedule it twice.
                    except Exception:
                        records.finish(run, 'failed', 'collection_interrupted')
                        raise CareerError('collection_interrupted', '実行を開始できませんでした。', 503) from None
                receipt = dict(protocol_version=1, release_id=payload['release_id'], company_id=target['id'], profile=profile,
                               operation_id=payload['operation_id'], state=run['state'], run_id=run['id'])
                with store.transaction() as db:
                    store.set_meta(db, operation_key, {'company_id': target['id'], 'release_id': payload['release_id'], 'receipt': receipt})
                return receipt
            except CareerError as error:
                # Preserve the operation's target and definitive rejection. A
                # lost response cannot turn this ID into another company's write.
                with store.transaction() as db:
                    store.set_meta(db, operation_key, {'company_id': target['id'], 'release_id': payload['release_id'], 'error': error.code, 'status': error.status})
                raise
        except CareerError as error:
            raise HTTPException(error.status, detail={'code': error.code}) from None
        except (ValueError, TypeError, KeyError, json.JSONDecodeError):
            raise HTTPException(400, detail={'code': 'invalid_mobile_action'}) from None

    @router.post('/mobile/actions/mypage.check')
    async def check(request: Request, background: BackgroundTasks, profile: str = 'default'):
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > 4096:
                raise HTTPException(413, detail={'code': 'payload_too_large'})
        return await run_in_threadpool(check_sync, bytes(body), background, profile)
    return router

"""No unauthenticated standalone service. Parent Dashboard supplies the session."""
import json
import os
from urllib.parse import urlsplit

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Request

from .importing import import_bundle
from .store import CareerError, Store
from .shared import correct_fact, save_document
from .sync import GitHub, resolve_conflict, synchronize


def hosted_profile():
    """The profile whose plugin host runs this router, or None when it runs in the Dashboard itself."""
    if os.environ.get("HERMES_PLUGIN_HOST_PROCESS") != "1":
        return None
    try:
        from hermes_constants import get_hermes_home, profile_name_for_home
        return profile_name_for_home(get_hermes_home())
    except Exception:
        return None


def request_profile(profile: str | None = None) -> str:
    # The Dashboard drops ``?profile=`` before forwarding to a profile's own plugin host,
    # so the host's home is what names the profile there.
    return profile or hosted_profile() or "default"


def require_session(request: Request):
    # In-process: the gated Dashboard puts its verified session on this request. Under
    # ``plugins.isolation: host`` the router runs in a child app instead; the parent has already
    # applied the same guard and only forwards its decision as ``hosted_http_authenticated``.
    hosted = getattr(request.state, "hosted_http_authenticated", None) is True
    in_process = getattr(request.app.state, "auth_required", False) and getattr(request.state, "session", None)
    if not (hosted or in_process):
        raise HTTPException(401, detail={"code": "dashboard_session_required"})
    origin = request.headers.get("origin")
    if request.method != "GET" and origin and urlsplit(origin).netloc != request.url.netloc:
        raise HTTPException(403, detail={"code": "cross_origin_write"})


def sync_job(store):
    remote = None
    try:
        remote = GitHub()
        return synchronize(store, remote)
    except CareerError as error:
        with store.transaction() as db:
            status = {"state": "unsynced", "reason": error.code}
            store.set_meta(db, "sync_status", status)
        return status
    finally:
        if remote is not None:
            remote.client.close()


def create_router(store_factory=Store, *, run_worker=False, browser_consent=False, mobile_runtime=None, mobile_workbench=None):
    router = APIRouter(dependencies=[Depends(require_session)])
    from .google_connection import create_google_router
    router.include_router(create_google_router())

    # Plugin bootstrap wires the real Session adapter. The operator flag remains
    # OFF by default; after authorized activation every input needs native consent.
    # Test/other hosts keep their existing explicitly injected manager.
    consent_access = None
    if browser_consent:
        from .browser_consent import BrowserConsentAccess
        consent_access = BrowserConsentAccess()

        @router.on_event('shutdown')
        def close_browser_consent():
            consent_access.close()

    def browser_manager(request):
        if consent_access is not None:
            request.app.state.career_browser_access = consent_access
            return consent_access
        return getattr(request.app.state, 'career_browser_access', None)

    def browser_request_guard(request):
        # Hermes is served on a trusted LAN/Tailscale; HTTP is the intended
        # transport there, so TLS is not required for these native requests.
        # Native OkHttp sends no browser Origin / Fetch Metadata. Reject even
        # same-origin Dashboard scripts using ambient cookies, not just CSRF.
        if 'origin' in request.headers or any(h.lower().startswith('sec-fetch-') for h in request.headers):
            raise HTTPException(403, detail={'code': 'browser_native_consent_required'})
        # Non-simple header also prevents a cross-origin HTML form from sending
        # this request. No CORS is added. This is not device attestation.
        if request.headers.get('x-hermes-browser-consent') != 'once':
            raise HTTPException(403, detail={'code': 'browser_native_consent_required'})
        if request.headers.get('content-type', '').split(';', 1)[0].strip().lower() != 'application/json':
            raise HTTPException(415, detail={'code': 'browser_json_required'})

    @router.post('/mypage/browser-login-locate')
    async def locate_browser_login(request: Request):
        from fastapi.responses import JSONResponse
        from starlette.concurrency import run_in_threadpool
        from .browser_login_locator import locate_login
        browser_request_guard(request)
        try:
            body = bytearray()
            async for chunk in request.stream():
                body.extend(chunk)
                if len(body) > 32768:
                    raise HTTPException(413, detail={'code': 'payload_too_large'})
            result = await run_in_threadpool(locate_login, store_factory(), json.loads(body))
            return JSONResponse(result, headers={'Cache-Control': 'no-store, private', 'Pragma': 'no-cache',
                                                'Referrer-Policy': 'no-referrer', 'X-Content-Type-Options': 'nosniff'})
        except CareerError as error:
            raise HTTPException(error.status, detail={'code': error.code}) from None
        except (ValueError, TypeError, RecursionError):
            raise HTTPException(400, detail={'code': 'invalid_request'}) from None
        except HTTPException:
            raise
        except Exception:
            raise HTTPException(503, detail={'code': 'login_locator_unavailable'}) from None

    @router.post('/mypage/browser-access/prepare')
    async def prepare_browser_consent(request: Request):
        from fastapi.responses import JSONResponse
        from starlette.concurrency import run_in_threadpool
        browser_request_guard(request)
        try:
            body = bytearray()
            async for chunk in request.stream():
                body.extend(chunk)
                if len(body) > 8192:
                    raise HTTPException(413, detail={'code': 'payload_too_large'})
            payload = json.loads(body)
            manager = browser_manager(request)
            if consent_access is None or manager is not consent_access:
                raise CareerError('browser_credentials_disabled', 'Browser confirmation unavailable.', 403)
            result = await run_in_threadpool(manager.prepare, store_factory(), payload, request.state.session)
            return JSONResponse(result, headers={'Cache-Control': 'no-store, private', 'Pragma': 'no-cache',
                                                'Referrer-Policy': 'no-referrer', 'X-Content-Type-Options': 'nosniff'})
        except HTTPException:
            raise
        except CareerError as error:
            raise HTTPException(error.status, detail={'code': error.code}) from None
        except (ValueError, TypeError, RecursionError):
            raise HTTPException(400, detail={'code': 'invalid_request'}) from None
        except Exception:
            raise HTTPException(503, detail={'code': 'browser_consent_unavailable'}) from None

    @router.post("/mypage/browser-credentials")
    async def mypage_browser_credentials(request: Request):
        from fastapi.responses import JSONResponse
        from starlette.concurrency import run_in_threadpool
        from .mypage_browser_credentials import browser_credentials
        if consent_access is not None:
            browser_request_guard(request)
        if request.headers.get("content-type", "").split(";", 1)[0].strip().lower() != "application/json":
            raise HTTPException(415, detail={"code": "browser_json_required"})
        try:
            body = bytearray()
            async for chunk in request.stream():
                body.extend(chunk)
                if len(body) > 8192:
                    raise HTTPException(413, detail={"code": "payload_too_large"})
            payload = json.loads(body)
            def retrieve():
                from .browser_access import BrowserAccessManager
                store = store_factory()
                manager = browser_manager(request)
                if consent_access is not None:
                    if not isinstance(payload, dict) or set(payload) != {'company_id', 'profile', 'expected_version', 'url', 'approval_id', 'consent_mode'}:
                        raise CareerError('invalid_request', 'Check the confirmation request.', 400)
                    approval_id = payload.pop('approval_id')
                    consent_mode = payload.pop('consent_mode')
                    grant = manager.approve_and_issue(store, payload, request.state.session, approval_id, consent_mode)
                else:
                    grant = manager.issue(store, payload, request.state.session) if isinstance(manager, BrowserAccessManager) else None
                return browser_credentials(store, payload, grant=grant, session=request.state.session)
            result = await run_in_threadpool(retrieve)
            return JSONResponse(result, headers={"Cache-Control": "no-store, private", "Pragma": "no-cache",
                                                 "Referrer-Policy": "no-referrer", "X-Content-Type-Options": "nosniff"})
        except HTTPException:
            raise
        except CareerError as error:
            raise HTTPException(error.status, detail={"code": error.code}) from None
        except Exception:
            raise HTTPException(400, detail={"code": "browser_credentials_unavailable"}) from None

    @router.get("/mypage/browser-access")
    @router.post("/mypage/browser-access/revoke")
    async def browser_access_control(request: Request):
        from .browser_access import BrowserAccessManager
        from starlette.concurrency import run_in_threadpool
        manager = browser_manager(request)
        if not isinstance(manager, BrowserAccessManager):
            from fastapi.responses import JSONResponse
            result = {"enabled": False, "grants": []} if request.method == "GET" else {"revoked": 0, "disabled": True}
            return JSONResponse(result, headers={"Cache-Control": "no-store, private", "Pragma": "no-cache", "X-Content-Type-Options": "nosniff", "Referrer-Policy": "no-referrer"})
        try:
            if request.method == "GET":
                result = await run_in_threadpool(manager.status, request.state.session)
            else:
                if consent_access is not None:
                    browser_request_guard(request)
                if request.headers.get("content-type", "").split(";", 1)[0].strip().lower() != "application/json":
                    raise HTTPException(415, detail={"code": "browser_json_required"})
                body = bytearray()
                async for chunk in request.stream():
                    body.extend(chunk)
                    if len(body) > 2048:
                        raise HTTPException(413, detail={"code": "payload_too_large"})
                payload = json.loads(body)
                if not isinstance(payload, dict) or set(payload) not in (set(), {"company_id", "profile"}):
                    raise HTTPException(400, detail={"code": "invalid_request"})
                if payload and not all(isinstance(payload[i], str) for i in ("company_id", "profile")):
                    raise HTTPException(400, detail={"code": "invalid_request"})
                result = await run_in_threadpool(manager.revoke, request.state.session, **payload)
            from fastapi.responses import JSONResponse
            return JSONResponse(result, headers={"Cache-Control": "no-store, private", "Pragma": "no-cache", "X-Content-Type-Options": "nosniff", "Referrer-Policy": "no-referrer"})
        except CareerError as error:
            raise HTTPException(error.status, detail={"code": error.code}) from None
        except (ValueError, TypeError, RecursionError):
            raise HTTPException(400, detail={"code": "invalid_request"}) from None

    def coach_store():
        from .coach_store import CoachStore
        return CoachStore(store_factory())

    @router.get("/home/content")
    def home_content(id: str, version: str, profile: str = Depends(request_profile)):
        from .home import HomeView
        try:
            return HomeView(store_factory()).content(id, version, profile)
        except CareerError as error:
            raise HTTPException(error.status, detail={"code": error.code, "detail": error.detail}) from None

    @router.get("/home")
    def home(profile: str = Depends(request_profile), cursor: str | None = None):
        from .home import HomeView
        try:
            return HomeView(store_factory()).read(profile, cursor)
        except CareerError as error:
            raise HTTPException(error.status, detail={"code": error.code, "detail": error.detail}) from None

    @router.get("/companies")
    def companies(profile: str = Depends(request_profile)):
        from .company_views import CompanyViews
        try:
            return CompanyViews(store_factory()).overview(profile)
        except CareerError as error:
            raise HTTPException(error.status, detail={"code": error.code, "detail": error.detail}) from None

    @router.get("/companies/{company_id}")
    def company_detail(company_id: str, profile: str = Depends(request_profile)):
        from .company_views import CompanyViews
        try:
            return CompanyViews(store_factory()).detail(company_id, profile)
        except CareerError as error:
            raise HTTPException(error.status, detail={"code": error.code, "detail": error.detail}) from None

    @router.get("/collection/notices")
    def collection_notices(after: int = 0, profile: str = Depends(request_profile), baseline: bool = False):
        from .collection_store import CollectionStore
        try:
            return CollectionStore(store_factory()).notices(after, profile, baseline)
        except CareerError as error:
            raise HTTPException(error.status, detail={"code": error.code, "detail": error.detail}) from None

    @router.get("/collection/notices/{notice_id}")
    def collection_notice_content(notice_id: str, profile: str = Depends(request_profile)):
        from .collection_store import CollectionStore
        try:
            return CollectionStore(store_factory()).notice_content(notice_id, profile)
        except CareerError as error:
            raise HTTPException(error.status, detail={"code": error.code, "detail": error.detail}) from None

    @router.get("/collection/connections")
    def collection_connections():
        from .mail_store import connection_view
        return connection_view(store_factory())

    @router.get("/collection/mail-status")
    def collection_mail_status():
        from .mail_store import MailStore
        return MailStore(store_factory()).sync_view()

    @router.post("/collection/client-mail")
    async def collection_client_mail(request: Request, profile: str = Depends(request_profile)):
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > 4 * 1024 * 1024:
                raise HTTPException(413, detail={"code": "payload_too_large"})
        try:
            payload = json.loads(body)
            from .client_mail import ClientMailImporter
            return ClientMailImporter(store_factory()).ingest(payload)
        except CareerError as error:
            raise HTTPException(error.status, detail={"code": error.code, "detail": error.detail}) from None
        except (ValueError, TypeError, KeyError, UnicodeError):
            raise HTTPException(400, detail={"code": "invalid_request"}) from None

    @router.post("/collection/client-mail-cache")
    async def collection_client_mail_cache(request: Request):
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > 16000:
                raise HTTPException(413, detail={"code": "payload_too_large"})
        try:
            from .client_mail import ClientMailImporter
            return ClientMailImporter(store_factory()).reuse(json.loads(body))
        except CareerError as error:
            raise HTTPException(error.status, detail={"code": error.code, "detail": error.detail}) from None
        except (ValueError, TypeError, KeyError, UnicodeError):
            raise HTTPException(400, detail={"code": "invalid_request"}) from None

    @router.get("/companies/{company_id}/mail")
    def company_mail_page(company_id: str, cursor: str = ""):
        from .mail_store import MailStore
        try:
            return MailStore(store_factory()).message_page(company_id, cursor)
        except (ValueError, TypeError, UnicodeError):
            raise HTTPException(400, detail={"code": "invalid_cursor"}) from None

    @router.post("/collection/{action}")
    async def collection_mutation(action: str, request: Request, background: BackgroundTasks, profile: str = Depends(request_profile)):
        from .collection_store import CollectionStore
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > (262144 if action == "company-order" else 16000):
                raise HTTPException(413, detail={"code": "payload_too_large"})
        try:
            payload = json.loads(body)
            if not isinstance(payload, dict):
                raise ValueError()
            store = store_factory()
            collection = CollectionStore(store)
            if action == "company-order":
                from .company_order import save_order
                return save_order(store, payload, profile)
            if action == "mypage-settings":
                from .mypage_bot import MyPageBotStore
                from .mypage_cron import reconcile
                if not isinstance(payload.get("company_id"), str):
                    raise ValueError()
                records = MyPageBotStore(store)
                row = records.configure(payload["company_id"], profile, payload)
                if run_worker:
                    reconcile(records, row)
                return {"mypage": records.view(row["company_id"], profile)}
            if action == "source-resolved":
                from .home import HomeView
                if set(payload) != {"id", "version"} or not all(isinstance(v, str) for v in payload.values()):
                    raise ValueError()
                return HomeView(store).resolve_source(payload["id"], payload["version"], profile)
            if action == "mypage-check":
                if set(payload) != {"company_id"} or not isinstance(payload["company_id"], str):
                    raise ValueError()
                from .mypage import MyPageStore
                from .mypage_bot import MyPageBotStore, connection_id
                from .mypage_cron import HermesMyPageCron
                records = MyPageBotStore(store)
                row = records.get(connection_id(payload["company_id"], profile))
                if row:
                    run, created = records.enqueue(row["id"], with_created=True)
                    if run["state"] == "running":
                        return {"state": "running", "run_id": run["id"]}
                    if run_worker:
                        try:
                            cron = HermesMyPageCron(records)
                            claim = cron.claim(row)
                            background.add_task(cron.fire, claim, run)
                        except CareerError as error:
                            if error.code == "collection_running" and not created:
                                return {"state": run["state"], "run_id": run["id"]}
                            code = "collection_interrupted" if error.code == "collection_running" else error.code
                            records.finish(run, "failed", code)
                            raise CareerError(code, "確認を開始できませんでした。少し待ってから再試行してください。", error.status) from None
                        except Exception as error:
                            from .mypage_job import report_failure
                            report_failure(error, "cron_claim", run["id"])
                            records.finish(run, "failed", "collection_interrupted")
                            raise CareerError("collection_interrupted", "確認を開始できませんでした。サーバーの実行履歴を確認してください。", 503) from None
                    return {"state": "queued", "run_id": run["id"]}
                from starlette.concurrency import run_in_threadpool
                return await run_in_threadpool(MyPageStore(store).collect, payload["company_id"], profile)
            if action == "company-preferences":
                if set(payload) != {"company_id", "expected_version", "priority", "stage"}:
                    raise ValueError()
                if payload["priority"] not in {"unknown", "high", "medium", "low"} or payload["stage"] not in {"unknown", "considering", "preparing", "applied", "interview", "offer", "closed"}:
                    raise ValueError()
                with store.transaction() as db:
                    entity = store.entity(db, "companies", payload["company_id"])
                    if not entity:
                        raise CareerError("company_missing", "企業が見つかりません。", 404)
                    result = store.put(db, "companies", entity["id"], payload["expected_version"],
                        dict(entity["data"], user_priority=payload["priority"], user_stage=payload["stage"]))
                if payload["stage"] == "closed":
                    from .mypage_cron import stop_company
                    stop_company(store, payload["company_id"])
                return {"company": result}
            if action == "settings":
                if type(payload.get("enabled")) is not bool or payload.get("channel") != "mail":
                    raise ValueError()
                if payload["enabled"] and payload.get("account_id"):
                    from .workspace_reader import connected_account
                    if payload["account_id"] != connected_account(payload['account_id']):
                        raise CareerError("mail_account_mismatch", "Hermesの接続済みGmailが変更されています。接続画面を開き直してください。", 409)
                from .mail_store import MailStore
                mail = MailStore(store).configure(payload.get("account_id"), payload["enabled"])
                if run_worker and payload["enabled"]:
                    from .mail_worker import worker as mail_worker
                    mail_worker(store).start()
                return {"mail": mail}
            if action == "mail-sender":
                if set(payload) != {"company_id", "address", "enabled"}:
                    raise ValueError()
                from .mail_store import MailStore
                result = MailStore(store).configure_sender(payload["company_id"], payload["address"], payload["enabled"])
                if run_worker and payload["enabled"]:
                    from .mail_worker import worker as mail_worker
                    mail_worker(store).start()
                return {"sender": result}
            if action == "seen":
                return collection.mark_seen(payload["company_id"], profile, payload["cursor"])
            if action == "check":
                if payload.get("channel") != "mail":
                    raise ValueError()
                from .mail_store import MailStore
                if not MailStore(store).settings().get("enabled"):
                    raise CareerError("mail_collection_disabled", "メールの定期取得を有効にしてください。", 409)
                ticket = MailStore(store).request_sync()
                if run_worker:
                    from .mail_worker import worker as mail_worker
                    mail_worker(store).start()
                return dict(ticket, runs=[{"channel": "mail", "state": "queued"}])
            raise HTTPException(404)
        except CareerError as error:
            raise HTTPException(error.status, detail={"code": error.code, "detail": error.detail}) from None
        except (ValueError, TypeError, KeyError):
            raise HTTPException(400, detail={"code": "invalid_request"}) from None

    if run_worker:
        @router.on_event("startup")
        def start_coach():
            from .coach_worker import worker
            worker(store_factory()).start()
            from .mail_worker import worker as mail_worker
            mail_worker(store_factory()).start()

        @router.on_event("shutdown")
        def stop_coach():
            from .coach_worker import worker
            worker(store_factory()).close()
            from .mail_worker import worker as mail_worker
            mail_worker(store_factory()).close()

    @router.get("/coach/threads")
    def coach_threads(profile: str = Depends(request_profile)):
        from .threads import list_threads
        try:
            return list_threads(coach_store(), profile)
        except CareerError as error:
            raise HTTPException(error.status, detail={"code": error.code, "detail": error.detail}) from None

    async def thread_payload(request):
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > 10000:
                raise HTTPException(413)
        try:
            payload = json.loads(body)
            if not isinstance(payload, dict):
                raise ValueError()
            return payload
        except (ValueError, TypeError):
            raise HTTPException(400) from None

    @router.post("/coach/threads")
    async def coach_create_thread(request: Request, profile: str = Depends(request_profile)):
        from .threads import create_thread
        payload = await thread_payload(request)
        try:
            return create_thread(coach_store(), payload, profile)
        except CareerError as error:
            raise HTTPException(error.status, detail={"code": error.code, "detail": error.detail}) from None

    @router.patch("/coach/threads/{thread_id}")
    async def coach_update_thread(thread_id: str, request: Request, profile: str = Depends(request_profile)):
        from .threads import update_thread
        payload = await thread_payload(request)
        try:
            return update_thread(coach_store(), thread_id, payload, profile)
        except CareerError as error:
            raise HTTPException(error.status, detail={"code": error.code, "detail": error.detail}) from None

    @router.get("/coach/conversation")
    def coach_conversation(profile: str = Depends(request_profile), before: int | None = None, thread_id: str = "global"):
        try:
            return coach_store().snapshot(profile, before=before, thread_id=thread_id)
        except CareerError as error:
            raise HTTPException(error.status, detail={"code": error.code, "detail": error.detail}) from None

    @router.get("/coach/events")
    def coach_events(profile: str = Depends(request_profile), after: int = 0, thread_id: str = "global"):
        try:
            return coach_store().events(profile, after, thread_id)
        except CareerError as error:
            raise HTTPException(error.status, detail={"code": error.code, "detail": error.detail}) from None

    @router.get("/coach/capabilities")
    def coach_capabilities(profile: str = Depends(request_profile), refresh: bool = False):
        from .coach_store import profile_key
        try:
            return coach_store().execution.capabilities(profile_key(profile), refresh)
        except CareerError as error:
            raise HTTPException(error.status, detail={"code": error.code, "detail": error.detail}) from None

    @router.get("/coach/daily-settings")
    def coach_daily_settings(profile: str = Depends(request_profile)):
        from .coach_store import profile_key
        try:
            return coach_store().execution.daily(profile_key(profile))
        except CareerError as error:
            raise HTTPException(error.status, detail={"code": error.code, "detail": error.detail}) from None

    @router.post("/coach/thread/{action}")
    @router.post("/coach/{action}")
    async def coach_mutation(action: str, request: Request, profile: str = Depends(request_profile)):
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > 100000:
                raise HTTPException(413, detail={"code": "payload_too_large"})
        try:
            payload = json.loads(body)
            if not isinstance(payload, dict):
                raise ValueError("Object required")
            coach = coach_store()
            if action == "message":
                result = coach.submit(payload, profile)
            elif action == "research":
                result = coach.submit(payload, profile, kind="research")
            elif action == "control":
                result = coach.control(payload, profile)
            elif action == "daily-settings":
                from .coach_store import profile_key
                result = coach.execution.update_daily(payload, profile_key(profile))
            else:
                raise HTTPException(404)
            if run_worker and action != "daily-settings":
                from .coach_worker import worker
                active_worker = worker(coach.store)
                if action == "control" and payload.get("control") == "stop":
                    active_worker.cancel(payload["id"])
                active_worker.start()
            return result
        except CareerError as error:
            raise HTTPException(error.status, detail={"code": error.code, "detail": error.detail}) from None
        except (ValueError, TypeError, KeyError):
            raise HTTPException(400, detail={"code": "invalid_request"}) from None

    @router.get("/snapshot")
    def snapshot():
        from .collection_store import document_context
        store = store_factory()
        result = store.snapshot()
        # Read-only links let Materials open the same offer/question workspace as Companies.
        with store.connect() as db:
            result["entities"] = [dict(entity, data=dict(entity["data"], career_context=document_context(
                db, entity["id"], entity["data"].get("version", 1)))) if entity["kind"] == "documents" else entity
                for entity in result["entities"]]
        return result

    @router.get("/document/{document_id}/{version}")
    def document(document_id: str, version: int):
        import re
        if not re.fullmatch(r"DOC-\d{3,}", document_id) or not 1 <= version <= 999999:
            raise HTTPException(400, detail={"code": "invalid_document"})
        path = f".jobhunt/documents/{document_id}-v{version:03d}.md"
        with store_factory().connect() as db:
            row = db.execute("SELECT body,checksum,synced FROM document_files WHERE path=?", (path,)).fetchone()
            if not row:
                raise HTTPException(404, detail={"code": "document_missing"})
            return {"path": path, "body": row["body"], "checksum": row["checksum"],
                    "server_saved": True, "github_synced": bool(row["synced"]), "version": version}

    @router.get("/migration-preview")
    def migration_preview():
        from .migration import preview
        from .shared import current_state
        store = store_factory()
        with store.connect() as db:
            source = current_state(store, db)
        return preview(source)

    @router.post("/{action}")
    async def mutation(action: str, request: Request, background: BackgroundTasks):
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > 10 * 1024 * 1024:
                raise HTTPException(413, detail={"code": "payload_too_large"})
        try:
            payload = json.loads(body)
            if not isinstance(payload, dict):
                raise ValueError("Object required")
            store = store_factory()
            if action == "answer":
                from .es_work import mutate_answer
                result = mutate_answer(store, payload)
                if payload.get("intent", "save") == "save":
                    background.add_task(sync_job, store)
                return result
            if action in {"correct", "document"}:
                result = correct_fact(store, payload) if action == "correct" else save_document(store, payload)
                background.add_task(sync_job, store)
                return result
            if action == "save":
                result = store.save(payload)
                if payload["kind"] in {"profile", "facts", "stories", "hypotheses", "documents"}:
                    background.add_task(sync_job, store)
                return result
            if action == "delete-company":
                from .company_lifecycle import delete_company
                return delete_company(store, payload)
            if action == "decide":
                return store.decide(payload)
            if action == "import":
                return import_bundle(store, payload)
            if action == "approve":
                result = store.approve(payload)
                background.add_task(sync_job, store)
                return result
            if action == "resolve":
                result = resolve_conflict(store, payload)
                background.add_task(sync_job, store)
                return result
            if action == "sync":
                background.add_task(sync_job, store)
                return {"state": "queued"}
            if action == "research":
                import uuid
                from .coach_worker import worker
                result = coach_store().submit({"operation_id": payload.get("operation_id") or str(uuid.uuid4()),
                    "text": "保存済みのマイページ案内・企業メールから変更点と次の行動を整理してください。公開サイトの巡回や新規取得は行いません。"}, request_profile(request.query_params.get("profile")), kind="research")
                if run_worker:
                    worker(store).start()
                return result
            raise HTTPException(404)
        except CareerError as error:
            raise HTTPException(error.status, detail={"code": error.code, "detail": error.detail}) from None
        except (ValueError, TypeError, KeyError):
            raise HTTPException(400, detail={"code": "invalid_request"}) from None

    from .mobile_sdui import create_mobile_router
    router.include_router(create_mobile_router(store_factory, run_worker=run_worker, runtime=mobile_runtime, workbench=mobile_workbench))
    from .mobile_workbench import create_workbench_router
    router.include_router(create_workbench_router(store_factory, mobile_workbench))
    return router

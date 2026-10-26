"""Authenticated Dashboard plugin API; no public server or Relay route."""
import re
import sqlite3
import threading
from contextlib import closing
from pathlib import Path
from stat import S_ISREG
from urllib.parse import urlsplit

from fastapi import APIRouter, Depends, HTTPException, Request

from group_service import CliRuntime, GroupError, GroupService


def read_history(path):
    """Read committed history without starting or taking ownership of a worker."""
    path = Path(path)
    try:
        try:
            metadata = path.stat()
        except FileNotFoundError:
            return []
        if not S_ISREG(metadata.st_mode):
            raise GroupError("history_unavailable", 503)
        with closing(sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True, timeout=2)) as db:
            db.row_factory = sqlite3.Row
            return [dict(id=row["id"], groupName=row["group_name"], title=row["title"],
                         createdAt=row["created"], updatedAt=row["updated"])
                    for row in db.execute(
                        "SELECT id, group_name, title, created, updated FROM conversations ORDER BY updated DESC")]
    except (OSError, sqlite3.Error):
        raise GroupError("history_unavailable", 503) from None


def require_session(request: Request):
    if not getattr(request.app.state, "auth_required", False) or not getattr(request.state, "session", None):
        raise HTTPException(401, detail={"code": "dashboard_session_required"})
    origin = request.headers.get("origin")
    if request.method != "GET" and origin and urlsplit(origin).netloc != request.url.netloc:
        raise HTTPException(403, detail={"code": "cross_origin_write"})


def create_router(factory=None):
    router = APIRouter(dependencies=[Depends(require_session)])
    services, lock = {}, threading.Lock()
    discovery_stop = threading.Event()

    def service(profile):
        with lock:
            if profile not in services:
                if factory:
                    services[profile] = factory(profile)
                else:
                    from hermes_cli.profiles import get_profile_dir, profile_exists
                    if not profile_exists(profile):
                        raise GroupError("profile_not_found", 404)
                    services[profile] = GroupService(get_profile_dir(profile) / "group_chat" / "history.db", CliRuntime(owner_profile=profile))
            return services[profile]

    def invoke(profile, action):
        try:
            if not re.fullmatch(r"[a-zA-Z0-9_-]{1,80}", profile):
                raise GroupError("invalid_profile", 422)
            return action(service(profile))
        except GroupError as error:
            raise HTTPException(error.status, detail={"code": error.code}) from None

    @router.on_event("startup")
    def recover_queues():
        if factory:
            return
        from hermes_cli.profiles import get_profile_dir, list_profile_names
        for profile in list_profile_names():
            if profile == "default" or (get_profile_dir(profile) / "group_chat" / "history.db").is_file():
                try:
                    service(profile)
                except GroupError:
                    # Another Dashboard process may already own this profile.
                    # Leave its durable queue untouched; never start a second worker.
                    continue

    @router.on_event("shutdown")
    def close():
        discovery_stop.set()
        if discovery_thread:
            discovery_thread.join(timeout=3)
        for value in services.values():
            value.close()

    @router.get("/conversations")
    def history(profile: str = "default"):
        if factory is not None:
            return invoke(profile, lambda s: {"conversations": s.history()})
        try:
            if not re.fullmatch(r"[a-zA-Z0-9_-]{1,80}", profile):
                raise GroupError("invalid_profile", 422)
            from hermes_cli.profiles import get_profile_dir, profile_exists
            if not profile_exists(profile):
                raise GroupError("profile_not_found", 404)
            return {"conversations": read_history(get_profile_dir(profile) / "group_chat" / "history.db")}
        except GroupError as error:
            raise HTTPException(error.status, detail={"code": error.code}) from None

    @router.get("/conversations/{conversation}")
    def snapshot(conversation: str, group: str, profile: str = "default"):
        return invoke(profile, lambda s: s.snapshot(conversation, group))

    @router.post("/conversations/{conversation}/messages")
    async def submit(conversation: str, request: Request, profile: str = "default"):
        if len(await request.body()) > 4_000_000:
            raise HTTPException(413)
        try:
            payload = await request.json()
        except ValueError:
            raise HTTPException(422) from None
        if not isinstance(payload, dict) or not re.fullmatch(r"[a-zA-Z0-9_-]{1,100}", conversation):
            raise HTTPException(422)
        return invoke(profile, lambda s: s.submit(conversation, payload))

    @router.patch("/conversations/{conversation}")
    async def rename(conversation: str, request: Request, profile: str = "default"):
        if len(await request.body()) > 16_384:
            raise HTTPException(413)
        try:
            payload = await request.json()
        except ValueError:
            raise HTTPException(422) from None
        if not isinstance(payload, dict) or not re.fullmatch(r"[a-zA-Z0-9_-]{1,100}", conversation):
            raise HTTPException(422)
        return invoke(profile, lambda s: s.rename(conversation, payload))

    @router.post("/conversations/{conversation}/stop")
    def stop(conversation: str, group: str, profile: str = "default"):
        return invoke(profile, lambda s: s.stop(conversation, group))

    # Dashboard versions can mount plugins from inside their own startup hook,
    # after FastAPI has already entered startup. Recover on import as well;
    # the per-profile service cache and OS lock prevent a second worker.
    if factory is None:
        recover_queues()
        def discover():
            while not discovery_stop.wait(5):
                recover_queues()
        discovery_thread = threading.Thread(target=discover, daemon=True, name="group-profile-discovery")
        discovery_thread.start()
    else:
        discovery_thread = None
    return router

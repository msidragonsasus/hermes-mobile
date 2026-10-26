import re
import threading
from urllib.parse import urlsplit

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, ConfigDict, StrictBool

from .runtime import synthesize
from .store import PlanError, PlanStore

try:
    from hermes_cli.plugin_host_http_auth import admitted_for_sensitive_route
except ImportError:
    admitted_for_sensitive_route = None


def resolve_home(profile):
    from hermes_cli.profiles import get_profile_dir, profile_exists
    from hermes_constants import get_hermes_home, profile_name_for_home
    import os
    if os.environ.get("HERMES_PLUGIN_HOST_PROCESS") == "1":
        home = get_hermes_home()
        actual = profile_name_for_home(home)
        if profile and profile != actual:
            raise PlanError("profile_mismatch", 403)
        return home, actual
    profile = profile or "default"
    if not re.fullmatch(r"[a-zA-Z0-9_-]{1,80}", profile) or not profile_exists(profile):
        raise PlanError("profile_missing", 404)
    return get_profile_dir(profile), profile


def require_session(request: Request):
    # The core has already validated native tickets, sessions or private host IPC.
    # Reuse its route guard; never re-consume tickets or trust principal shape.
    if admitted_for_sensitive_route is None:
        raise HTTPException(503, detail={"code": "standard_auth_unavailable"})
    if not admitted_for_sensitive_route(request):
        raise HTTPException(401, detail={"code": "dashboard_session_required"})
    origin = request.headers.get("origin")
    if request.method != "GET" and origin and urlsplit(origin).netloc != request.url.netloc:
        raise HTTPException(403, detail={"code": "cross_origin_write"})


class Refresh(BaseModel):
    model_config = ConfigDict(extra="forbid")
    request_id: str
    automatic: StrictBool = False


class Settings(BaseModel):
    model_config = ConfigDict(extra="forbid")
    email: str
    manaba_url: str
    enabled: StrictBool


class Completion(BaseModel):
    model_config = ConfigDict(extra="forbid")
    done: StrictBool


def create_router(resolver=resolve_home, model=synthesize, launch=None):
    router = APIRouter(dependencies=[Depends(require_session)])

    def invoke(profile, action):
        try:
            home, name = resolver(profile)
            return action(PlanStore(home), home, name)
        except PlanError as error:
            raise HTTPException(error.status, detail={"code": error.code}) from None

    def run(store, home, profile, request_id):
        try:
            answer = model(profile, store, request_id)
            inputs = store.cached_inputs(request_id)
            store.finish(request_id, inputs, answer)
        except Exception:
            store.fail(request_id)

    @router.get("/plan")
    def plan(profile: str | None = None):
        return invoke(profile, lambda store, home, name: store.snapshot())

    @router.get("/settings")
    def settings(profile: str | None = None):
        return invoke(profile, lambda store, home, name: store.settings())

    @router.put("/settings")
    def configure(body: Settings, profile: str | None = None):
        return invoke(profile, lambda store, home, name: store.configure(body.model_dump()))

    @router.post("/refresh")
    def refresh(body: Refresh, profile: str | None = None):
        def start(store, home, name):
            if store.begin(body.request_id, body.automatic):
                if launch:
                    launch(lambda: run(store, home, name, body.request_id))
                else:
                    threading.Thread(target=run, args=(store, home, name, body.request_id), daemon=True).start()
            return store.snapshot()
        return invoke(profile, start)

    @router.patch("/tasks/{identity}")
    def complete(identity: str, body: Completion, profile: str | None = None):
        def update(store, home, name):
            store.complete(identity, body.done)
            return store.snapshot()
        return invoke(profile, update)

    return router

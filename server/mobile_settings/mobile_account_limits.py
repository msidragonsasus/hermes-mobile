"""Read-only account-limit projection mounted in the authenticated Dashboard process.

Hermes owns provider credentials and quota fetching. This module never reads a
credential file, starts an agent, refreshes a token, or redeems a usage reset.
"""
import asyncio
from datetime import datetime, timezone
import inspect
import math

from fastapi import APIRouter, Depends, HTTPException, Query, Request


def unavailable(reason="usage_unavailable", provider=None):
    return {"available": False, "provider": provider, "windows": [], "unavailable_reason": reason}


def timestamp(value):
    if not isinstance(value, datetime) or value.tzinfo is None:
        return None
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def project(snapshot, provider):
    """Allowlist fields; raw provider bodies, details, identities and exceptions never leave here."""
    if snapshot is None or getattr(snapshot, "unavailable_reason", None):
        return unavailable(provider=provider)
    if getattr(snapshot, "provider", None) != provider:
        return unavailable("provider_mismatch", provider)
    windows = []
    for window in tuple(getattr(snapshot, "windows", ()) or ())[:20]:
        label = getattr(window, "label", None)
        if not isinstance(label, str) or not label.strip() or len(label) > 120:
            continue
        used = getattr(window, "used_percent", None)
        valid = type(used) in (int, float) and math.isfinite(used) and 0 <= used <= 100
        windows.append({"label": label, "used_percent": float(used) if valid else None,
                        "reset_at": timestamp(getattr(window, "reset_at", None))})
    checked = timestamp(getattr(snapshot, "fetched_at", None))
    if not windows or not checked:
        return unavailable(provider=provider)
    plan = getattr(snapshot, "plan", None)
    return {"available": True, "provider": provider,
            "plan": plan if isinstance(plan, str) and len(plan) <= 120 else None,
            "fetched_at": checked, "windows": windows}


def fetch_configured(config, fetcher):
    model = config.get("model")
    provider = model.get("provider") if isinstance(model, dict) else None
    # Never infer a provider from model-name substrings, installed apps or unrelated logins.
    if not isinstance(provider, str) or provider not in {"openai-codex", "anthropic", "openrouter"}:
        return unavailable("provider_not_supported")
    if "read_only" not in inspect.signature(fetcher).parameters:
        return unavailable("read_only_usage_unavailable", provider)
    return project(fetcher(provider, read_only=True), provider)


class CoreBackend:
    @staticmethod
    def authorize(request: Request):
        from hermes_cli.web_server import _require_token
        return _require_token(request)

    @staticmethod
    def read(profile):
        from agent.account_usage import fetch_account_usage
        from hermes_cli.config import load_config
        from hermes_cli.web_routers.mcp import _profile_scope
        with _profile_scope(profile):
            return fetch_configured(load_config(), fetch_account_usage)


def create_router(backend=None):
    backend = backend or CoreBackend()
    router = APIRouter(prefix="/api/mobile-settings")

    @router.get("/account-limits")
    async def account_limits(
        profile: str | None = Query(default=None, max_length=100),
        authorized=Depends(backend.authorize),
    ):
        try:
            return await asyncio.wait_for(asyncio.to_thread(backend.read, profile), timeout=20)
        except HTTPException:
            raise
        except Exception:
            # No provider exception text, URLs or raw response bodies in logs or HTTP output.
            return unavailable()

    return router

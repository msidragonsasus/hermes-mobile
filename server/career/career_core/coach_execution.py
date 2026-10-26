"""Explicit Hermes provider/model selection. Catalog discovery never performs inference."""
import os
from datetime import datetime, timezone

from .store import CareerError, now

PROVIDER = "openai-codex"
CODEX_BASE = "https://chatgpt.com/backend-api/codex"
FEATURES = ["conversation_threads", "execution_config", "daily_settings", "artifact_receipts", "document_revision", "company_threads", "company_views", "source_collection", "notice_feed", "es_workspace"]


def oauth_credentials():
    try:
        from hermes_cli.auth import resolve_codex_runtime_credentials
        value = resolve_codex_runtime_credentials()
    except Exception:
        raise CareerError("oauth_unavailable", "サーバーでCodex OAuthに再ログインしてください。", 503) from None
    if value.get("provider") != PROVIDER or value.get("base_url", "").rstrip("/") != CODEX_BASE or not value.get("api_key"):
        raise CareerError("paid_fallback_blocked", "既存Codex OAuth以外の接続は使用しません。", 503)
    return value


def wire_effort(model, effort):
    try:
        from agent.transports.codex import _resolve_reasoning
        actual, enabled = _resolve_reasoning(model, {
            "provider": PROVIDER, "base_url": CODEX_BASE,
            "reasoning_config": {"effort": effort, "enabled": True},
        })
    except (ImportError, AttributeError, TypeError):
        raise CareerError("hermes_contract_unsupported", "Hermesのモデル設定APIを確認してください。", 503) from None
    if not enabled or actual != effort:
        raise CareerError("reasoning_unsupported", "この推論強度はHermesで別の値になるため選択できません。", 409)
    return effort


def codex_account_headers(access_token):
    """Reuse Hermes' current account/residency headers, with older-host support."""
    try:
        from agent.codex_headers import codex_account_headers as resolve_headers
    except ImportError:
        try:
            from hermes_cli.codex_models import _extract_chatgpt_account_id
        except ImportError:
            raise CareerError("hermes_contract_unsupported", "Hermesのモデル設定APIを確認してください。", 503) from None
        account = _extract_chatgpt_account_id(access_token)
        return {"ChatGPT-Account-Id": account} if account else {}
    return resolve_headers(access_token)


def discover_codex_models():
    """Keep the account catalog's actual entries; never synthesize entitlement."""
    credentials = oauth_credentials()
    try:
        import httpx
        from hermes_cli.codex_models import _ranked_slugs
        from agent.reasoning_effort import codex_supported_efforts
        headers = {"Authorization": "Bearer " + credentials["api_key"],
                   **codex_account_headers(credentials["api_key"])}
        response = httpx.get(CODEX_BASE + "/models?client_version=1.0.0", headers=headers, timeout=10)
        if response.status_code in (401, 403):
            raise CareerError("oauth_unavailable", "サーバーのCodexログインを確認してください。", 503)
        response.raise_for_status()
        entries = response.json().get("models", [])
        slugs = _ranked_slugs(entries)
        models = []
        for slug in slugs:
            entry = next(item for item in entries if item.get("slug") == slug)
            published = entry.get("supported_reasoning_levels")
            efforts = [item.get("effort") for item in published if isinstance(item, dict)] if isinstance(published, list) else list(codex_supported_efforts(slug))
            accepted = []
            for effort in efforts:
                if not isinstance(effort, str):
                    continue
                try:
                    accepted.append(wire_effort(slug, effort))
                except CareerError as error:
                    if error.code != "reasoning_unsupported":
                        raise
            if accepted:
                models.append({"provider": PROVIDER, "id": slug, "label": slug,
                               "reasoning_efforts": accepted, "source": "account_catalog",
                               "inference_verified": False})
        if not models:
            raise CareerError("model_catalog_empty", "Codexのモデル候補を確認できません。再確認してください。", 503)
        return {"models": models, "checked_at": now(), "state": "available"}
    except CareerError:
        raise
    except Exception:
        raise CareerError("model_catalog_unavailable", "モデル一覧を取得できません。接続を確認して再試行してください。", 503) from None


def discover_models():
    """Use the same configured-provider inventory as Hermes' model picker."""
    try:
        from hermes_cli.inventory import build_model_options_payload, load_picker_context
        inventory = build_model_options_payload(load_picker_context(), include_unconfigured=False)
    except Exception:
        raise CareerError("model_catalog_unavailable", "Hermesのモデル一覧を取得できません。接続とサーバーの対応版を確認してください。", 503) from None
    models, failures = [], []
    for provider in inventory.get("providers", []):
        slug = provider.get("slug")
        if not slug or slug in {"auto", "moa"} or provider.get("authenticated") is False:
            continue
        if slug == PROVIDER:
            try:
                models.extend(discover_codex_models()["models"])
            except CareerError as error:
                failures.append({"provider": slug, "reason": error.code})
            continue
        for model in provider.get("models", []):
            if isinstance(model, str) and model.strip():
                models.append({"provider": slug, "id": model, "label": model,
                               "reasoning_efforts": ["default"], "source": "hermes_inventory",
                               "inference_verified": False})
    if not models:
        code = failures[0]["reason"] if failures else "model_catalog_empty"
        raise CareerError(code, "利用できるモデル候補がありません。Hermesのプロバイダー接続を確認してください。", 503)
    return {"models": models, "checked_at": now(), "state": "available", "provider_errors": failures}


def runtime_credentials(execution):
    """Resolve only the explicitly selected Hermes connection; never accept auto fallback."""
    if execution["provider"] == PROVIDER:
        return dict(oauth_credentials(), api_mode="codex_responses")
    try:
        from hermes_cli.runtime_provider import resolve_runtime_provider, has_named_custom_provider
        from hermes_cli.auth import resolve_provider
        value = resolve_runtime_provider(requested=execution["provider"], target_model=execution["model"])
        # Named custom connections use the shared 'custom' transport identity.
        expected = "custom" if has_named_custom_provider(execution["provider"]) else resolve_provider(execution["provider"])
    except Exception:
        raise CareerError("provider_unavailable", "選択したプロバイダーに接続できません。Hermesの接続設定を確認してください。", 503) from None
    if value.get("provider") != expected or value.get("model", execution["model"]) != execution["model"]:
        raise CareerError("execution_mismatch", "選択した接続と異なるため実行を中断しました。", 409)
    if value.get("api_mode") not in {"chat_completions", "anthropic_messages", "codex_responses"}:
        raise CareerError("hermes_contract_unsupported", "このプロバイダーの実行方式は就活Appでまだ対応していません。", 409)
    if not value.get("api_key") or not value.get("base_url"):
        raise CareerError("provider_unavailable", "選択したプロバイダーの接続設定を確認してください。", 503)
    return value


def default_execution():
    model = os.environ.get("CAREER_CODEX_MODEL", "").strip()
    if not model:
        raise CareerError("model_not_configured", "モデルを選択するか、サーバーの既定モデルを設定してください。", 503)
    # This is the previous Hermes adapter's explicit transport default, captured at acceptance.
    return {"provider": PROVIDER, "model": model,
            "reasoning_effort": os.environ.get("CAREER_CODEX_REASONING", "medium").strip()}


def canonical_execution(value):
    if not isinstance(value, dict) or set(value) != {"provider", "model", "reasoning_effort"}:
        raise CareerError("invalid_execution", "モデルと推論強度を選択してください。")
    if any(not isinstance(item, str) or not item.strip() or len(item) > 200 for item in value.values()):
        raise CareerError("invalid_execution", "モデル設定が不正です。")
    result = {key: item.strip() for key, item in value.items()}
    if result["provider"] in {"auto", "moa"}:
        raise CareerError("invalid_execution", "実行するプロバイダーとモデルを明示的に選択してください。", 409)
    return result


class ExecutionPolicy:
    def __init__(self, store):
        self.store = store

    def catalog(self, refresh=False):
        with self.store.connect() as db:
            cached = self.store.meta(db, "coach_model_catalog")
        if cached and not refresh:
            age = (datetime.now(timezone.utc) - datetime.fromisoformat(cached["checked_at"])).total_seconds()
            if age < 300:
                return cached
        try:
            result = discover_models()
        except CareerError as error:
            if not cached or error.code in {"oauth_unavailable", "paid_fallback_blocked", "hermes_contract_unsupported"}:
                raise
            # Cached candidates remain visible, but validation requires a fresh catalog.
            return dict(cached, state="stale", reason=error.code)
        with self.store.transaction() as db:
            self.store.set_meta(db, "coach_model_catalog", result)
        return result

    def validate(self, value):
        value = canonical_execution(value)
        catalog = self.catalog()
        if catalog["state"] != "available":
            raise CareerError("model_catalog_unavailable", "モデル一覧を再確認してから送信してください。", 503)
        model = next((item for item in catalog["models"] if item["id"] == value["model"] and item["provider"] == value["provider"]), None)
        if not model:
            raise CareerError("model_unsupported", "このモデルは選択したプロバイダーの現在の候補にありません。選び直してください。", 409)
        if value["reasoning_effort"] not in model["reasoning_efforts"]:
            raise CareerError("reasoning_unsupported", "このモデルに対応する推論強度を選んでください。", 409)
        if value["provider"] == PROVIDER:
            wire_effort(value["model"], value["reasoning_effort"])
        elif value["reasoning_effort"] != "default":
            raise CareerError("reasoning_unsupported", "このモデルはHermes標準の推論設定で実行します。", 409)
        return value

    def daily(self, profile):
        with self.store.connect() as db:
            saved = self.store.meta(db, "coach_daily:" + profile)
        if saved:
            return saved
        try:
            return {"version": 0, "execution": default_execution(), "source": "server_default"}
        except CareerError as error:
            return {"version": 0, "execution": None, "source": "server_default", "reason": error.code}

    def update_daily(self, payload, profile):
        value = canonical_execution(payload.get("execution"))
        request = {"action": "daily_settings", "profile": profile, "execution": value,
                   "expected_version": payload.get("expected_version")}
        # Validate only for a new operation; lost acknowledgements must be replayable offline.
        def apply(db):
            old = self.store.meta(db, "coach_daily:" + profile, {"version": 0})
            if type(request["expected_version"]) is not int or request["expected_version"] != old["version"]:
                raise CareerError("version_conflict", "自動調査の設定が更新されています。読み直してください。", 409)
            result = {"version": old["version"] + 1, "execution": value, "source": "user", "updated_at": now()}
            self.store.set_meta(db, "coach_daily:" + profile, result)
            return result
        return self.validated_operation(payload.get("operation_id"), request, value, apply)

    def validated_operation(self, operation_id, request, execution, apply):
        # Catalog network/cache work occurs outside SQLite's write transaction.
        with self.store.connect() as db:
            exists = isinstance(operation_id, str) and db.execute("SELECT 1 FROM operations WHERE id=?", (operation_id,)).fetchone()
        if not exists:
            self.validate(execution)
        return self.store.operation(operation_id, request, apply)

    def capabilities(self, profile, refresh=False):
        try:
            catalog = self.catalog(refresh)
        except CareerError as error:
            catalog = {"models": [], "state": "setup_required", "reason": error.code, "checked_at": now()}
        try:
            default = default_execution()
        except CareerError:
            default = None
        with self.store.connect() as db:
            companies = [self.store.entity(db, "companies", row[0]) for row in db.execute("SELECT id FROM entities WHERE kind='companies'")]
        missing = [company["id"] for company in companies if not company["data"].get("official_urls")]
        usable = catalog["state"] == "available"
        return {"plugin_version": "0.4.0", "api_version": 2, "features": FEATURES, **catalog,
                "default_execution": default, "daily_settings": self.daily(profile),
                "work": [
                    {"id": "self_analysis", "state": "available" if usable else "setup_required"},
                    {"id": "es_document", "state": "available" if usable else "setup_required"},
                    {"id": "official_research", "state": "available" if usable and companies and not missing else "setup_required",
                     "missing_company_ids": missing},
                ]}

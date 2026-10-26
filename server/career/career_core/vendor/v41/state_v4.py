#!/usr/bin/env python3
"""Pure schema, normalization, serialization, and privacy helpers for state v4."""

from __future__ import annotations

import copy
import json
import re
from typing import Any

SCHEMA_VERSION = "4.1"
PLUGIN_VERSION = "0.9.1"
PATCH_TYPE = "UNSAVED_JOBHUNT_PATCH_V4"
REPOSITORY_NAME = "jp-job-hunting-memory"
REPOSITORY_MARKER = "jp-job-hunting-coach-state"
STATE_PATH = ".jobhunt/state.json"
DOCUMENTS_PATH = ".jobhunt/documents"

COLLECTION_PREFIXES: dict[str, str] = {
    "facts": "FACT",
    "hypotheses": "HYP",
    "stories": "ST",
    "target_companies": "CO",
    "applications": "APP",
    "tasks": "TASK",
    "documents": "DOC",
    "selection_events": "EV",
    "decisions": "DEC",
    "sources": "SRC",
    "imports": "IMP",
}
ID_FIELDS = {
    "facts": "fact_id",
    "hypotheses": "hypothesis_id",
    "stories": "story_id",
    "target_companies": "company_id",
    "applications": "application_id",
    "tasks": "task_id",
    "documents": "document_id",
    "selection_events": "event_id",
    "decisions": "decision_id",
    "sources": "source_id",
    "imports": "import_id",
}
ID_PATTERN = re.compile(r"^(FACT|HYP|ST|CO|APP|TASK|DOC|EV|DEC|SRC|IMP)-\d{3,}$")
EXACT_JST = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2} JST$")
DATE_ONLY = re.compile(r"^\d{4}-\d{2}-\d{2}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
DOCUMENT_PATH = re.compile(r"^\.jobhunt/documents/(DOC-\d{3,})-v(\d{3,})\.md$")
MAX_STATE_BYTES = 10 * 1024 * 1024

CLASSIFICATIONS = {
    "CONFIRMED", "INFERRED", "UNKNOWN", "CONFLICTED", "SUPERSEDED",
    "SENSITIVE_REQUIRES_OPT_IN", "SKIPPED_NOT_JOB_HUNTING",
}
SOURCE_KINDS = {
    "CHATGPT_MEMORY_SURFACED", "CHAT_HISTORY_SURFACED", "PROJECT_CONTEXT",
    "USER_PASTED_MEMORY_SUMMARY", "CHATGPT_DATA_EXPORT", "CURRENT_USER_STATEMENT",
    "USER_PRIMARY", "EXTERNAL", "LOCAL_V3_MIGRATION", "LEGACY_NOTE",
}
SOURCE_ROLES = {"USER_STATEMENT", "USER_PRIMARY", "MODEL_SUMMARY", "GENERATED_DOCUMENT", "EXTERNAL", "UNKNOWN"}
PRIMARY_ROLES = {"USER_STATEMENT", "USER_PRIMARY"}
# Kind is the acquisition channel; role is the author of the evidence, not the importer.
KIND_ROLES = {
    "CURRENT_USER_STATEMENT": {"USER_STATEMENT"},
    "USER_PRIMARY": {"USER_PRIMARY"},
    "EXTERNAL": {"EXTERNAL"},
    "CHATGPT_MEMORY_SURFACED": {"MODEL_SUMMARY", "UNKNOWN"},
    "USER_PASTED_MEMORY_SUMMARY": {"MODEL_SUMMARY", "UNKNOWN"},
    "LOCAL_V3_MIGRATION": {"UNKNOWN"},
    "LEGACY_NOTE": {"UNKNOWN"},
}
PURPOSES = {"exploration", "strategy", "creation", "practice", "operations"}
ROOT_PATCH_FIELDS = {"candidate", "last_session"}
DECISION_KINDS = {"FACT_CORRECTION", "SCOPE_EXCLUSION", "STYLE_PREFERENCE", "TEMPORARY_POLICY", "STRATEGY_CHOICE"}
# Explicit aliases: never infer a reference's type merely from its value.
REFERENCE_PREFIXES = {
    **{field: {COLLECTION_PREFIXES[collection]} for collection, field in ID_FIELDS.items()},
    "target_company_id": {"CO"}, "deadline_source_id": {"SRC"},
    "next_research_task_id": {"TASK"}, "superseded_by_fact_id": {"FACT"},
    "replacement_fact_id": {"FACT"},
    "fact_ids": {"FACT"}, "verified_fact_ids": {"FACT"}, "supporting_fact_ids": {"FACT"},
    "contradicting_fact_ids": {"FACT"}, "review_fact_ids": {"FACT"}, "preference_fact_ids": {"FACT"},
    "source_ids": {"SRC"}, "story_ids": {"ST"}, "hypothesis_ids": {"HYP"},
    "application_ids": {"APP"}, "company_ids": {"CO"}, "document_ids": {"DOC"},
    "task_ids": {"TASK"}, "dependency_ids": {"TASK"}, "decision_ids": {"DEC"},
    "event_ids": {"EV"}, "import_ids": {"IMP"},
    "evidence_ids": {"FACT", "HYP", "ST", "SRC", "DOC", "EV"},
    "fit_evidence_ids": {"FACT", "HYP", "ST", "SRC", "DOC", "EV"},
    "target_ids": set(COLLECTION_PREFIXES.values()),
}
CONFIDENCE_LEVELS = {"low", "medium", "high"}
DEADLINE_PRECISIONS = {"exact", "date_only", "unknown"}
HYPOTHESIS_STATUSES = {"active", "supported", "weakened", "rejected", "retired"}
APPLICATION_STATUSES = {"unknown", "considering", "preparing", "submitted", "in_process", "offered", "rejected", "withdrawn", "closed"}
TASK_STATUSES = {"todo", "doing", "blocked", "done", "cancelled"}
DOCUMENT_STATUSES = {"idea", "drafting", "review", "ready", "submitted", "archived"}
SOURCE_TYPES = {"official_recruit", "official_corporate", "official_ir", "official_news", "secondary", "user_provided"}
SOURCE_FRESHNESS = {"current", "stale", "unknown"}
SOURCE_ACCESS = {"ok", "login_required", "blocked", "removed"}
EVENT_TYPES = {"application", "submission", "test", "group_discussion", "interview", "internship", "offer", "rejection", "withdrawal", "feedback", "other"}
EVENT_OUTCOMES = {"pending", "passed", "rejected", "offered", "withdrawn", "unknown", "not_applicable"}

FORBIDDEN_FIELD_NAMES = {
    "contacts", "contact", "email", "phone", "telephone", "address", "password",
    "passwd", "otp", "cookie", "session_token", "api_key", "private_key",
    "github_token", "access_token", "refresh_token", "mypage_credentials",
    "raw_chat", "raw_export", "raw_zip", "attachment_original", "backup",
    "patch_payload",
}

SECRET_PATTERNS: dict[str, re.Pattern[str]] = {
    "OpenAI/API-style secret": re.compile(r"\bsk-[A-Za-z0-9_-]{20,}\b"),
    "AWS access key": re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    "Google API key": re.compile(r"\bAIza[0-9A-Za-z_-]{30,}\b"),
    "GitHub token": re.compile(r"\b(?:github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9_]{20,})\b"),
    "HTTP bearer token": re.compile(r"(?i)\bAuthorization\s*:\s*Bearer\s+[A-Za-z0-9._~+/=-]{16,}"),
    "URL credentials": re.compile(r"(?i)https?://[^/\s@]+@"),
    "private key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "credential assignment": re.compile(
        r"(?i)(?<![A-Za-z0-9_])[\"']?(?:password|passwd|cookie|session[_ -]?(?:id|token)|"
        r"otp|one[- ]?time(?:[_ -]?(?:code|password))?|api[_ -]?key|access[_ -]?token|"
        r"refresh[_ -]?token|client[_ -]?secret|authorization)[\"']?\s*[:=]\s*[\"']?[^\s,;\"']{8,}"
    ),
}


def initial_manifest() -> dict[str, Any]:
    return {
        "schema_version": SCHEMA_VERSION,
        "plugin_version": PLUGIN_VERSION,
        "repository_marker": REPOSITORY_MARKER,
        "storage_backend": "github-connector",
        "privacy_mode": "private-repo",
        "state_path": STATE_PATH,
        "documents_path": DOCUMENTS_PATH,
        "consents": {
            "github_state_read": True,
            "github_write_through": False,
            "import_chatgpt_context": False,
            "store_sensitive_notes": False,
            "store_personal_contacts": False,
        },
    }


def initial_state(updated_at_jst: str = "1970-01-01 00:00 JST") -> dict[str, Any]:
    state: dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        "revision": 1,
        "updated_at_jst": updated_at_jst,
        "candidate": {},
    }
    for collection in COLLECTION_PREFIXES:
        state[collection] = []
    state["last_session"] = {}
    return state


def record_id(collection: str, record: dict[str, Any]) -> str | None:
    field = ID_FIELDS[collection]
    value = record.get(field)
    if value is None and collection == "target_companies":
        value = record.get("target_company_id")
    return value if isinstance(value, str) else None


def scan_secrets(value: Any, path: str = "$") -> list[str]:
    findings: list[str] = []
    if isinstance(value, dict):
        for key, item in value.items():
            findings.extend(scan_secrets(item, f"{path}.{key}"))
    elif isinstance(value, list):
        for index, item in enumerate(value):
            findings.extend(scan_secrets(item, f"{path}[{index}]"))
    elif isinstance(value, str):
        for label, pattern in SECRET_PATTERNS.items():
            if pattern.search(value):
                findings.append(f"{path}: {label}")
    return findings


def normalize_state(state: dict[str, Any]) -> dict[str, Any]:
    """Return a deep-copied state with deterministic record ordering."""
    normalized = copy.deepcopy(state)
    for collection in COLLECTION_PREFIXES:
        records = normalized.get(collection, [])
        if isinstance(records, list):
            normalized[collection] = sorted(
                records,
                key=lambda record: record_id(collection, record) or "" if isinstance(record, dict) else "",
            )
    return normalized


def deterministic_dumps(value: dict[str, Any]) -> str:
    return json.dumps(normalize_state(value), ensure_ascii=False, sort_keys=True, indent=2) + "\n"


def increment_revision(state: dict[str, Any], updated_at_jst: str) -> dict[str, Any]:
    updated = copy.deepcopy(state)
    revision = updated.get("revision")
    if not isinstance(revision, int) or isinstance(revision, bool) or revision < 1:
        raise ValueError("state revision must be a positive integer")
    if not EXACT_JST.fullmatch(updated_at_jst):
        raise ValueError("updated_at_jst must use YYYY-MM-DD HH:MM JST")
    updated["revision"] = revision + 1
    updated["updated_at_jst"] = updated_at_jst
    return updated


def make_provenance(
    source_kind: str,
    source_ref: str,
    imported_at_jst: str,
    *,
    source_date: str = "unknown",
    confidence: str = "low",
    source_role: str | None = None,
) -> dict[str, str]:
    if source_kind not in SOURCE_KINDS:
        raise ValueError(f"unsupported source_kind: {source_kind}")
    if confidence not in CONFIDENCE_LEVELS:
        raise ValueError(f"unsupported confidence: {confidence}")
    if not EXACT_JST.fullmatch(imported_at_jst):
        raise ValueError("imported_at_jst must use YYYY-MM-DD HH:MM JST")
    if not isinstance(source_ref, str) or not source_ref.strip():
        raise ValueError("source_ref must identify an evidence location")
    if source_role is None:
        source_role = {"CURRENT_USER_STATEMENT": "USER_STATEMENT", "USER_PRIMARY": "USER_PRIMARY", "EXTERNAL": "EXTERNAL"}.get(source_kind, "UNKNOWN")
    if source_role not in SOURCE_ROLES or source_role not in KIND_ROLES.get(source_kind, SOURCE_ROLES):
        raise ValueError("source_role is invalid for source_kind")
    return {
        "source_kind": source_kind,
        "source_role": source_role,
        "source_ref": source_ref,
        "source_date": source_date,
        "imported_at_jst": imported_at_jst,
        "confidence": confidence,
    }

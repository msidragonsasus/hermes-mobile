"""Bounded read-only batches and sanitized provider errors; no credential ownership."""
import json
import math
import re
import time
import threading
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime

from .store import CareerError


class MailReadError(CareerError):
    def __init__(self, code, retry_after=0, *, http_status=None, limit_kind=None):
        super().__init__(code, "メールの取得状態を確認してください。保存済みメールは保持しています。", 503)
        delay = float(retry_after or 0)
        self.retry_after = max(0, delay) if math.isfinite(delay) else 0
        self.http_status = http_status if http_status in {400, 401, 403, 404, 429, 500, 502, 503, 504} else None
        self.limit_kind = limit_kind if limit_kind in {'concurrent', 'bandwidth', 'daily', 'rate', 'unknown'} else None


def retry_wait(error, failures=1):
    """An absent Retry-After is not permission to immediately increase pressure."""
    code = error.code
    if code == 'mail_rate_limited':
        wait = min(900, 60 * 2 ** min(max(0, failures - 1), 4))
    elif code == 'mail_daily_limit':
        wait = 3600
    elif code in {'authentication_required', 'mail_scope_unsupported', 'mail_domain_policy', 'workspace_permission_denied'}:
        wait = 300
    else:
        wait = min(300, 2 ** min(failures, 9))
    return max(wait, getattr(error, 'retry_after', 0))


def provider_error(error, resource, captured=""):
    response = getattr(error, "resp", None)
    status = getattr(response, "status", None)
    content = getattr(error, "content", b"")
    if isinstance(content, bytes):
        content = content.decode("utf-8", errors="replace")
    raw = str(content) + captured
    if status is None:
        match = re.search(r'"code"\s*:\s*(400|401|403|404|429|50[0234])\b', raw)
        status = int(match[1]) if match else None
    reasons = set(re.findall(r'"reason"\s*:\s*"([A-Za-z_]+)"', raw))
    delay = 0
    retry = response.get("retry-after", "") if response is not None and hasattr(response, "get") else ""
    try:
        delay = max(0, float(retry))
    except (TypeError, ValueError):
        try:
            delay = max(0, (parsedate_to_datetime(retry) - datetime.now(timezone.utc)).total_seconds())
        except (TypeError, ValueError, OverflowError):
            pass
    if not math.isfinite(delay):
        delay = 0
    # Gmail can put the retry time in the JSON message instead of an HTTP header.
    for stamp in re.findall(r'retry (?:after|at)[: ]+(\d{4}-\d{2}-\d{2}T[\d:.]+(?:Z|[+-]\d{2}:\d{2}))', raw, re.I):
        try:
            due = datetime.fromisoformat(stamp.replace('Z', '+00:00'))
            delay = max(delay, (due - datetime.now(timezone.utc)).total_seconds())
        except ValueError:
            pass
    try:
        payload = json.loads(content).get('error', {})
        for detail in payload.get('details', []):
            if detail.get('@type') == 'type.googleapis.com/google.rpc.RetryInfo':
                duration = re.fullmatch(r'(\d+(?:\.\d+)?)s', detail.get('retryDelay', ''))
                if duration:
                    value = float(duration[1])
                    if math.isfinite(value):
                        delay = max(delay, value)
    except (ValueError, TypeError, AttributeError):
        pass
    if "dailyLimitExceeded" in reasons:
        code, delay = "mail_daily_limit", max(delay, 3600)
    elif status == 429 or reasons & {"rateLimitExceeded", "userRateLimitExceeded", "RATE_LIMIT_EXCEEDED"}:
        code = "mail_rate_limited"
    elif reasons & {"insufficientPermissions", "ACCESS_TOKEN_SCOPE_INSUFFICIENT"}:
        code = "mail_scope_unsupported"
    elif "domainPolicy" in reasons:
        code = "mail_domain_policy"
    elif status == 401 or type(error).__name__ == "RefreshError" or re.search(r"invalid_grant|Token is invalid|Not authenticated", raw, re.I):
        code = "authentication_required"
    elif status == 403:
        code = "workspace_permission_denied"
    elif status == 404:
        code = "mail_cursor_expired" if resource == "history" else "mail_message_missing" if resource.startswith("messages/") else "workspace_unavailable"
    elif status == 400:
        code = "mail_page_expired" if resource in {"messages", "history"} else "mail_bad_request"
    elif isinstance(error, ModuleNotFoundError):
        code = "workspace_dependency_unavailable"
    elif isinstance(error, PermissionError):
        code = "workspace_permission_denied"
    else:
        code = "workspace_unavailable"
    result = {"error": code, "retry_after": delay}
    if status in {400, 401, 403, 404, 429, 500, 502, 503, 504}:
        result['http_status'] = status
    if code in {'mail_rate_limited', 'mail_daily_limit'}:
        result['limit_kind'] = ('daily' if code == 'mail_daily_limit' else
                                'concurrent' if re.search(r'concurrent requests', raw, re.I) else
                                'bandwidth' if re.search(r'bandwidth', raw, re.I) else
                                'rate' if reasons & {'rateLimitExceeded', 'userRateLimitExceeded', 'RATE_LIMIT_EXCEEDED'} else 'unknown')
    return result


def read_batch(module, ids, format, batch_size, concurrency):
    """Each parallel batch owns its own httplib2 transport. Refresh before threading."""
    from .workspace_reader import operation
    if (not isinstance(ids, list) or not 1 <= len(ids) <= 40 or len(set(ids)) != len(ids)
            or type(batch_size) is not int or type(concurrency) is not int
            or not 1 <= batch_size <= 40 or concurrency not in {1, 2} or batch_size * concurrency > 40):
        raise ValueError("Invalid mail batch")
    params = {"format": format}
    if format == "metadata":
        params["metadataHeaders"] = "From"
    arguments = [operation("messages/" + identity, params)[1] for identity in ids]
    chunks = [arguments[i:i + batch_size] for i in range(0, len(arguments), batch_size)]
    services = []
    try:
        # Credentials remain in the existing Workspace module. Never pass them to the parent.
        metrics = []
        limited = threading.Event()
        for _ in range(min(concurrency, len(chunks))):
            service = module.build_service("gmail", "v1")
            services.append(service)
            counter = {'response_bytes': 0, 'http_requests': 0, 'seconds': 0.0}
            metrics.append(counter)
            http = getattr(service, '_http', None)
            if http is not None:
                original = http.request
                def measured(*args, _original=original, _counter=counter, **kwargs):
                    begun = time.monotonic()
                    try:
                        response, content = _original(*args, **kwargs)
                        _counter['response_bytes'] += len(content)
                        return response, content
                    finally:
                        _counter['http_requests'] += 1
                        _counter['seconds'] += time.monotonic() - begun
                http.request = measured
        def run(slot):
            results = []
            service = services[slot]
            for chunk in chunks[slot::len(services)]:
                if limited.is_set():
                    results.extend({'id': args['id'], 'error': 'mail_rate_limited'} for args in chunk)
                    continue
                def callback(identity, value, error):
                    result = provider_error(error, "messages/" + identity) if error else {"data": value}
                    if result.get('error') in {'mail_rate_limited', 'mail_daily_limit'}:
                        limited.set()
                    results.append(dict(id=identity, **result))
                batch = service.new_batch_http_request(callback=callback)
                for args in chunk:
                    batch.add(service.users().messages().get(**args), request_id=args["id"])
                try:
                    batch.execute()
                except Exception as error:
                    failure = provider_error(error, 'batch')
                    received = {item['id'] for item in results}
                    results.extend(dict(id=args['id'], **failure) for args in chunk if args['id'] not in received)
                    if failure['error'] in {'mail_rate_limited', 'mail_daily_limit'}:
                        limited.set()
            return results
        with ThreadPoolExecutor(max_workers=len(services)) as pool:
            items = [item for values in pool.map(run, range(len(services))) for item in values]
        return {'items': items, 'metrics': {key: sum(value[key] for value in metrics)
                                          for key in ('response_bytes', 'http_requests', 'seconds')}}
    finally:
        for service in services:
            service.close()

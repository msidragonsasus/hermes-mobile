"""Bounded Gmail benchmark; only aggregate metrics leave stdout.

Read the existing mailbox and DB as their owner. Credential refresh stays inside
Hermes' installed Workspace implementation; no new credential store is created.
"""
import argparse
import contextlib
import io
import json
import math
from pathlib import Path
import sqlite3
import statistics
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from career_core.gmail_reader import GmailReader, account_reference
from career_core.mail_sync import sender_groups
from career_core.workspace_reader import WorkspaceApi
from career_core.mail_transport import MailReadError, retry_wait
from career_core.store import CareerError


def select(trials):
    def key(row):
        return row['batch_size'], row['concurrency'], row.get('requests_per_second', 4)
    groups = {}
    blocked = {key(r) for r in trials if r['error_codes']}
    for row in trials:
        rate = row.get('requests_per_second', 4)
        if (row.get('phase', 'verification') != 'verification' or row.get('complete') is False
                or row.get('stop_code') or row.get('superseded') or key(row) in blocked
                or type(rate) not in {int, float} or not math.isfinite(rate) or not .25 <= rate <= 4):
            continue
        groups.setdefault(key(row), []).append(row)
    verified = {setting: statistics.median(r['messages_per_second'] for r in rows)
                for setting, rows in groups.items() if len(rows) >= 3}
    if not verified:
        return None
    fastest = max(verified.values())
    chosen = min((setting for setting, speed in verified.items() if speed >= fastest * .9),
                 key=lambda setting: (setting[0] * setting[1], setting[2], setting[1], setting[0]))
    batch, concurrency, rate = chosen
    result = {'batch_size': batch, 'concurrency': concurrency, 'verified': True,
              'median_messages_per_second': verified[chosen]}
    if any('requests_per_second' in row for row in groups[chosen]):
        result['requests_per_second'] = rate
    return result


def recovery_hint(previous, account=None):
    if account is not None and previous.get('account_id') != account:
        raise MailReadError('mail_account_mismatch')
    failed = [r for r in previous.get('trials', []) if 'mail_rate_limited' in r.get('error_codes', [])]
    if not failed:
        raise MailReadError('benchmark_resume_invalid')
    loads = [r['batch_size'] * r['concurrency'] for r in failed
             if type(r.get('batch_size')) is int and type(r.get('concurrency')) is int
             and 1 <= r['batch_size'] <= 40 and r['concurrency'] in {1, 2}
             and r['batch_size'] * r['concurrency'] <= 40]
    if len(loads) != len(failed):
        raise MailReadError('benchmark_resume_invalid')
    rates = [r.get('requests_per_second', 2) for r in failed]
    if any(type(rate) not in {int, float} or not math.isfinite(rate) or not .25 <= rate <= 4 for rate in rates):
        raise MailReadError('benchmark_resume_invalid')
    batch = max((b for b in (1, 2, 5, 10, 20, 40) if b < min(loads)), default=1)
    error = MailReadError('mail_rate_limited', (previous.get('stop') or {}).get('retry_after', 0))
    return {'max_batch': batch, 'initial_rate': max(.25, min(1, min(rates) / 2)),
            'limited': True, 'wait_seconds': retry_wait(error)}


def connection_probe(home):
    result = {}
    for name, prefer_python in [('workspace_default', False), ('official_python', True)]:
        api = WorkspaceApi(home=home, prefer_python=prefer_python)
        try:
            status = api.status()
            result[name] = {'status': 'ready' if status.get('ready') else status.get('reason'),
                            'installed_backend': status.get('backend')}
            if status.get('ready'):
                api.get('profile')  # Never include the returned address or history position.
                result[name]['profile'] = 'ok'
        except CareerError as error:
            result[name] = {'status': error.code, 'http_status': getattr(error, 'http_status', None),
                            'limit_kind': getattr(error, 'limit_kind', None),
                            'retry_after': getattr(error, 'retry_after', 0)}
            if error.code in {'mail_rate_limited', 'mail_daily_limit'}:
                break
        finally:
            api.close()
    return result


def benchmark(database, home, *, previous=None, progress=lambda event: None):
    start = time.monotonic()
    hint = recovery_hint(previous) if previous else {}
    wait = hint.pop('wait_seconds', 0)
    if wait >= 1800:
        return {'state': 'incomplete', 'verified': False, 'trials': [],
                'stop': {'code': 'mail_rate_limited', 'retry_after': wait}}
    if wait:
        progress({'event': 'cooldown', 'seconds': wait})
        until = time.monotonic() + wait
        while time.monotonic() < until:
            time.sleep(min(30, until - time.monotonic()))
    probe = connection_probe(home)
    if probe.get('official_python', {}).get('profile') != 'ok':
        return {'state': 'connection_unavailable', 'connections': probe, 'verified': False, 'trials': []}
    db = sqlite3.connect(Path(database).resolve().as_uri() + '?mode=ro', uri=True)
    try:
        companies = {identity for identity, data in db.execute("SELECT id,data FROM entities WHERE kind='companies'")
                     if not json.loads(data).get('deleted_at')}
        senders = sorted({data['emailAddress'].strip().lower() for (raw,) in db.execute(
            "SELECT data FROM entities WHERE kind='company_mail_senders'") for data in [json.loads(raw)]
            if data.get('enabled', True) is True and data.get('company_id') in companies})
    finally:
        db.close()
    if not senders:
        return {'state': 'no_senders', 'verified': False, 'connections': probe}
    api = WorkspaceApi(home=home, prefer_python=True)
    account = account_reference(api.get('profile')['emailAddress'])
    if previous:
        recovery_hint(previous, account)
    reader = GmailReader(account, api=api)
    ids = []
    for group in sender_groups(senders):
        if reader.calls >= 50:
            break
        query = '{' + ' '.join('from:' + s for s in group) + '} after:' + str(int(time.time() - 30 * 86400)) + ' -in:spam -in:trash'
        page = reader.search_ids(query)
        ids.extend(item['id'] for item in page.get('messages', []))
        ids = list(dict.fromkeys(ids))[:40]
        if len(ids) == 40:
            break
    if len(ids) < 40:
        return {'state': 'insufficient_sample', 'sample_size': len(ids), 'verified': False, 'connections': probe}
    try:
        result = run_trials(reader, ids, senders, started_at=start, initial_requests=3, progress=progress, **hint)
    finally:
        reader.close()
    return dict(result, account_id=account, sample_size=len(ids), connections=probe,
                resumed_after_limit=bool(previous), previous_trials_used_for_selection=False)


def run_trials(reader, ids, senders, *, started_at=None, initial_requests=0,
               max_seconds=1800, max_requests=2000, max_batch=40,
               initial_rate=4, limited=False, progress=lambda event: None):
    """Keep fixed samples, paced traffic and a decreasing envelope after a limit."""
    start = time.monotonic() if started_at is None else started_at
    api = reader.api
    trials, recoveries = [], []
    stop = None
    rate, ceiling = initial_rate, max_batch
    initial_units = initial_requests + reader.calls * 5

    def budget(required_requests=0):
        if (time.monotonic() - start >= max_seconds
                or initial_requests + reader.calls + required_requests > max_requests
                or api.response_bytes >= 100 * 1024 * 1024):
            raise MailReadError('benchmark_budget_reached')

    def pause(seconds, event='pacing'):
        pause_until = time.monotonic() + max(0, seconds)
        if pause_until >= start + max_seconds:
            raise MailReadError('benchmark_budget_reached')
        if event == 'cooldown':
            progress({'event': event, 'seconds': round(seconds, 1)})
        while time.monotonic() < pause_until:
            budget()
            time.sleep(min(30, pause_until - time.monotonic()))

    def trial(batch, concurrency, phase):
        budget()
        targets = ids[:batch * concurrency] if phase == 'sweep' else ids
        errors, completed, messages, latencies = [], 0, 0, []
        interruption = None
        profiles, api_seconds = 0, 0.0
        response_before, http_before, calls_before = api.response_bytes, api.http_requests, reader.calls
        begun = time.monotonic()
        for index in range(0, len(targets), batch * concurrency):
            chunk = targets[index:index + batch * concurrency]
            chunk_start, chunk_calls = time.monotonic(), reader.calls
            api_recorded = False
            try:
                budget(1 + len(chunk) * 2)
                profiles += 1
                reader.profile()
                values, done, failures = reader.read_ids(chunk, set(senders), {},
                                                        {'batch_size': batch, 'concurrency': concurrency})
                completed += len(done)
                messages += len(values)
                errors.extend(failures)
                api_seconds += time.monotonic() - chunk_start
                api_recorded = True
                if not failures:
                    pause((reader.calls - chunk_calls) / rate - (time.monotonic() - chunk_start))
            except CareerError as error:
                if not api_recorded:
                    api_seconds += time.monotonic() - chunk_start
                if error.code == 'benchmark_budget_reached':
                    interruption = error
                else:
                    errors.append(error)
            latencies.append(time.monotonic() - chunk_start)
            progress({'event': 'step', 'phase': phase, 'batch_size': batch, 'concurrency': concurrency,
                      'requests_per_second': rate, 'completed_ids': completed, 'planned_messages': len(targets)})
            if errors or interruption:
                break
        elapsed = time.monotonic() - begun
        calls = reader.calls - calls_before
        row = {'phase': phase, 'batch_size': batch, 'concurrency': concurrency, 'requests_per_second': rate,
               'planned_messages': len(targets), 'seconds': round(elapsed, 3),
               'api_seconds': round(api_seconds, 3), 'messages': messages, 'completed_ids': completed,
               'requests': calls, 'quota_units_estimate': profiles + max(0, calls - profiles) * 20,
               'complete': completed == len(targets) and not errors and interruption is None,
               'stop_code': interruption.code if interruption else None,
               'http_batch_requests': api.http_requests - http_before,
               'response_bytes': api.response_bytes - response_before,
               'step_max_seconds': round(max(latencies, default=elapsed), 3),
               'messages_per_second': round(messages / max(elapsed, .001), 3),
               'error_codes': sorted({error.code for error in errors}), 'error_count': len(errors),
               'http_statuses': sorted({error.http_status for error in errors if getattr(error, 'http_status', None)}),
               'limit_kinds': sorted({error.limit_kind for error in errors if getattr(error, 'limit_kind', None)}),
               'error_rate': len(errors) / max(1, calls), 'retries': 0, 'retry_rate': 0}
        trials.append(row)
        if errors:
            raise max(errors, key=lambda error: getattr(error, 'retry_after', 0))
        if interruption:
            raise interruption
        if not row['complete']:
            raise MailReadError('benchmark_sample_incomplete')
        return row

    while True:
        batch, concurrency = 1, 1
        try:
            successful = []
            for batch in (b for b in (1, 2, 5, 10, 20, 40) if b <= ceiling):
                matching = [r for r in trials if r['phase'] == 'sweep' and r['complete']
                            and r['batch_size'] == batch and r['concurrency'] == 1
                            and r['requests_per_second'] == rate and not r.get('superseded')]
                successful.append(matching[0] if matching else trial(batch, 1, 'sweep'))
            if not limited:
                winner = max(successful, key=lambda row: row['messages_per_second'])
                batch, concurrency = min(20, winner['batch_size']), 2
                successful.append(trial(batch, concurrency, 'sweep'))
            fastest = max(row['messages_per_second'] for row in successful)
            candidates = [row for row in successful if row['messages_per_second'] >= fastest * .9]
            for candidate in sorted(candidates, key=lambda r: (r['batch_size'] * r['concurrency'], r['concurrency'])):
                batch, concurrency = candidate['batch_size'], candidate['concurrency']
                for _ in range(3):
                    trial(batch, concurrency, 'verification')
            break
        except CareerError as error:
            stop = {'code': error.code, 'retry_after': getattr(error, 'retry_after', 0),
                    'http_status': getattr(error, 'http_status', None), 'limit_kind': getattr(error, 'limit_kind', None)}
            if error.code != 'mail_rate_limited':
                break
            lower = [b for b in (1, 2, 5, 10, 20, 40) if b < batch * concurrency and b <= ceiling]
            next_rate = max(.25, rate / 2)
            if not lower and next_rate == rate:
                stop['retry_after'] = retry_wait(error, len(recoveries) + 1)
                break
            ceiling, rate, limited = max(lower, default=1), next_rate, True
            wait = retry_wait(error, len(recoveries) + 1)
            recoveries.append(dict(stop, wait_seconds=wait, next_max_batch=ceiling, next_requests_per_second=rate))
            # A setting verified before this pressure reduction is not an applicable ceiling.
            for row in trials:
                if row['batch_size'] * row['concurrency'] > ceiling or row['requests_per_second'] > rate:
                    row['superseded'] = True
            try:
                pause(wait, 'cooldown')
            except CareerError as interrupted:
                stop = {'code': interrupted.code, 'retry_after': wait}
                break
            stop = None
    selected = select(trials)
    return {'state': 'measured' if selected else 'incomplete', 'measurement_version': 4,
            'verified': bool(selected), 'selected': selected, 'trials': trials, 'stop': stop,
            'recoveries': recoveries, 'requests': initial_requests + reader.calls,
            'quota_units_estimate': initial_units + sum(r['quota_units_estimate'] for r in trials),
            'quota_model': '2026 published units: profile=1, list=5, message.get=20; project overrides are unverified.',
            'response_bytes': api.response_bytes, 'budget_seconds': max_seconds,
            'sampling': 'One step per sweep setting; each verification reads the same full sample with pacing.',
            'error_rate_scope': 'Read errors per attempted logical request; budget stops are excluded.',
            'bytes_scope': 'Decoded HTTP batch response bodies only; headers, TLS and profile/list traffic excluded.',
            'scope': 'Observed sample only; not a provider limit or future guarantee.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--database', default='/var/lib/hermes/career/career.sqlite3')
    parser.add_argument('--home', default='/var/lib/hermes/.hermes')
    parser.add_argument('--probe-only', action='store_true')
    parser.add_argument('--resume-report', type=Path)
    parser.add_argument('--progress', action='store_true')
    args = parser.parse_args()
    try:
        previous = None
        if args.resume_report:
            if args.resume_report.stat().st_size > 1024 * 1024:
                raise MailReadError('benchmark_resume_invalid')
            previous = json.loads(args.resume_report.read_text(encoding='utf-8'))
        def progress(event):
            if args.progress:
                print(json.dumps(event), file=sys.__stderr__, flush=True)
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            result = connection_probe(args.home) if args.probe_only else benchmark(args.database, args.home, previous=previous, progress=progress)
    except Exception as error:
        result = {'state': getattr(error, 'code', 'benchmark_unavailable'), 'verified': False,
                  'exception_type': type(error).__name__, 'http_status': getattr(error, 'http_status', None),
                  'limit_kind': getattr(error, 'limit_kind', None), 'retry_after': getattr(error, 'retry_after', 0)}
    print(json.dumps(result, ensure_ascii=False))

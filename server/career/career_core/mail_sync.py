"""One bounded, resumable account sync step. New mail preempts historical scans."""
import copy
import math
from datetime import datetime, timezone
import time
from urllib.parse import quote

from .locking import job_lock
from .mail_transport import MailReadError, retry_wait
from .store import CareerError

INTERVAL = 300
DAY = 86400


def iso(stamp):
    return datetime.fromtimestamp(stamp, timezone.utc).isoformat()


def rule_map(assignments):
    return {sender: sorted(companies) for sender, companies in sorted(assignments.items())}


def sender_groups(senders):
    group = []
    for sender in sorted(senders):
        candidate = group + [sender]
        if group and (len(candidate) > 25 or len(quote(' '.join('from:' + s for s in candidate), safe='')) > 6000):
            yield group
            group = []
        group.append(sender)
    if group:
        yield group


def status_view(state, settings, assignments, company=None, at=None, requested=0):
    at = time.time() if at is None else at
    senders = {s for s, companies in assignments.items() if company is None or company in companies}
    jobs = [job for job in state.get('jobs', []) if senders.intersection(job['senders'])]
    rules_current = all(state.get('rules', {}).get(s) == sorted(assignments[s]) for s in senders)
    recent = bool(senders) and rules_current and all(job['done'] for job in jobs if job['phase'] == 'recent')
    backfill = bool(senders) and rules_current and all(job['done'] for job in jobs if job['phase'] == 'backfill')
    checked = state.get('delta_checked_at')
    fresh = bool(settings.get('enabled') and senders and recent and checked is not None
                 and at - checked <= INTERVAL and not state.get('last_error')
                 and 'started_at' not in state.get('delta', {})
                 and state.get('completed_generation', 0) >= requested)
    return {'version': 2, 'state': ('disabled' if not settings.get('enabled') else
            'no_senders' if not senders else 'auth_required' if state.get('last_error') == 'authentication_required' else
            'retrying' if state.get('retry_at', 0) > at else 'partial' if not recent else
            'updated' if fresh else 'stale'), 'fresh': fresh, 'recent_complete': recent,
            'backfill_complete': backfill, 'synced_through_at': iso(checked) if checked is not None else None,
            'last_success_at': iso(checked) if checked is not None and recent else None,
            'last_attempt_at': state.get('last_attempt_at'), 'reason': state.get('last_error'),
            'retry_at': iso(state['retry_at']) if state.get('retry_at', 0) > at else None,
            'recent_since': iso(state['started_at'] - 30 * DAY) if state.get('started_at') else None,
            'scanned_ids': state.get('scanned_ids', 0), 'saved_messages': state.get('saved_messages', 0),
            'backfill_groups_done': sum(job['done'] for job in jobs if job['phase'] == 'backfill'),
            'backfill_groups_total': sum(job['phase'] == 'backfill' for job in jobs),
            'completed_generation': state.get('completed_generation', 0), 'requested_generation': requested,
            'coverage': '登録送信元の直近30日と開始後の差分。過去分の完了は別表示。'}


class MailSync:
    def __init__(self, collector, clock=time.time):
        self.collector, self.store, self.mail = collector, collector.store, collector.mail
        self.clock = clock

    def step(self, should_cancel=lambda: False):
        with job_lock(self.store.directory / 'mail-sync.lock') as acquired:
            if not acquired:
                return {'state': 'running', 'changed': 0, 'delay': 1}
            return self._step(should_cancel)

    def _step(self, should_cancel):
        settings = self.mail.settings()
        if not settings.get('enabled') or not settings.get('account_id'):
            raise CareerError('mail_collection_disabled', 'メールの自動同期を有効にしてください。', 409)
        account, at = settings['account_id'], self.clock()
        key = 'mail:sync:' + account
        with self.store.connect() as db:
            state = self.store.meta(db, key, None)
            requested = self.store.meta(db, 'mail:request:' + account, 0)
            measured = self.store.meta(db, 'mail:tuning:' + account, {})
        assignments = self.mail.active_assignments()
        if not assignments:
            # Remember rule removals so re-enabling the same sender starts its catch-up scan.
            if state:
                state['rules'], state['jobs'] = {}, []
                state['completed_generation'] = requested
                self._save(key, state, settings, should_cancel)
            return {'state': 'no_senders', 'changed': 0, 'delay': INTERVAL}
        if state and state.get('retry_at', 0) > at:
            return {'state': 'retrying', 'changed': 0, 'delay': state['retry_at'] - at}
        if state is None:
            state = {'version': 2, 'rules': {}, 'jobs': [], 'delta': {}, 'completed_generation': 0}
        state = copy.deepcopy(state)
        rate_ceiling = self._rate_ceiling(measured)
        state['requests_per_second'] = min(state.get('requests_per_second', rate_ceiling), rate_ceiling)
        if (state.get('rules') == rule_map(assignments) and state.get('delta_checked_at')
                and requested <= state.get('completed_generation', 0)
                and 'started_at' not in state['delta'] and all(j['done'] for j in state['jobs'])
                and at - state['delta_checked_at'] < INTERVAL):
            return {'state': 'unchanged', 'changed': 0, 'delay': INTERVAL - (at - state['delta_checked_at'])}
        def cancelled():
            if should_cancel():
                return True
            with self.store.connect() as db:
                current = self.store.meta(db, 'mail:settings', {})
                return (not current.get('enabled') or current.get('account_id') != account
                        or self.mail.active_assignments(db) != assignments)
        reader = None
        started = time.monotonic()
        try:
            state['last_attempt_at'] = iso(at)
            reader = self.collector.reader_factory(account, should_cancel=cancelled)
            profile = reader.profile()  # Check the credential owner before any message access.
            if not state['delta'].get('history_id'):
                state['started_at'] = self.clock()
                state['delta'] = {'history_id': profile['historyId']}
            self._reconcile(state, assignments, self.clock())
            state['last_attempt_at'] = iso(at)
            self._save(key, state, settings, should_cancel)
            ceiling = self._ceiling(measured)
            tuning = state.setdefault('tuning', dict(ceiling))
            tuning['batch_size'] = min(tuning['batch_size'], ceiling['batch_size'])
            tuning['concurrency'] = min(tuning['concurrency'], ceiling['concurrency'])
            delta = state['delta']
            due = (bool(delta.get('started_at')) or requested > state.get('completed_generation', 0)
                   or at - state.get('delta_checked_at', 0) >= INTERVAL)
            job = None
            if due:
                lane = delta
                if 'started_at' not in delta:
                    delta.update(started_at=at, generation=requested)
                if 'pending' not in delta:
                    try:
                        page = reader.history_ids(delta['history_id'], delta.get('page_token'))
                    except CareerError as error:
                        if error.code not in {'mail_cursor_expired', 'mail_page_expired'}:
                            raise
                        if error.code == 'mail_page_expired':
                            delta.pop('page_token', None)
                        else:
                            # A new baseline starts live collection immediately; all scoped IDs
                            # are reconciled again with cache reuse, recent before historical.
                            state['delta'] = {'history_id': profile['historyId']}
                            state['started_at'] = self.clock()
                            state['rules'], state['jobs'] = {}, []
                            state.pop('delta_checked_at', None)
                            self._reconcile(state, assignments, at)
                        self._save(key, state, settings, should_cancel)
                        return {'state': 'partial', 'changed': 0, 'delay': 1}
                    delta['pending'] = list(dict.fromkeys(m['message']['id'] for entry in page.get('history', [])
                                               for m in entry.get('messagesAdded', [])))
                    if page.get('nextPageToken') and page['nextPageToken'] == delta.get('page_token'):
                        raise MailReadError('mail_page_stalled')
                    delta['next_page'] = page.get('nextPageToken')
                    delta['next_history'] = page.get('historyId', profile['historyId'])
            else:
                recent = [j for j in state['jobs'] if not j['done'] and j['phase'] == 'recent']
                jobs = recent or [j for j in state['jobs'] if not j['done']]
                if not jobs:
                    return {'state': 'unchanged', 'changed': 0,
                            'delay': max(1, INTERVAL - (at - state.get('delta_checked_at', 0)))}
                job, lane = jobs[0], jobs[0]
                if 'pending' not in job:
                    try:
                        page = reader.search_ids(job['query'], job.get('page_token'))
                    except CareerError as error:
                        if error.code != 'mail_page_expired' or not job.get('page_token'):
                            raise
                        job.pop('page_token', None)
                        self._save(key, state, settings, should_cancel)
                        return {'state': 'partial', 'changed': 0, 'delay': 1}
                    if page.get('nextPageToken') and page['nextPageToken'] == job.get('page_token'):
                        raise MailReadError('mail_page_stalled')
                    job['pending'] = list(dict.fromkeys(m['id'] for m in page.get('messages', [])))
                    job['next_page'] = page.get('nextPageToken')
            # Persist discovered IDs before HTTP reads. On restart only unfinished IDs replay.
            self._save(key, state, settings, should_cancel)
            ids = lane['pending'][:min(40, tuning['batch_size'] * tuning['concurrency'])]
            cached = self.mail.cached(account, ids)
            messages, completed, errors = reader.read_ids(ids, set(assignments), cached, tuning)
            with self.store.transaction() as db:
                self._validate(db, settings, should_cancel)
                changed = self.collector.persist(db, account, messages, notify=job is None)
                lane['pending'] = [identity for identity in lane['pending'] if identity not in completed]
                state['scanned_ids'] = state.get('scanned_ids', 0) + len(completed)
                state['saved_messages'] = state.get('saved_messages', 0) + len([m for m in messages if m['id'] not in cached])
                if not lane['pending']:
                    lane.pop('pending')
                    token = lane.pop('next_page', None)
                    if token:
                        lane['page_token'] = token
                    elif job is not None:
                        job['done'] = True
                    else:
                        state['delta_checked_at'] = delta['started_at']
                        state['completed_generation'] = delta['generation']
                        state['delta'] = {'history_id': delta['next_history']}
                if job is not None:
                    state['jobs'].remove(job)
                    state['jobs'].append(job)
                state.pop('last_error', None)
                state.pop('retry_at', None)
                if errors:
                    self._failure(state, max(errors, key=lambda e: e.retry_after), self.clock())
                else:
                    state['failures'] = 0
                    state['success_steps'] = state.get('success_steps', 0) + 1
                    if state['success_steps'] >= 5:
                        state['requests_per_second'] = min(rate_ceiling, state['requests_per_second'] * 1.25)
                        tuning['batch_size'] = min(ceiling['batch_size'], max(1, tuning['batch_size'] * 2))
                        tuning['concurrency'] = ceiling['concurrency']
                        state['success_steps'] = 0
                self._attention(db, settings, state)
                self.store.set_meta(db, key, state)
            view = status_view(state, settings, assignments, at=at, requested=requested)
            delay = max(0.25, reader.calls / state.get('requests_per_second', 4) - (time.monotonic() - started))
            if errors:
                delay = max(delay, state['retry_at'] - self.clock())
            return {'state': view['state'], 'changed': changed, 'companies': len({c for cs in assignments.values() for c in cs}),
                    'delay': delay, 'pending': not view['backfill_complete'], 'status': view}
        except Exception as error:
            if not isinstance(error, CareerError):
                error = MailReadError('mail_collector_unavailable')
            if state is not None and error.code != 'collection_cancelled':
                self._failure(state, error, self.clock())
                self._save(key, state, settings, should_cancel)
            raise error from None
        finally:
            if reader is not None:
                reader.close()

    @staticmethod
    def _ceiling(measured):
        batch, concurrency = measured.get('batch_size', 1), measured.get('concurrency', 1)
        if (measured.get('verified') is not True or type(batch) is not int or type(concurrency) is not int
                or not 1 <= batch <= 40 or concurrency not in {1, 2} or batch * concurrency > 40):
            return {'batch_size': 1, 'concurrency': 1}
        return {'batch_size': batch, 'concurrency': concurrency}

    @staticmethod
    def _rate_ceiling(measured):
        rate = measured.get('requests_per_second', 4)
        if (measured.get('verified') is not True or type(rate) not in {int, float}
                or not math.isfinite(rate) or not 0.25 <= rate <= 4):
            return 1
        return rate

    def _reconcile(self, state, assignments, at):
        rules = rule_map(assignments)
        state['jobs'] = [j for j in state['jobs'] if set(j['senders']).intersection(rules)]
        added = [s for s, companies in rules.items() if set(companies) - set(state['rules'].get(s, []))]
        for group in sender_groups(added):
            sender_query = '{' + ' '.join('from:' + s for s in group) + '}'
            for phase in ('recent', 'backfill'):
                bounds = (f' after:{int(at - 30 * DAY) - 1} before:{int(at) + 1}' if phase == 'recent'
                          else f' before:{int(at - 30 * DAY) + 1}')
                state['jobs'].append({'senders': group, 'phase': phase, 'done': False,
                                      'query': sender_query + bounds + ' -in:spam -in:trash'})
        state['rules'] = rules

    def _failure(self, state, error, at):
        state['failures'] = state.get('failures', 0) + 1
        state['last_error'] = error.code
        state['retry_at'] = at + retry_wait(error, state['failures'])
        state['success_steps'] = 0
        state['requests_per_second'] = max(0.25, state.get('requests_per_second', 4) / 2)
        tuning = state.setdefault('tuning', {'batch_size': 1, 'concurrency': 1})
        tuning.update(batch_size=max(1, tuning['batch_size'] // 2), concurrency=1)

    def _validate(self, db, settings, should_cancel):
        current = self.store.meta(db, 'mail:settings', {})
        if should_cancel() or not current.get('enabled') or current.get('account_id') != settings['account_id']:
            raise CareerError('collection_cancelled', 'メール同期を停止しました。保存済みメールは保持しています。', 409)

    def _attention(self, db, settings, state):
        reason = state.get('last_error')
        if reason not in {None, 'authentication_required'}:
            return
        for company in {company for companies in self.mail.active_assignments(db).values() for company in companies}:
            self.collector.collection.attention(db, 'mail:' + settings['account_id'] + ':' + company,
                                                company, reason == 'authentication_required', mail=True)

    def _save(self, key, state, settings, should_cancel):
        with self.store.transaction() as db:
            self._validate(db, settings, should_cancel)
            for sender, companies in self.mail.active_assignments(db).items():
                for company in companies:
                    self.collector.collection.begin_mail_notices(db, company, settings['account_id'])
            self._attention(db, settings, state)
            self.store.set_meta(db, key, state)

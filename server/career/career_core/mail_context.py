"""Pin a bounded saved-mail view for an agent; acquisition remains program-owned."""
import time

from .mail_store import MailStore
from .store import CareerError, now


def snapshot(store, run):
    mail = MailStore(store)
    key = 'mail:snapshot:' + run['id']
    with store.transaction() as db:
        pinned = store.meta(db, key, None)
        if pinned:
            return pinned
        upper = db.execute('SELECT COALESCE(MAX(seq),0) FROM career_mail_sequence').fetchone()[0]
        import json
        companies = [r['id'] for r in db.execute("SELECT id,data FROM entities WHERE kind='companies'")
                     if not json.loads(r['data']).get('deleted_at')]
        scoped = run['request'].get('scope', {}).get('company_id')
        if scoped:
            companies = [identity for identity in companies if identity == scoped]
        pinned = {'upper': upper, 'captured_at': now(), 'status': mail.status(db=db),
                  'companies': {identity: mail.status(identity, db) for identity in companies},
                  'guidance': '同期未確認の場合は最終同期時刻と未確認範囲を明示し、保存済みの範囲で続行する。未確認を新着なしと扱わない。'}
        store.set_meta(db, key, pinned)
        return pinned


def prepare(store, run, should_cancel=lambda: False, timeout=60):
    mail = MailStore(store)
    issue = None
    if run['kind'] in {'daily', 'research'} and mail.settings().get('enabled') and mail.active_assignments():
        try:
            from .mail_worker import worker
            ticket = mail.request_sync()
            worker(store).start()
            until = time.monotonic() + min(60, timeout)
            while time.monotonic() < until and not should_cancel():
                view = mail.sync_view()
                if view['account_id'] != ticket['account_id']:
                    issue = 'mail_account_changed'
                    break
                status = view['status']
                if status.get('completed_generation', 0) >= ticket['generation'] and status.get('fresh'):
                    break
                if status.get('reason'):
                    issue = status['reason']
                    break
                time.sleep(min(.25, max(0, until - time.monotonic())))
            else:
                issue = 'mail_sync_pending'
        except CareerError as error:
            issue = error.code
        except Exception:
            issue = 'mail_sync_unavailable'
    pinned = snapshot(store, run)
    if issue:
        pinned['refresh_issue'] = issue
        for status in [pinned['status'], *pinned['companies'].values()]:
            status['fresh'] = False
            if status.get('state') == 'updated':
                status['state'] = 'stale'
            status['reason'] = status.get('reason') or issue
        with store.transaction() as db:
            store.set_meta(db, 'mail:snapshot:' + run['id'], pinned)
    return pinned


def public_snapshot(pinned):
    return dict({key: value for key, value in pinned.items() if key != 'companies'},
                company_count=len(pinned['companies']))


def page(store, run, company, cursor=None, limit=20):
    pinned = snapshot(store, run)
    if company not in pinned['companies']:
        raise CareerError('mail_scope_denied', 'この作業の対象企業を確認してください。', 403)
    return dict(MailStore(store).snapshot_page(company, pinned['upper'], cursor, limit),
                company_id=company, status=pinned['companies'][company], captured_at=pinned['captured_at'],
                guidance=pinned['guidance'])

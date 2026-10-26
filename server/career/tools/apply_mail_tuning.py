"""Apply a reviewed aggregate benchmark report to its configured account only."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(1, str(Path(__file__).resolve().parent))
from career_core.mail_sync import MailSync
from career_core.store import CareerError, Store, now
from benchmark_mail import select


def apply(store, report):
    selected = select(report.get('trials', []))
    if (not selected or selected != report.get('selected') or report.get('state') != 'measured'
            or MailSync._ceiling(selected) != {key: selected[key] for key in ('batch_size', 'concurrency')}
            or ('requests_per_second' in selected and MailSync._rate_ceiling(selected) != selected['requests_per_second'])):
        raise CareerError('invalid_mail_measurement', '正常に3回測定した設定を選んでください。')
    with store.transaction() as db:
        settings = store.meta(db, 'mail:settings', {})
        if not settings.get('account_id') or report.get('account_id') != settings['account_id']:
            raise CareerError('mail_account_mismatch', '測定したGmailと同期対象のGmailが異なります。')
        value = dict(selected, applied_at=now(), report_sha256=hashlib.sha256(
            json.dumps(report, sort_keys=True).encode()).hexdigest())
        store.set_meta(db, 'mail:tuning:' + settings['account_id'], value)
        return value


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--data-dir', required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    if not (Path(args.data_dir) / 'career.sqlite3').is_file():
        raise SystemExit('Existing Career database is required')
    if args.report.stat().st_size > 1024 * 1024:
        raise SystemExit('Measurement report is too large')
    print(json.dumps(apply(Store(args.data_dir), json.loads(args.report.read_text(encoding='utf-8')))))

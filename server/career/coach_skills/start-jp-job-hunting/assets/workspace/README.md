# `.jobhunt` ワークスペース v3

このフォルダは就活の継続用正本です。個人情報を含み得るため、標準ではGit追跡対象外です。

## 正本

- `manifest.json`: スキーマ、revision、同意、タイムゾーン
- `facts.csv`: 本人事実・推論・未確認を分離した構造化事実
- `hypotheses.csv`: 強み、志望、選考原因等の検証可能な仮説
- `profile.md`: 本人が読みやすいプロフィール
- `story-bank.md`: ES・面接に使う経験と証拠
- `target-companies.csv`: 候補企業と応募ポートフォリオ
- `applications.csv`: 企業×卒年×選考区分×コースの応募案件
- `tasks.csv`: 複数タスク、期限、依存関係
- `documents.csv`: ES等の設問、版、提出状態
- `selection-events.jsonl`: 選考観測の追記型ログ
- `selection-log.md`: 人間向けの振り返り記録
- `source-ledger.csv`: 外部情報の出典と鮮度
- `decision-log.md`: 意思決定と見直し条件
- `contacts.csv`: 明示同意を得た連絡先のみ
- `documents/`: ES等の追記型版ファイル
- `backups/`: 状態更新前バックアップ
- `patches/`: 値を含まないlocal audit metadata。GitHub対象外
- `raw/`: 保存を明示許可された元資料
- `exports/`: 縮約状態等。正本ではなく匿名化保証なし

## 原則

- `CONFIRMED` は本人の直接発言または本人の一次資料で確認できる事実だけにする。
- AIの推論は `INFERRED` または `hypotheses.csv` に置き、本人確認なしに昇格させない。
- 時刻不明の締切を23:59等へ補完しない。
- 同名企業でも別年度・別コースは別応募案件にする。
- 提出済み文書を黙って上書きしない。
- 連絡先保存は `consents.store_personal_contacts=true` の場合だけ行う。
- 機微情報保存は `consents.store_sensitive_notes=true` の場合だけ行う。
- パスワード、Cookie、ワンタイムコード、APIキー、秘密鍵を保存しない。
- 複数ファイル更新は状態patchをpreviewし、shared lock内でrevisionを二重照合して適用する。
- patch監査へraw valuesやnoteを残さない。

検証:

```bash
python3 scripts/validate_workspace.py .jobhunt
```

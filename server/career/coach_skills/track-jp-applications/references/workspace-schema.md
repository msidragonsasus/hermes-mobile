# `.jobhunt` ワークスペース・スキーマ v3

## ID

`FACT-###`、`HYP-###`、`ST-###`、`CO-###`、`APP-###`、`TASK-###`、`DOC-###`、`EV-###`、`DEC-###`、`SRC-###`、`CT-###`。表示名変更でIDを変えない。

`DEC-###`は意思決定、`EV-###`は構造化された選考観測を表す。旧selection-log.mdの`SEL-###`はログ見出しであり、stateの参照IDには使わない。構造化時に既存イベントを照合し、未登録の場合だけ`EV-###`を採番して旧見出しとの対応を保持する。見出しの数値だけで既存EVへ結び付けたり、ログを書き換えたりしない。

## 正本

正確な列順、code値、ID正規表現は、このSkillの`scripts/workspace_schema.py`を正本とする。CSV行は列を省略せず、状態patchでは完全行を送る。

## 主要ファイル

- `facts.csv`: 本人事実、未確認、推論候補、旧情報。`CONFIRMED / INFERRED / UNKNOWN / SUPERSEDED`
- `hypotheses.csv`: 支持・反証事実、信頼度、次のtest、見直し期限
- `target-companies.csv`: 応募前候補
- `applications.csv`: 企業×卒年×選考区分×courseの応募案件
- `tasks.csv`: 一つの完了条件を持つ作業。`dependency_ids`は`|`区切り
- `documents.csv`: 設問、字数、版、本文path、根拠ID、SHA-256
- `selection-events.jsonl`: 全fieldを持つ追記型`EV-###`
- `source-ledger.csv`: 年度・course・確認日時・鮮度を持つ外部出典
- `contacts.csv`: 明示同意済み連絡先だけ。GitHub対象外
- `patches/`: 値を含まないlocal audit metadata。GitHub対象外

## 締切精度

- `exact`: `YYYY-MM-DD HH:MM JST`
- `date_only`: `YYYY-MM-DD`
- `unknown`: 値は空欄

時刻不明を23:59へ変換しない。

## 状態code

- 応募: `unknown / considering / preparing / submitted / in_process / offered / rejected / withdrawn / closed`
- task: `todo / doing / blocked / done / cancelled`
- document: `idea / drafting / review / ready / submitted / archived`
- priority: `P0`〜`P9`

## 更新

- `JOBHUNT_PATCH_V3`の`base_revision`と現在の`workspace_revision`が一致する場合だけ適用する。
- `profile.md`は`replace_text`、`story-bank.md`、`selection-log.md`、`decision-log.md`は`append_text`でtransactionへ含める。
- document version saveもrevisionを増やし、同じshared lockを使う。
- staging全体を検証し、commit直前にrevisionを再照合する。
- 差分なしではrevisionを増やさない。
- auditにraw valuesやnoteを残さない。

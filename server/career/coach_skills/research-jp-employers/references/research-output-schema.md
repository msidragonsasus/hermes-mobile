# 企業・募集調査の出力スキーマ

## ID

- `CF-###`: 企業・事業・職種に関する公式事実
- `RF-###`: 募集要項・選考区分・プログラムに関する公式事実
- `DL-###`: 締切・日時
- `SRC-###`: 出典
- `FIT-###`: 本人事実と企業事実の接続候補

## 事実レコード

```yaml
fact_id: CF-001
claim: ""
source_id: SRC-001
target_year: "対象年度"
selection_type: "internship / main / event / unknown"
program_or_role: ""
region: ""
status: "confirmed_official / confirmed_secondary / inferred / unknown / conflicted"
checked_at_jst: "YYYY-MM-DD HH:MM JST"
notes: ""
```

## 締切レコード

```yaml
deadline_id: DL-001
application_id: "APP-001"
label: "ES提出"
deadline_jst: "YYYY-MM-DD HH:MM JST / YYYY-MM-DD / blank"
deadline_precision: "exact / date_only / unknown / none"
source_id: "SRC-001"
checked_at_jst: "YYYY-MM-DD HH:MM JST"
```

`date_only` を `23:59` に変換しない。メールと公式ページで日時が異なる場合は `CONFLICTED` とし、応募者向け最新通知を優先できるかを確認する。

## 出典レコード

```yaml
source_id: SRC-001
title: ""
url: "https://..."
source_type: "official_recruit / official_corporate / official_ir / official_news / secondary / user_provided"
target_year: ""
program_or_role: ""
published_or_updated_date: "YYYY-MM-DD / unknown"
checked_at_jst: "YYYY-MM-DD HH:MM JST"
access_status: "ok / login_required / blocked / removed"
freshness_status: "current / stale / unknown"
```

## 本人との接続

接続候補は次の両方があるときだけ作る。

1. 本人の確認済み事実ID (`PF-###` / `ST-###`)
2. 企業・募集の確認済み事実ID (`CF-###` / `RF-###`)

```yaml
fit_id: FIT-001
person_evidence_ids: [ST-001]
company_fact_ids: [CF-002]
connection: ""
confidence: "low / medium / high"
counterevidence: ""
```

会社説明を本人の志望理由へ言い換えただけの接続は作らない。

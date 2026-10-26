# 適性検査準備フレームワーク

## 判定レベル

- `confirmed`: 案内文または提供元の公式情報で確認
- `likely`: URL、画面、所要時間等から推定できるが未確定
- `unknown`: 証拠不足または複数候補

## 診断軸

| 領域 | 観測値 | 主な誤答原因 |
|---|---|---|
| 言語 | 正答率、1問時間 | 語彙、関係把握、設問読み違い |
| 計数・非言語 | 正答率、完答率 | 立式、公式、計算、場合分け |
| 論理 | 正答率、検算時間 | 条件整理、飛躍、表の作り方 |
| 英語 | 正答率、読了時間 | 語彙、構文、情報検索 |
| 操作・速度 | 未回答数、誤操作 | 画面慣れ、時間配分、入力 |

## 誤答ログ

```yaml
assessment_error:
  date: "YYYY-MM-DD"
  domain: ""
  question_type: ""
  result: "correct / incorrect / timeout"
  elapsed_seconds: 0
  cause: "knowledge / interpretation / setup / calculation / pacing / operation"
  correction: ""
  next_drill: ""
```

同じ原因が複数回観測されるまで、能力の固定的な弱みと断定しない。

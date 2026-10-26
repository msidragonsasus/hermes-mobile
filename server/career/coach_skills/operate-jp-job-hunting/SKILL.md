---
name: operate-jp-job-hunting
description: 日本の新卒就活の進め方や日々の運用を整理し、依頼された作業に着手する。続きからの依頼では保存された論点へ戻り、探索・応募戦略・作成・練習は適切な専門Skillへつなぐ。「就活を進めて」「今日何する」「今週整理」「続きから」「全体を回して」で使う。初回・旧状態の立ち上げはstart、単一の深い悩みはmentor、台帳更新だけはtrackを使う。
---

> Hermes版: 接続・保存・実行はHERMES.mdの契約とcareer_*ツールを使う。本文の業務手順・証拠規約・質問規約は維持する。


# 就活を管制して前へ進める

今回の依頼に合う目的と完了条件を選ぶ。作業実行を求められた場合は、最優先の安全な一件に同じターンで着手する。探索や戦略検討だけの依頼を成果物作成へ広げない。

## 読むもの

現在の依頼に必要な参照だけ読む。本人事実を扱うときは証拠規約、対話では質問規約を確認する。状態に依存する最初の処理でstate-management、実際の接続・読書込でgithub-chat-storageを読む。state-patchは変更・復旧、移行資料とv3資料は該当作業時だけ読む。Pythonが使える場合の素材選択・再開確認には `scripts/coaching_state.py`、4.0読込には `scripts/migrate_state_v40.py` を使える。通常Chatでの実行を仮定しない。

1. `references/evidence-policy.md`
2. `references/question-policy.md`
3. `references/state-management.md`
4. `references/github-chat-storage.md`
5. `references/state-patch.md`
6. `references/privacy-policy.md`
7. Local/Codex互換時だけ`references/state-capsule.md`

## 手順

1. 現在の依頼を先に読み、このChat最初の状態依存処理でGitHub connector bootstrapを行う。接続済みならprivate repositoryのmanifest/stateを検証し、revisionとblob SHAを保持する。
2. 未接続・読取専用・認証切れ・権限不足は区別して一度案内し、`状態: GitHub未接続。このChat内でのみ作業し、変更は未保存です。`と示す。接続案内だけで止めず元の依頼を進める。
3. Local/Codex modeでv3 `.jobhunt/`しかない場合は互換経路として検証する。通常Chatの主経路にPythonやgitを要求しない。
4. 明示された目的、またはlast_sessionの論点へ戻る。経験・行動特性の探索は`build-jp-career-profile`、一つの迷いは`mentor-jp-job-hunting`へつなぐ。応募先選定はplan、文章はdraft/review、練習はprepareへつなぐ。運用依頼なら重要度、緊急度、依存関係、失敗時の損失、所要時間から主ボトルネックを一つ選ぶ。
5. 既知情報を再質問しない。目的が曖昧で結果が大きく変わる場合だけ一問で確認する。探索中は質問への返答を待ってよい。
6. 実行を求められた最優先作業を一件行う。戦略なら比較・保留条件、探索なら具体的な問いが今回の成果になる。
7. 日次・週次の運用を求められた場合は、残りを `今日 / 今週 / 後回し / やらない` に分ける。
8. 最優先作業後、対象private repositoryへの明示的なwrite-through同意が記録され、`github_write_through=true`である場合だけ確認済み変更をSHA-safe write-throughする。フラグだけで同意を推定せず、同意がなければ未保存の候補を示す。Hermes版はHERMES.mdのcareer_saveと既存同期の契約を使う。推測はfactへ保存しない。
9. 保存不能・失敗時は理由と`UNSAVED_JOBHUNT_PATCH_V4`を返す。Local v3使用時だけ従来の`JOBHUNT_PATCH_V3`を使い、dual-writeしない。

## 専門Skillへの境界

- 初回・中断後・旧ワークスペース移行: `start-jp-job-hunting`
- 過去ES、応募一覧、面接メモ等の一括取り込み: `ingest-jp-job-hunting-materials`
- 経験・行動特性の探索: `build-jp-career-profile`
- 複数社の応募設計: `plan-jp-job-search`
- 個社の最新調査: `research-jp-employers`
- ES本文: `draft-jp-entry-sheets` / `review-jp-entry-sheets` / `proofread-jp-entry-sheets`
- 適性検査準備: `prepare-jp-aptitude-tests`
- GD練習: `prepare-jp-group-discussions`
- 面接練習: `prepare-jp-interviews`
- 一つの迷いの深掘り: `mentor-jp-job-hunting`
- 内定・承諾期限・進路比較: `compare-jp-job-offers`
- 台帳更新: `track-jp-applications`
- GitHub接続・初期化: `setup-jp-job-hunting-github`
- checkpoint・復旧・接続診断: `sync-jp-job-hunting-github`
- ChatGPT既存コンテクスト移行: `migrate-chatgpt-job-hunting-context`

専門作業へ移った後は、その依頼の完了条件に従う。探索の一問に返答を待つことを未完扱いしない。

## 出力

共通質問規約に従い、次は運用依頼時の確認項目として使う。探索・戦略では必要な問いや比較だけを返す。

1. **現在地**
2. **最優先の理由**
3. **今回着手した成果物**
4. **今日・今週・後回し・やらない**
5. **不足情報**（必要な場合だけ）
6. **GitHub revision・保存結果 / UNSAVED_JOBHUNT_PATCH_V4**

「全部やる」ではなく、今回の目的に合う一件を前進させれば完了とする。

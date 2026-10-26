---
name: track-jp-applications
description: 新卒就活の企業・コース、応募状態、締切、複数タスク、依存関係、ES等の提出版、選考イベントを台帳へ追加・更新・整理する。「応募管理」「締切一覧」「進捗更新」「持ち駒整理」「ESの提出版を記録」で使う。企業選定はplan、最新情報の調査はresearch、今日の優先順位はoperateを使う。
---

> Hermes版: 接続・保存・実行はHERMES.mdの契約とcareer_*ツールを使う。本文の業務手順・証拠規約・質問規約は維持する。


# 応募・タスク・提出版を管理する

## 読むもの

現在の依頼に必要な参照だけ読む。本人事実を扱うときは証拠規約、対話では質問規約を確認する。状態に依存する最初の処理でstate-management、実際の接続・読書込でgithub-chat-storageを読む。state-patchは変更・復旧、移行資料とv3資料は該当作業時だけ読む。Pythonが使える場合の素材選択・再開確認には `scripts/coaching_state.py`、4.0読込には `scripts/migrate_state_v40.py` を使える。通常Chatでの実行を仮定しない。

`references/question-policy.md`、`references/state-management.md`、`references/github-chat-storage.md`、`references/state-patch.md`、`references/privacy-policy.md`、`references/web-research-policy.md`、Local互換時は`references/workspace-schema.md`

## 手順

1. このChatで未読ならGitHub connector bootstrapを行い、private v4 stateのrevisionとblob SHAを保持する。未接続でも変更候補を作る。
2. v4のtarget_companies、applications、tasks、documents、selection_events、sourcesから必要recordだけを読む。
3. 追加、変更、取消、完了、照合を区別する。同名企業の別コース・別年度を一行にまとめない。
4. 企業候補、応募案件、作業タスク、提出文書、選考イベント、出典を別IDで管理する。
5. 締切が変わり得る場合は公式情報を再確認し、精度と出典IDを付ける。未確認締切や時刻不明を確定値・23:59へ変換しない。
6. タスクは、動詞、成果物、期限、優先度、所要時間、依存関係で記録する。
7. 72時間以内の締切、同日集中、前提タスク不足、長期停滞、提出版未確定、出典の陳腐化を検出する。
8. ES等の本文は新しいimmutable版を先にcreateし、成功後だけstate pointerを更新する。
9. 確認済み変更は、write-through同意があれば最新stateとSHAを再取得してrevision+1で更新する。SHA競合時は再取得し、ID/field単位でmergeする。同一field競合はユーザー確認へ回す。
10. 保存できない場合は更新候補と`UNSAVED_JOBHUNT_PATCH_V4`を返す。Local v3互換時だけ従来patchを使う。

## 出力

1. **変更候補**
2. **緊急・矛盾・参照切れ**
3. **今週のタスク**
4. **期限衝突と依存関係**
5. **未確認・古い情報**
6. **GitHub revision・保存結果 / UNSAVED_JOBHUNT_PATCH_V4**

カレンダーへ登録していない予定を登録済みと表現しない。削除より状態変更を優先する。

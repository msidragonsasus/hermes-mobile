---
name: start-jp-job-hunting
description: 日本の新卒就活を初めて始める、長期間中断後に再開する、現状が散らかっている、旧ワークスペースを移行する場合に、段階、制約、直近期限、最初の7日間を整理する。「就活を始めたい」「再開したい」「何からやる」「状況を初期化」「旧版から移したい」で使う。資料の一括構造化はingest、日常運用はoperate、単なる新Chatの続きは保存された論点に対応するSkillを使う。
---

> Hermes版: 接続・保存・実行はHERMES.mdの契約とcareer_*ツールを使う。本文の業務手順・証拠規約・質問規約は維持する。


# 就活を立ち上げる

自己分析シートの提出を前提にせず、後続Skillを使える最小状態を作る。既存データがある場合は破壊せずに移行・取り込みへ接続する。

## 読むもの

現在の依頼に必要な参照だけ読む。本人事実を扱うときは証拠規約、対話では質問規約を確認する。状態に依存する最初の処理でstate-management、実際の接続・読書込でgithub-chat-storageを読む。state-patchは変更・復旧、移行資料とv3資料は該当作業時だけ読む。Pythonが使える場合の素材選択・再開確認には `scripts/coaching_state.py`、4.0読込には `scripts/migrate_state_v40.py` を使える。通常Chatでの実行を仮定しない。

`references/evidence-policy.md`、`references/question-policy.md`、`references/state-management.md`、`references/github-chat-storage.md`、`references/chatgpt-context-migration.md`、`references/state-patch.md`、`references/privacy-policy.md`

## 手順

1. GitHub connector bootstrapを行い、private repositoryのv4 stateがあれば検証して続きから始める。未接続でも現状診断を進める。
2. GitHub未初期化なら`setup-jp-job-hunting-github`へ委譲し、初期化内容と同意をpreviewする。通常ChatでローカルCLIを要求しない。
3. Local/Codex modeで`.jobhunt/manifest.json`がある場合は互換状態として確認する。
   - v3: `scripts/validate_workspace.py` で整合性を検証し、初期化で上書きしない。
   - v1/v2: `scripts/migrate_workspace_to_v3.py` を使い、必ず別の移行先へ非破壊移行する。
   - 判定不能・破損: 原本を保全し、修復候補と不足を示す。推測で書き換えない。
4. 過去ES、応募一覧、面接メモ等がある場合は、初期化後に `ingest-jp-job-hunting-materials` へ回す。ChatGPT既存コンテクストは専用migrate Skillを提案する。
5. 卒年、就活段階、現在の応募、絶対に外せない制約、7日以内の期限を抽出する。
6. 既知の情報は聞き直さず、必要な不足だけ確認する。経験を探索する段階なら具体的な一問に返答を待ってよい。
7. 主ボトルネックを一つ選ぶ。
8. Local/Codex互換として新規v3 workspaceが必要な場合だけ`scripts/init_workspace.py`を使う。既存v3からGitHub v4へは`scripts/migrate_workspace_v3_to_v4.py`で別出力へ非破壊移行する。
9. GitHub v4の初期変更は同意後にSHA-safe write-throughする。未保存なら`UNSAVED_JOBHUNT_PATCH_V4`を返す。
10. 立ち上げ計画を求められた場合は、最初の7日間を成果物、期限、所要時間の目安で設計する。経験素材が不足する場合は `build-jp-career-profile` を次の主作業にする。
11. GitHubへ書けない場合はephemeral modeと未保存差分を返し、保存したと装わない。

## 出力

1. **現状診断**
2. **CONFIRMED / INFERRED / UNKNOWN / CONFLICTED**
3. **既存状態の検証・移行判断**
4. **主ボトルネック**
5. **最初の7日間**
6. **GitHub初期化・読込結果またはephemeral状態**
7. **保存結果またはUNSAVED_JOBHUNT_PATCH_V4**
8. **次に答える質問**（必要な場合だけ最大3問）

成功や内定を保証しない。原本を壊さず、次の一件に着手できる状態になれば完了とする。

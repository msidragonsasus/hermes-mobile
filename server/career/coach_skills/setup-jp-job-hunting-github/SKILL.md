---
name: setup-jp-job-hunting-github
description: 就活コーチのChat-first永続状態を、書込対応GitHubプラグインとprivate repositoryへ安全に接続・初期化する。「GitHubに接続」「初期設定」「状態保存を有効」「接続した」で使い、他の状態依存Skillが未接続・未初期化を検出した場合も診断へ委譲できる。接続案内は暗黙で行えるが、外部書込はpreviewと同意後だけ行う。
---

> Hermes版: 接続・保存・実行はHERMES.mdの契約とcareer_*ツールを使う。本文の業務手順・証拠規約・質問規約は維持する。


# GitHub Chat storageを初期設定する

## 読むもの

現在の依頼に必要な参照だけ読む。本人事実を扱うときは証拠規約、対話では質問規約を確認する。状態に依存する最初の処理でstate-management、実際の接続・読書込でgithub-chat-storageを読む。state-patchは変更・復旧、移行資料とv3資料は該当作業時だけ読む。Pythonが使える場合の素材選択・再開確認には `scripts/coaching_state.py`、4.0読込には `scripts/migrate_state_v40.py` を使える。通常Chatでの実行を仮定しない。

`references/github-chat-storage.md`、`references/state-management.md`、`references/privacy-policy.md`、`references/chatgpt-context-migration.md`

## 手順

1. 利用可能なtoolの説明と入出力から、repository検索、visibility確認、file読取、file作成、SHA付きfile更新の能力を判定する。製品名や厳密なtool名だけで決めない。
2. `UNAVAILABLE / READ_ONLY / AUTH_REQUIRED / PERMISSION_DENIED / REPOSITORY_MISSING / READY_PRIVATE`を区別する。未接続・読取専用・認証切れ・権限不足ならreferenceの実際の案内文を返す。
3. ユーザー明示slug、会話内slug、標準名`jp-job-hunting-memory`、marker、manifestの順で対象を特定する。全repositoryを無差別に探索せず、複数候補は名前だけを示して選択を待つ。
4. GitHub側の現在情報でprivate性を確認する。publicまたは確認不能なら読取・書込を停止する。manifest/stateの読取前に、対象repositoryを示して`github_state_read`の明示同意を確認する。書込や移行の同意で読取同意を代用しない。repositoryがなければ手動作成条件を案内し、作成済みと装わない。
5. `.jobhunt/manifest.json`と`.jobhunt/state.json`を読む。既存stateがあれば検証して続きから始め、上書きしない。
6. 未初期化なら、schema 4.0のmanifest、revision 1の空state、`.jobhunt/README.md`の作成内容をpreviewする。
7. 読取同意とは別に「このprivate repositoryを正本にする」「確認済み変更をwrite-throughする」「既存ChatGPTコンテクストの移行候補を作る」の同意を分けて確認する。
8. 同意後にmanifest、state、READMEをcreateする。既存pathが見つかったら上書きせず再取得する。成功したfileとcommitだけを報告する。
9. 初回成功後に一度だけ、今ある範囲、Memory Summary、data export、今回は移行しない、の選択肢を案内する。「今ある範囲から」なら同じターンで候補を作る。
10. 呼出元の就活依頼があれば、接続診断だけで止めず同じターンで安全な成果物へ戻る。

## 出力

1. **能力・認証・権限状態**
2. **対象repositoryとprivate確認**
3. **既存stateまたは初期化preview**
4. **必要な同意**
5. **作成・読込結果**
6. **元の就活依頼の成果物／未保存差分**

repository作成・初期化・private確認・保存を実行していない場合は、実行済みと表現しない。

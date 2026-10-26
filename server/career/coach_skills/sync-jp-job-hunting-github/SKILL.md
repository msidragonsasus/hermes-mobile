---
name: sync-jp-job-hunting-github
description: Chat modeでは書込対応GitHubプラグインによるv4状態の読込、明示checkpoint、復旧、接続診断を行い、Local/Codex modeでは既存github_sync.pyによるv3の安全なcommit、push、fast-forward取得を行う。「同期」「checkpoint」「復旧」「GitHubから取得」で明示的に使う。public repoや分岐履歴の自動mergeには使わない。
---

> Hermes版: 接続・保存・実行はHERMES.mdの契約とcareer_*ツールを使う。本文の業務手順・証拠規約・質問規約は維持する。


# GitHub状態のcheckpoint・復旧・接続診断

## 読むもの

現在の依頼に必要な参照だけ読む。本人事実を扱うときは証拠規約、対話では質問規約を確認する。状態に依存する最初の処理でstate-management、実際の接続・読書込でgithub-chat-storageを読む。state-patchは変更・復旧、移行資料とv3資料は該当作業時だけ読む。Pythonが使える場合の素材選択・再開確認には `scripts/coaching_state.py`、4.0読込には `scripts/migrate_state_v40.py` を使える。通常Chatでの実行を仮定しない。

`references/github-chat-storage.md`、`references/state-management.md`、`references/privacy-policy.md`、`references/github-sync-policy.md`、`references/state-patch.md`

## Chat mode

1. tool能力、認証、権限、repository候補、現在visibilityを診断する。
2. private確認後にmanifest/stateを取得し、schema、revision、参照、秘密情報を検証する。
3. 明示checkpointでは、未保存の確認済み差分だけを最新SHAへwrite-throughする。通常の同意済み状態更新は、ユーザーが毎回syncと言わなくても各専門Skillが保存する。
4. stale SHAでは上書きせず再取得し、異なるrecord/fieldだけmergeする。同一field競合は確認を求める。
5. 復旧時はGitHub v4を正本とし、raw Chat、local-only data、未確認推論を追加しない。
6. 保存失敗時は理由、local/remoteのどこまで成功したか、`UNSAVED_JOBHUNT_PATCH_V4`を返す。

## Local/Codex mode

既存v3 `.jobhunt/`の移行、検証、一括処理、backup互換経路として`scripts/github_sync.py`を使う。共有lock、revision、private確認、credential-free remote、allowlist、現在indexと到達可能なGit履歴の危険path検出、remote-ahead/diverged拒否、fast-forward-only取得を維持する。

`contacts.csv`、`patches/`、`raw/`、`exports/`、`backups/`、`snapshots/`、lock、staging、secretは常に対象外。v3とv4をdual-writeしない。

## 出力

1. **modeと接続状態**
2. **repository・private確認方法**
3. **revision・SHAまたはlocal commit関係**
4. **実際に完了した範囲**
5. **local-only／除外対象**
6. **未保存差分・競合・次の安全な操作**

GitHubへ反映できていない場合は完了したと装わない。通常運用でfileを自動削除しない。

# プライバシー・権限ポリシー v4

目的に不要な私的情報を読まない・保存しない・出力しない。GitHub保存前にrepositoryの現在visibilityがprivateであることをGitHub側から確認し、確認不能、public、visibility変更時は停止する。

## 分離する同意

- `github_state_read`
- `github_write_through`
- `import_chatgpt_context`
- `store_sensitive_notes`
- `store_personal_contacts`

GitHub読取を、書込、削除、sensitive保存、contact保存の許可として扱わない。repository初期化、大規模移行、sensitive保存、削除は暗黙実行しない。

## GitHubへ保存禁止

- contacts、第三者のemail・電話・住所
- password、OTP、Cookie、session token、API key、private key、GitHub token、MyPage認証情報
- 生Chat（raw Chat）、ChatGPTデータエクスポート原本、raw ZIP、添付原本
- backup、snapshot、raw patch payload、local audit
- 未同意の医療・服薬・精神状態等のsensitive情報

秘密情報は候補化、patch、文書版、state検証、GitHub書込、local Git同期の各境界で拒否する。本文をGitHubへ保存する場合もimmutable文書版だけとし、秘密情報検査を通す。

## External side effects

メール読取、メール送信、calendar書込、GitHub write-through、local commit、push、pull、deleteを別権限として扱う。通常運用でGitHub `delete_file`を自動実行しない。失敗時は未保存とし、秘密情報を含まない最小patchだけを返す。

## Local v3 safety floor

v0.8.1の共有lock、revision、allowlist、credential-free remote、private確認、current indexと到達可能なGit履歴における`contacts.csv`、`patches/`、`raw/`、`exports/`、`backups/`等の検出を維持する。`.gitignore`だけを安全境界にしない。

# Workspace MCPを使う構成

Drive・Calendar操作は既存の
[taylorwilsdon/google_workspace_mcp](https://github.com/taylorwilsdon/google_workspace_mcp)
を使用する方針。既存Gmailの認証・同期・アカウント管理は保持する。
操作を個別に自作するプロトタイプは取り下げた。

## 確認したこと

2026-10-20、`workspace-mcp==2.1.0`をWindows上の隔離環境で起動し、
MCP initialize / tools/listが成功した。実アカウントや本番tokenは使用していない。
`--tools drive calendar docs --tool-tier extended`で33ツールが登録され、
`update_drive_file`、`modify_doc_text`、`manage_event`を確認した。
その後、稼働中の`hermes-node`にも導入・33ツール登録を確認したとの
Hermes側報告を受領した。Google認証は本人が完了し、専用領域へのcredential保存と既存Gmail認証の保全をHermes側で確認済み。2026-10-20 04:37 JST、通常HermesのMCP経由でDrive・カレンダー各1件の一覧取得成功との報告を受領した。実際の作成・編集・削除は試験していない。
現在の完了部分と残作業は[導入記録](DEPLOYMENT-20261020.md)を参照。

このMCP自体はMITのOSSで、自分の環境で実行できる。Composioや有料の
ホスティングを契約する必要はない。Google側のAPI利用条件・割当は別に適用される。

## Hermes設定の準備

[workspace-mcp.example.yaml](workspace-mcp.example.yaml)は追加候補の設定例。
これは汎用例であり、稼働設定のコピーではない。既存の`mcp_servers`に1項目を追加する形にする。
ファイル全体でconfigを上書きしない。

- Python 3.10以上と`uvx`が実行可能なHermesホストで起動する。
- 例の`/PATH/TO/HERMES_HOME`を実際の絶対パスに置き換える。
- OAuthクライアントは既存のJSONを参照可能だが、実際のGoogle Cloud設定と
  callback URIがMCPのOAuthフローに対応するか確認する。
- Drive・Calendar・Docs APIを有効にし、必要な追加権限をGoogleで許可する。
- MCPの認証保存先は既存Gmailとは別にする。既存tokenを移動・書換・共有しない。
  Googleアカウントが同じでも、MCP側のログインが必要になる場合がある。
- Hermesをサーバーで動かしスマホで認証する場合、callbackの`localhost`は
  スマホ自身を指す。既存の信頼済みLAN/Tailscaleで到達できるcallbackを設定する。
  MCPを公開Internetへ直接露出させない。
- MCP接続、ツール登録、追加認証、実際の読み書きを別々に確認する。
  書込の承認はHermes側の実版の制御経路を確認してから有効にする。

## 初回のGoogleログイン

ホストのHTTPS経路とGoogle OAuth callback設定後、通常のHermes会話から
Drive/Calendarツールを使い、MCPが返すGoogleログインリンクを開いて追加権限を許可する。
MobileのMCP管理画面にある標準の認証ボタンはHTTP型MCPのOAuth用であり、
今回のstdio型Workspace MCPのGoogleログインを代行するものではない。

MCP側のGoogleログインとMobileの「Google接続」画面のGmail状態は現在別管理。
Googleアカウントが同じでも初回のMCP認証が必要になる。

参照: [公式Quick Start](https://workspacemcp.com/quick-start)、
[認証保存実装](https://github.com/taylorwilsdon/google_workspace_mcp/blob/v2.1.0/auth/credential_store.py)。

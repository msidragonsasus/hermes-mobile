# Workspace MCP導入記録（2026-10-20）

状態: **本番MCP導入・本人のGoogle認証・通常Hermes経由のDrive/Calendar一覧読取確認まで完了**。
本人が権限を許可し、通常Chromeで「Authentication Successful」と認証情報保存完了を確認した。通常Hermesの実データ読取テストはHermesの通常承認待ち。

依頼ID: `GOOGLE-WORKSPACE-MCP-20261020-01`。
サーバー上の操作と検証は、指定経路の
[Slack #hermes-bro](https://chatgpt-lll7864.slack.com/archives/C0C74STS7D4/p1792434284315129)
でHermesへ依頼した。以下のサーバー情報は同スレッドの03:35 JST報告による。
bro側からSSHやRelayの代替経路で実行・確認したものではない。

## 完了した部分

- 実ホスト`hermes-node`、Mobile/Slack通常会話と同じ`default`プロファイル。
- `HERMES_HOME=/var/lib/hermes/.hermes`。
- Hermes `0.21.5+6562` / commit `b2860025`。
- `workspace-mcp==2.1.0`を専用runtime
  `/var/lib/hermes/.hermes/venvs/workspace-mcp-2.1.0`へ配置。
- 設定変更は`mcp_servers.google-workspace`のみ。他項目の差分なしを報告。
- Drive/Calendar/Docs、extended構成でMCP handshake成功、Hermesレジストリに33ツール。
  `update_drive_file`、`modify_doc_text`、`manage_event`を含む。
- 稼働Gatewayが設定を自動検出しMCPを起動。サービス再起動なし。
- `trust: untrusted`で書込は承認必須。MCPからの追加LLM呼出しは無効。
- MCPにGmailサービスをロードしていない。専用credential directoryは別配置、0700。
- `google_token.json`・既存OAuth JSON等の保全対象138ファイルのハッシュ差分なし。
  既存Gmailのアカウント別認証、同期、送信者制限、Career DB、会話/モデル設定を保持。
- Slack/Mobile/Career用Gatewayは稼働継続、Mobile health HTTP 200を報告。
- 実ファイル/予定の作成・変更・削除、実メール読取試験は実施していない。

バックアップ/サーバー検証記録:
`/var/lib/hermes/.hermes/backups/GOOGLE-WORKSPACE-MCP-20261020-01-20261020T033035/`

## 設定・認証の実施記録

### 1. 管理者によるprivate HTTPS設定

既存Web型`google_mobile_client_secret.json`の参照設定は追加済み。
導入時は認証callbackに必要なTailscale HTTPS経路がなく、実行ユーザーは証明書取得権限と
sudo権限を持たない。権限の回避は行っていない。

Hermesから提示された管理者操作（本人が実行し、HTTPS有効化リンクを受領）:

```sh
sudo tailscale serve --bg --https=443 http://127.0.0.1:8000
```

これはTailscale内限定のHTTPS転送。FunnelやInternet公開は使用しない。
既存Serve設定が変わっていた場合はその設定を保持して調整する。
実行時にHTTPS有効化の所有者承認が出る場合は、その操作も必要。

本人から共有された公式Tailscale画面を通常Chromeで開き、2026-10-20 04:18 JSTに「Serve is ready to use」を確認した。04:19 JSTのHermes報告ではServe設定済み・HTTPS証明書検証成功。04:22 JSTのHermes報告では通常HermesのMCPからlistenerを起動し、認可コードなしのcallbackに想定どおりHTTP 400が返ることまで確認。sudo再実行は不要。

### 2. Google Cloud設定（完了）

既存プロジェクト: `ageless-arcanum-508105-m2`。
本人が通常Chromeで管理アカウントへ切り替えた後、既存Webクライアント
「Hermes Career Google Sign-In」に次のredirect URIを追加し、
「OAuth クライアントを保存しました」の成功表示を確認した。

```text
https://hermes-node.tailc08fde.ts.net/oauth2callback
```

既存redirect URIは空だった。秘密の追加・無効化・ローテーション、AI-powered agent
フラグの変更、新規プロジェクト/クライアントへの置換は行っていない。
公開client ID:
`123067273555-20f4d7qr42jtdseg1oo8g488updt1jc0.apps.googleusercontent.com`
04:19 JSTのHermes報告で、サーバーの既存OAuth JSONとこのclient IDの完全一致を確認。

同じプロジェクトの「有効なAPIとサービス」で、Google Drive API、Google Calendar API、
Google Docs APIがすべて既に有効と確認した。APIの有効化・無効化変更は不要だった。
Cloud設定の反映には時間がかかる場合がある。

保存成功の画面記録:
`E:\OSHIIRE\software\Hermes\output\google-workspace-mcp-20261020\oauth-client-saved.png`

### 3. 本人ログインと読取確認

HTTPS経路とCloud設定後に、callback到達を確認し、Google認証リンクを発行する。
本人がDrive/Calendar/Docsの追加権限を許可し、MCP credential保存・通常Hermes/Mobileの
読取まで確認する。認可コードやtokenをSlackへ送る手順にはしない。
04:22 JSTに認証リンク発行。本人が通常Chromeで権限を許可し、keiya0227d@gmail.comの「Authentication Successful」と認証情報保存完了の表示を確認した。04:28 JSTのHermes報告で専用credential保存・11 scope・Gmail scopeなし・既存Gmail認証のチェックポイント一致を確認。通常Hermes経由の実読取結果は次節のとおり。実書込は試験していない。
list_calendarsにreadOnlyHint=trueがないためuntrusted保護の承認を要求。Slack上のpermanently/session表示と本人の一回許可操作報告が食い違ったが、04:26 JSTのHermes確認では永続allowlistは導入前バックアップと一致し、今回の追加なし。実版MCP承認経路は各呼出しのみの同意として処理し保存しない。表示相違の原因は未特定。既存承認・trustは変更なし。

MobileのMCP管理画面は`http://100.103.190.68:9119/mcp`。
これはHermesの管理入口であり、Googleログインの代わりではない。

認証成功の画面記録: `E:\OSHIIRE\software\Hermes\output\google-workspace-mcp-20261020\google-workspace-auth-success.png`

## 最終の動作確認（04:37 JST）

[Hermesの最終報告](https://chatgpt-lll7864.slack.com/archives/C0C74STS7D4/p1792438660399189)
で以下を確認した。実サーバーの結果はHermes側報告であり、broが直接SSH/HTTPで検証したものではない。

- 通常HermesのMCP経由でカレンダー一覧1件取得成功、APIエラーなし。
- 初回Drive読取は承認期限切れでAPI呼出し前に停止。本人の再承認後に再試行し、Drive一覧1件取得成功、APIエラーなし。
- 読取試験前後で、既存Gmail認証を含む保全対象9ファイルにハッシュ差分なし。
- 設定・trust・承認timeoutは変更なし。サービス再起動なし。
- ユーザーのファイル・予定の作成、変更、削除は試験していない。
- 作成編集ツールの登録と必要なscopeの取得は確認済み。実際の書込操作・Mobile画面上の操作は未検証。
- 個人ファイル名・カレンダー名・データ本文・認可コード・tokenは報告に含めていない。

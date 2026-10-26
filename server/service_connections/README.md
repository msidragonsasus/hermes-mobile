# Slackのログイン接続

Hermes Mobileから「Slackに接続」を押すと、Slackのログイン・ワークスペース選択・
権限確認をブラウザで進め、アプリが完了を待つ。利用者へClient secretを入力させない。
読み取り専用のSlackアプリ登録とHermes側の初期設定は、管理者が一度用意する。

## 再利用した実装

- [Slack公式MCP](https://docs.slack.dev/ai/slack-mcp-server/): Slackがホストする
  `https://mcp.slack.com/mcp`を使用する。サービス操作やトークン更新を自作しない。
- [Slack公式OAuth実装](https://github.com/slackapi/bolt-python/blob/main/slack_bolt/oauth/oauth_flow.py):
  アプリ登録と利用者の許可を分離する設計を確認した。BoltはMCPのユーザーOAuthと
  エンドポイントが異なるためコピーせず、既存Hermes MCP OAuthを利用する。
- Hermesの`auth_mcp_server`、`DashboardOAuthFlow`、`HermesTokenStorage`、
  プロファイル分離、コールバックのstate検証・一回利用、認証失敗時の復旧処理を再利用。
  この拡張は既存MCPルーターへ追加する。隔離されたプラグインホストに別のOAuth
  レジストリを作らない。

## 導入前の確認

通常のSlackボット用`SLACK_BOT_TOKEN`・`SLACK_APP_TOKEN`は、この連携の認証には使わない。
既存の`mcp_servers.slack`が別URL・stdio・Bearer方式なら自動変更せず個別設定として扱う。
公開InternetへのHermes公開、Slackへの送信、OAuthアプリの自動作成、課金サービスの
追加、Googleアカウント統合は行わない。

1. 管理者がSlackの内部アプリまたは利用条件を満たす公開アプリを登録する。
   Slack側のAgents設定でMCPを有効にし、OAuthのユーザー権限と正確なredirect URLを登録する。
   アプリの公開・管理者承認条件によって接続可能なワークスペースは変わる。
2. 初期権限は`search:read.public`、`channels:history`、`channels:read`など、目的に必要な
   読み取りだけを選ぶ。`chat:write`や変更用権限はこの導入では追加しない。
3. 対象Hermesプロファイルのprivate `.env`へ`SLACK_MCP_CLIENT_ID`と
   `SLACK_MCP_CLIENT_SECRET`を保存し、`slack.example.yaml`の当該項目だけを既存設定へ追加する。
   config全体を上書きしない。値をチャット・ログ・APKへ入れない。
4. `redirect_uri`を、既存の信頼済みHTTPSの
   `/api/mcp/oauth/callback/slack`に置き換える。スマホ自身のlocalhostへ向けない。
5. 変更対象とSHA-256を確認する。以下はローカルのコード導入手順であり、サーバーへの
   SSHや外部実行経路を提供するものではない。稼働サーバーへの依頼はSlack #hermes-broを使う。

```powershell
python prepare_install.py --agent-root PATH_TO_HERMES_AGENT
# 上のplanと対象checkoutを管理者が確認してから適用する
python prepare_install.py --agent-root PATH_TO_HERMES_AGENT --apply
```

変更は`hermes_cli/mobile_service_connections.py`の追加と、MCPルーターへの登録だけ。
既存ソース・改行を保持し、適用時は対象2ファイルをバックアップする。自動再起動や
認証設定の変更はしない。実際の稼働プロセス・checkoutを確認してDashboardへ反映する。

別途登録したpublic/PKCEアプリはシークレットなしでも利用できる。
`client_secret`を省き、`token_endpoint_auth_method: none`を明示する。
[PKCE設定は一方向の変更](https://docs.slack.dev/authentication/using-pkce/)なので、
既存の稼働Slackボット用アプリへ自動的に切り替えない。

## APIと境界

- `GET /api/connections/slack/status`: `ready` / `authorized` / `setup_required` /
  `manual_connection`。登録情報・秘密値は返さない。`authorized`は保存済み認証情報を示し、
  常時のサービス疎通や全操作の成功を保証しない。
- `POST /api/connections/slack/connect`: `request_id`だけ受け付ける。利用者はURLや
  client情報を指定できない。同じsession/profile/request_idは同じ認証処理を参照し、再実行しない。
  再試行ではその処理の最新状態を返す。完了・失敗後に古い認証URLを開き直さない。
  アプリは通信結果が不明な場合に同じrequest_idを保持する。期限切れや開始拒否など、
  継続できない状態が確認できたら、次の明示的な接続操作で新しいrequest_idを発行する。
- `GET /api/connections/slack/flows/{flow_id}`と`DELETE ...`: 開始した検証済みDashboard
  Sessionと同じプロファイルに限定する。生のプロバイダーエラー・ツール・トークンは返さない。
- ブラウザからの戻りは既存MCPコールバック。Slackの許可ページだけをアプリから開く。
  state・PKCE・トークン交換は既存のHermes標準処理が担当する。
- 中止はコアのキャンセル契約を使う。現行版では`mark_error(..., cancelled=True)`を渡し、
  ワーカーの再試行で認証URLが再発行されないようにする。旧版の引数なし契約も維持する。
  通常の失敗を中止扱いにせず、本人が中止した処理だけを終端状態に固定する。
- 既存接続が無効な場合、認証しても勝手に有効化しない。利用開始は接続設定で明示する。

## 検証

```powershell
python -m unittest -v test_connections
```

仮のAPIとアカウントで、初回設定ゲート、秘密値非公開、二重開始防止、session/profile境界、
中止、URL固定、既存のボット設定不使用を確認する。Hermes標準OAuthの実アカウント認証、
Slack管理者承認、ワークスペース選択・検索成功、実機・サーバーへの反映は別に確認する。

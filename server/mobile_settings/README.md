# Hermes Mobile 利用制限

設定の「利用制限」へ、設定中のプロファイルのモデル提供元について、残り％と
リセット時刻だけを返す認証付きGETルート。

公開実装を調査し、Hermes標準の
[`agent.account_usage.fetch_account_usage`](https://github.com/NousResearch/hermes-agent/blob/main/agent/account_usage.py)
を `read_only=True` で再利用する。プロバイダーのHTTP処理・認証情報管理は実装しない。
[Resetwatch](https://github.com/Adolanium/hermes-resetwatch)も調査したが、Desktop向けで
別プローブの導入を伴うためコード・プローブは取り込んでいない。

## 契約と境界

- `GET /api/mobile-settings/account-limits?profile=...`。
  既存Dashboardの`_require_token`とMCPの`_profile_scope`を再利用する。
- 設定中の提供元が`openai-codex`、`anthropic`、`openrouter`の場合のみ照会する。
  自動選択・独自エンドポイント・未対応の提供元は推測しない。
- 対応するHermes本体が必要。`read_only`引数がない版では呼び出さない。
  新規認証・トークン更新・残り枠リセットの利用・モデル実行を開始しない。
- 本体が選ぶ認証情報の利用枠。会話固有のモデル変更やcredential pool内の実行中
  アカウントと同一であることは保証しない。生のprovider応答、identity、detail、
  トークン、例外本文を返さない。パーセントもリセット時刻も推定しない。
- UIは表示中のみ1分間隔で更新。通信失敗・未対応・5分を超えた古い値・リセット時刻を
  過ぎた値を現在の残り枠として表示しない。時刻経過だけで100％に戻さない。
- APIキー残高だけで期間上限が返らないサービスは「取得できません」。
  コンテキスト使用率、トークン総数、Nousの課金残高をこの枠へ代用しない。

## ローカルでの準備

```powershell
python server/mobile_settings/prepare_install.py --agent-root PATH_TO_HERMES_AGENT
```

管理者が対象checkoutと差分を確認した後に`--apply`を付けると、既存MCPルーターへ
このルーターを追加する。UTF-8/BOM/改行を保持し、ルーターをバックアップする。
異なる既存モジュールは上書きしない。サーバー反映・再起動・APKインストールは別工程。
このディレクトリを作成しただけでは稼働サーバーへ反映されない。

```powershell
python -m unittest discover -s server/mobile_settings -p "test_*.py"
```

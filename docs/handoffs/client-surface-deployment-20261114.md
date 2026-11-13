# Client surface 公開・本番・実機確認（2026-11-14）

ユーザー承認に基づき、フォークへのpushとAndroid更新まで実施。
本番反映は下記SlackスレッドでHermesが作業中。完了・実機E2E結果は追記する。

## 公開した固定コミット

| 対象 | ブランチ | コミット |
|---|---|---|
| [本体フォーク](https://github.com/msidragonsasus/hermes-agent/tree/feat/plugin-client-surfaces) | feat/plugin-client-surfaces | 59a3c3f90a23b946474ce1d5fafb24dc8021a818 |
| [アプリ・プラグイン](https://github.com/msidragonsasus/hermes-mobile-cloud/tree/feat/client-surface-negotiation) | feat/client-surface-negotiation | 2e936cf9807d4714121be01cb6356efe5677600e |

両方ともpush後に `git ls-remote` のSHA一致を確認。mainへの直接push・上流PRは行っていない。
アプリのリポジトリはprivate。既存default branchは `baseline/20261026` で、`dev` は存在しないため、新しい機能ブランチのみ公開した。

## Android（ローカルから実機で確認）

- 端末: Samsung SC-51F、ADB接続。
- 更新前: versionName 1.0.95 / versionCode 1095。
- 更新後: versionName 1.0.96 / versionCode 1096、埋め込みGit SHA 2e936cf。
- コミット後の `assembleDebug -PversionCode=1096 -PversionName=1.0.96` 成功。
- APK: `app/build/outputs/apk/debug/app-debug.apk`。
- APK SHA-256: `ee6defbe54097016401ae6b16177581a5d5e0e96d8f075d13131d5b226b5c4a4`。
- 更新前後の署名SHA-256一致: `8561de18c9143b61a26d970ec28c30a20f4260ffba6879eab8250badc3f3524b`。
- `adb install -r` 成功。アンインストール・データ消去なし。
- 更新後のPackage Manager表示で1096 / 1.0.96を確認。
- 既存ログイン・保存済みDefault接続先・defaultプロフィールを保持し、会話画面が起動。
- 接続画面の「接続をテスト」で `Connected successfully` を確認。
- 端末の設定やGoogleサービス・アカウント境界は変更していない。
- 更新前後のAPK内のGoogle OAuth client ID集合も一致（1件）。

実機証跡はローカル `E:/OSHIIRE/software/Hermes/work/client-surface-device/`。
更新前のAPKを `installed-base.apk` に保存した。レイアウトJSONなどの個人データを含み得る証跡はGitへ追加しない。

## 本番（Slack経由）

[依頼・検証スレッド](https://chatgpt-lll7864.slack.com/archives/C0C74STS7D4/p1794589888878799)

- Hermesへの依頼は指定workspaceの `#hermes-bro` のみを使用。
- 固定本体パッチとplugin 0.6.0を渡し、既存変更・設定・セッションを保持した必要差分のみの適用を依頼。
- 事前報告: 本番HEADはローカルの上流基準と異なる。既存pluginは0.5.2。
- 本番基準: `5b147ab036d6d19131bff8d20701b5001eab844a`、main、core 0.22.2。
- `plugins.py` と生成済みTypeScriptが衝突し、本番には `plugins_context.py` が無いため、隔離コピーで限定移植を実施中。
- 既存の `hermes_cli/web_routers/mcp.py` の4行変更と未追跡ファイルを保持。
- 既存変更・plugin・設定・3 profileのDBを `/var/lib/hermes/work/client-surface-deploy-20261114/backup-preflight` に退避したとの報告。DBはSQLite backup APIを用い、各quick_checkがok。
- default gateway、Dashboard、career-mypage gateway、career-coach plugin hostが共有coreを利用していると報告。
- `gateway.managed_workers` は確認対象でfalse。本番で別workerを使っているとは扱わず、復元テストと区別する。
- 本番の差分適用・安全な再起動・mobile-v3広告・保存本文とモデル注記の分離は確認待ち。
- 02:28 JST、Hermesの隔離テスト用 `execute_code` が実行ガードによる一回限りの承認待ちになった。[承認メッセージ](https://chatgpt-lll7864.slack.com/archives/C0C74STS7D4/p1794590935631179)。本番変更前で停止しており、Codexから代理承認はしていない。

## 残る検証

1. 本番での適用差分、保持した既存変更、再起動後の稼働状態の報告。
2. defaultプロフィールの実際の受付からmobile-v3が広告されること。
3. 更新済み実機から固有マーカー付きの通常メッセージを送り、応答とネイティブ部品描画を確認。
4. 同じ実機送信について、保存ユーザー本文に旧表示指示が混ざらず、モデルへsurface注記が渡ることを本番側で突合。

ローカル実装テストの詳細は [実装記録](client-surface-implementation-20261114.md) を参照。
このデプロイ記録はその後の状態を記録する。

# Client surface 実装記録（2026-11-14）

ローカル実装・検証済み。本番サーバー、実機、GitHubへの反映は未実施。
元の `client-surface-core-patch-20261114.md` は設計時点の記録として変更していない。

## 作業場所と基準

| 対象 | 場所・基準 |
|---|---|
| 本体 | `E:/OSHIIRE/software/Hermes/work/client-surface-core` |
| 本体の上流基準 | `NousResearch/hermes-agent` の `547739e46692b2db7b4c9cdd57e8f795573846a4` |
| 本体ブランチ | `feat/plugin-client-surfaces` |
| 本体コミット | `59a3c3f90a23b946474ce1d5fafb24dc8021a818`（1機能・1コミット） |
| アプリ・プラグイン | `E:/OSHIIRE/software/Hermes/hermes-mobile-cloud` |
| アプリ基準 | `2e54e1176aec2fc14ef458ef3f6a183cf8f00eea` |
| アプリ作業ブランチ | `feat/client-surface-negotiation`、変更は作業ツリーに保持 |

本体は上流の公開コードを確認してから独立したローカルクローンで実装した。
同等の登録APIは確認時の上流には存在しなかった。
上流の `main` は変更せず、GitHub上のフォーク作成・push・PR公開は行っていない。
別の `hermes-mobile` 作業ツリーには既存の変更が多数あり、そちらは編集していない。

本体パッチ: [0001-register-per-turn-client-surfaces.patch](patches/0001-register-per-turn-client-surfaces.patch)

## 実装内容

### 本体

- `register_surface` / `ctx.register_client_surface(name, note)`。名前検査、組み込み名・重複の拒否。
- 登録は既存のプロファイル別 `PluginManager` に保持。別プロファイルへ広告・注記を漏らさない。
- 既存の所有権台帳で解除。文字列または同期コールバックを受け、6000文字に制限し、例外時は注記だけを省略。
- 旧 `prompt.submit` はリクエスト時に登録を照会。未登録は従来どおり空にし、canonical admission は拒否する。
- `gateway.capabilities.client_surfaces` は組み込みと登録済みのソート済み一覧。
  プロファイル別広告のため `profile` と既存のスコープ処理を適用。
- 受付・注記生成・ワーカー復元からの照会時に、そのプロファイルのプラグインを冪等に読み込む。
- RPC契約と生成済みTypeScript/OpenRPCを更新。
- `voice_context` の制約、組み込みsurfaceの文面、保存ユーザー本文、固定システムプロンプトを維持。

### mobile_display 0.6.0

- 新APIがあれば `mobile-v1` / `mobile-v2` / `mobile-v3` を登録。
- サーバー同梱カタログとAndroidの記述の一致を検査。注記全体の長さはそれぞれ2674 / 3704 / 4794文字。
- v2はAndroid内に異なる時期の記述が2つあるため、共通して対応できる古い方を採用。
  現在のアプリはv2へダウングレード交渉せず、v3が無ければ本文付加へ戻る。
- 旧アプリの本文検出を維持。新API対応ホストでは、印のないtui/cliターンへの「表示能力未確認」注記を抑制。
- preflightはAPI有無と `surface` / `legacy_suffix` モードを報告。古いホストも互換モードで利用可能。

### Android

- `TerminalCapability` に広告を同居させ、既存のscope / 認証epoch / socket検査と破棄処理を共有。
- `RichAnswerSubmissionTransport` だけが本文付加かsurfaceかを選択。
- `mobile-v3` 広告ありなら本文をそのまま送り、`surface` を追加。広告なし・v2のみなら従来方式。
- 空白・スラッシュコマンドにはどちらも追加しない。過去履歴の除去処理は維持。
- 送信直前にも広告を確認し、接続変更の競合時は未送信として返す。本文を失った状態で再送しない。
- `client_surfaces` だけの広告で `terminal_acceptance` を有効にしない。

## 設計資料からの調整

1. 最新上流では `PluginContext` は `hermes_cli/plugins_context.py` に移動済み。
   実装とpreflightをその構成に合わせた。
2. `DashboardRpcTransport` の通常送信は `submission_id` なしの旧経路と確認。
3. `session.redirect` は旧・canonicalともsurfaceパラメータを持たないため、割り込み送信は従来の本文付加を継続。
   通常の `prompt.submit` のsurface対応とは区別している。
4. 複数プロファイルを同じプロセスで処理する上流構成に合わせ、登録・広告・検査をプロファイル単位にした。

## 検証結果

| 検証 | 結果 |
|---|---|
| 本体の関連テスト7ファイル | 32件成功（下記デーモンテスト1件を除外） |
| 新しい本体テスト | 登録検査、例外、上限、解除・reload、プロファイル分離、legacy/canonical、別プロセス復元、隔離プラグインホスト、モデル入力とDB本文の分離 |
| 本体 `scripts/check --staged` | 18項目成功、code health blocking/advisoryとも0 |
| mobile_display unittest | 実際の本体API・dispatcherを含む30件成功、skipなし |
| Android関連テスト | 計148件成功（WsClient 109、既存履歴処理18、端末受理とsurface交渉17、表示送信adapter 4） |
| `assembleDebug` | 成功 |
| `compileReleaseKotlin` | 成功 |
| 変更したproduction Kotlinと送信adapterのktlint | 成功 |
| 変更ファイル全体のktlint | 既存テスト3ファイルの22指摘が残る。基準コミットの同ファイルでも同じ20行長・2未使用importを再現 |
| `git diff --check` | 本体は成功。アプリは既存CRLFを保持し `-c core.whitespace=cr-at-eol` で成功 |

上流の既存 `test_real_daemon_accepts_hud_and_voice_live_submits_and_notes_the_model` は
Windows上でテスト側のlive-system guardが `gateway.run` 子プロセス起動を拒否した。
ガードやテストを弱めず、最終の関連テスト実行ではこの1件を除外した。
別プロセスによるsurface復元テスト、および実際のAIAgent・DBを使うモデル入力分離テストは成功している。

本体テストは `scripts/run_tests.sh` 経由、依存環境は本体のPMで作成した `.venv`。
外部モデルへの呼び出しや実際のツール操作を検証の代わりに行っていない。

## ローカルAPK

- `app/build/outputs/apk/debug/app-debug.apk`（51,798,295 bytes）
- SHA-256: `a2a6923e5544ce04e530a6e618a28a45edbd1af0947dc1456c555356791d7daf`
- この作業ツリーからの開発ビルド。埋め込みGit SHAはアプリ基準の `2e54e11`。
- 実機へインストールしていない。

## 本番適用前に残る確認

- 本番 `/var/lib/hermes/app` のHEAD、既存の手パッチ、実行プロファイルとプラグイン構成の照合。
  本番には接続していないため、ローカル上流基準と同一とは断定できない。
- GitHubフォークの用意と、この本体コミットを運用ブランチへ適用する判断。
- 本体 → プラグイン → アプリの順で適用し、実際の接続から `mobile-v3` 広告を確認。
- 実機で送信後、Desktop/TUIの保存履歴に本文埋め込みがなく、回答がネイティブ部品で描画されること。

push・PR公開・本番/実機反映は未実施。Hermesへの依頼が必要な場合は、既定のSlack `#hermes-bro` を使用する。

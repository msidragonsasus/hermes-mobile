# 会話中のサイトログイン入力 — 2026-10-20

## 原因

Hermes のサイト用ログイン登録は、通常のチャット送信や `secret.request` イベントではない。
`client.capabilities {server_requests: true}` を送ったクライアントへ、
`{id, method: "vault.save_login", params: {session_id, origin, site}}` という
サーバー発の JSON-RPC 要求が届く。入力は同じ `id` の
`{result: {value: "JSON文字列"}}` として返す。

修正前の Mobile には、次の不足があった。

- 入力要求への対応通知を送っていなかった。
- EventParser が `id` の存在だけで RPC 結果と判定し、サーバー発の要求を区別していなかった。
- 会話画面に `vault.save_login` 用の入力画面と応答経路がなかった。

公開されている Hermes 本体では、未対応クライアントへの要求を待たずに終了する。
`save_login` コールバックの空の戻り値は `save_declined` になるため、ユーザーが画面で
辞退していなくても「保存を辞退した」という説明になる。今回の報告と一致する経路である。
報告された過去の会話内容・実際のパスワードは調査のために取得していない。

実装前に公開機能と既存コードを確認し、独自の保管方式を追加せず、Hermes 本体の
既存要求・暗号化保管・ブラウザー入力機能を利用した。

参照した一次資料:

- [Hermes のサーバー発要求](https://github.com/NousResearch/hermes-agent/blob/main/tui_gateway/server_requests.py)
- [入力コールバック](https://github.com/NousResearch/hermes-agent/blob/main/tui_gateway/agent_callbacks.py)
- [ブラウザー用保管ツール](https://github.com/NousResearch/hermes-agent/blob/main/tools/browser_vault_tool.py)
- [Desktop の入力画面](https://github.com/NousResearch/hermes-agent/blob/main/apps/desktop/src/components/prompt-overlays.tsx)

## 修正

- 接続時に対応通知を送り、RPC 結果より先にサーバー発要求を識別する。
- 会話中に対象サイト・ログインID・パスワード・「保存して続ける」を表示する。
- サイト表示は URL の origin を使い、エージェントの自由なラベルやクエリを表示に使わない。
- 設定の「パスワードとログイン」と同じ丸い Material フィールドを共用する。
- Username / Password の Android 自動入力セマンティクス、伏せ字、SecureOn を使用する。
- 入力値は保存可能な UI 状態、端末 DB、送信待ちキューに入れない。
- 応答は元の接続・プロファイル・要求に限定し、回答済み要求の重複と古い画面からの応答を拒否する。
- 明示的な「今回は入力しない」だけが空の応答を返す。画面再生成や接続変更では辞退を送らない。
- サーバーのキャンセルを反映し、再開・イベント再取得では未回答の要求のメタデータだけを復元する。
- 同じ仕組みの確認コード・外部パスワード管理の解除要求も表示する。

Mobile は入力値をチャットメッセージとして送らない。Hermes 本体ではログインIDは
識別用メタデータとしてエージェントに渡される。パスワードは専用経路で保管・入力される。
サーバーの実装・サービス・認証設定は変更していない。

## 検証

- 最終関連単体テスト: 83 / 83（WebSocket 全73件、要求解析2件、会話状態2件、保管リポジトリ6件）。
- その前の広い実行: 306件中305件成功。残る既存 WebSocket テストのタイムアウトは、
  先行する読み取りテストが未回答 RPC を残すためだった。終了処理でそれらを破棄するよう修正し、
  WebSocket 全73件の再実行は成功した。既存の判定条件・5秒の期限は変更していない。
- 変更した Kotlin 16ファイルの ktlint: 成功。全プロジェクトの既存 ktlint 問題の修正は対象外。
- 日本語・英語の XML: 解析成功。
- assembleDebug / assembleDebugAndroidTest / compileReleaseKotlin / checkColorLiterals / lintDebug: 成功。
- 実機 SC-51F（Android 16）: 11 / 11 成功。新規入力画面6件、既存保管画面4件、実サーバー互換性1件。
- ライト・ダーク・文字拡大・IME表示、伏せ字、送信前検証、明示的な辞退、状態復元、送信失敗を確認。
- キャプチャの初回2件は Compose の別ウィンドウ取得でタイムアウトした。アプリの SecureOn は維持し、
  テスト自身の View を描画して画像を取得する方法へ修正した。実機11件の再実行はすべて成功。
- 取得したライト・ダーク画像を目視確認した。対象サイト、丸い共通フィールド、読める操作ボタンを確認。
- 実サーバーへの接続で `client.capabilities` が成功し、対応要求一覧に `vault.save_login` があることを確認。
  既存の保存済み認証で実行し、モデル呼び出しや保管情報の追加は行っていない。

## 実機更新と保存データ

- `adb install -r` で 43 → **44 / 1.0.44-local** へ更新成功。アンインストール・データ消去なし。
- 新旧署名、権限、ネイティブライブラリ、既存ビルドの接続設定の一致を照合した。
- `firstInstallTime` は更新前後とも **2026-09-06 23:04:00**、データ領域も同じ。
- 実機テストで既存の暗号化ストレージが Ready となり、保存済み認証でサーバー接続が成功した。
- 認証設定ファイルのハッシュは、早い時点の記録と更新直後で異なった。
  更新直後に観測したファイル更新時刻は18:08:47で、APK更新時刻18:09:30より前だった。
  このため、その2つのハッシュからインストール前後の完全同一性は主張しない。
  端末上の全レコードの内容比較は行っていない。
- 更新後の MainActivity 起動・前面表示を確認した。

配布APK: `E:/OSHIIRE/software/Hermes/deliverables/Hermes-1.0.44-login-prompts-20261020.apk`

SHA-256: `299a64c9fcf702f3d6afe62683c8f13ba0ab3a1b9f5b5a40787472a9dcc2bac0`

検証証跡は `.audit/login-prompt-20261020/`。
実サイトのログイン、実際の認証情報の登録、モデルによるブラウザー操作は検証に使わない。
更新前に終了した要求は、会話から新しく依頼し直す必要がある。

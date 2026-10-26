# 保護ファイル編集のモバイル承認（2026-10-16）

## 調査結果

Android は既に `approval.request`、`approval.received`、`approval.pending`、
`approval.respond` に対応している。SOUL.md / AGENTS.md 専用の許可設定を追加する必要はない。
Chat の承認メッセージ、またはホームの承認待ちから詳細を開き、サーバーが提示した操作・説明・
対象を確認して「一度だけ許可」または「拒否」を選ぶ。サーバー、接続プロファイル、
サーバープロファイル、ソケット世代、runtime session、request ID は元の要求に固定する。

参照した Hermes Agent はローカルの監査用 checkout、commit
`9dd6634c5635321cf38840cc30e9b51226689128`。**稼働サーバーの版ではない。**
`tools/file_tools_write_guards.py` の保護ファイルガードは、セッションの通知 callback があれば
`tools/approval_gateway_wait.py` のキューへ要求を入れる。
`tui_gateway/server.py` の `_wire_session_agent` が callback を登録し、
`_emit_approval_request` が Android と共通の `approval.request` を送る。
`tui_gateway/methods_prompt.py` が pending/received/respond を処理する。
この経路は Slack 専用ではなく、参照コードではモバイルでも利用できる。

今回確認した Android の問題:

- アプリ全体の Attention 正規化は live の `approval.request` だけを処理し、
  `session.info.pending_approval` は Chat ViewModel だけが復元していた。
- 接続世代の変更やイベント欠落で未回答の要求も UNKNOWN になる。その後サーバーが要求を
  再提示しても、回答を試行して結果不明の要求と区別できず、回答不能を引き継いでいた。
- Chat の回答メソッドは once/deny を制限するが、要求が明示的に提示していない選択肢は
  チェックしていなかった。詳細画面・共通 coordinator 側には既にこのチェックがある。

これらはコードと回帰テストで確認した不具合であり、ユーザーが報告した停止の直接原因を
特定したものではない。稼働サーバーの callback 登録、対象 session、実際の要求の発行有無、
インストール済み APK は照合していない。

## 修正と安全性

- live / session.info / approval.pending のデコードを共通化し、アプリ全体でも
  session.info に含まれる承認を復元する。欠落した session/request ID を推測しない。
- 回答試行を別に記録し、未回答の UNKNOWN はサーバーから要求が再提示された後だけ復元する。
  復元自体は回答を送らない。元の説明を保持する。
- 一度送信を試みた要求は、結果不明、切断、重複イベント、別ソケット世代での再提示を経ても
  自動再送しない。確実に送信前に拒否された NotSent だけは、本人の新しいタップで再試行できる。
- Chat でも要求が提示していない選択肢を拒否する。壊れた choices の値から許可を生成しない。
  UI と transport の once/deny 制限、`all=false`、`resolved=1` の ACK 確認を維持する。
- サーバー側の期限切れはキューから要求を除去し、古い ID への回答は `resolved=0` となる。
  Android はこれを成功とせず、再送もしない。既知の期限を過ぎた要求も送信しない。

保護機能・自動承認設定・認証・権限・署名・applicationId・DB は変更していない。
実際の保護ファイル編集、エージェントへの依頼、本番サーバーの更新・再起動、端末への導入は行わない。

## サーバーで残る確認

1. 稼働版に上記保護ファイルガードと TUI/Dashboard の通知 callback 登録があること。
   Slack の成功は、Android が使う別 runtime session の callback 登録までは証明しない。
2. 編集ツールがそのセッションで要求を発行し、request ID 付きイベントが Android に届くこと。
   エージェントが会話文で「承認できない」と返しただけでは承認カードは生成できない。
3. 許可・拒否が同じ pending request のみに届き、期限切れ・解決済み ID は 0 件になること。
   未対応の場合はこの橋渡しを稼働版に合わせて修正する。ガード無効化や自動承認で代替しない。

参照版の保護要求は操作と対象ファイル名を command/description に含めるが、構造化された
絶対パス・編集 diff・期限時刻は提供しない。未提供の情報はアプリでも未提供と表示する。
詳細な diff や期限の事前表示にはサーバーの追加対応が必要。hard block された config.yaml や
別の memory/skills 保留ストアを、この承認で編集できるとは扱わない。

## ローカル検証

Android の回帰テストは、合成の SOUL.md / AGENTS.md 要求を live/replay/snapshot 経由で受信し、
元の説明・操作・request ID と once/deny を保持すること、Chat 外の復元、重複タップ、
未回答の復元、送信結果不明の再送禁止、未提示・不正な選択肢の拒否を検証する。
既存の coordinator/repository/transport の期限切れ、別 scope、ACK の試験も実行する。

`server/approval/tests/test_gateway_contract.py` は参照版の実際のガード・キュー・resolver を
読み込み、一度許可、拒否、期限切れ、古い ID、重複回答、通知失敗、対話経路の欠落を検証する。
人間待ちの時計・hook と CLI callback はテストで置換し、LLM・外部通信・ファイル編集は呼ばない。
任意の別版へ成功を一般化しない。

```powershell
python -m unittest discover -s server/approval/tests -v
# 別のローカル参照版を使う場合だけ指定:
# $env:HERMES_AGENT_SOURCE = 'C:\path\to\hermes-agent'
```

実行結果・ビルドログ・作業開始時点からの差分は
`../output/protected-approval-20261016/` に保存する。
APK は既存の Apps 改修を含む作業ツリー全体からの debug ビルドであり、今回だけのパッチではない。
実サーバーとの認証済み往復、実機・エミュレーターの UI 操作、更新インストールは未検証。

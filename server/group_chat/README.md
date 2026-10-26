# グループ会話のサーバー実行

Androidの会議進行ループを置き換えるDashboardプラグイン。`group_chat`ディレクトリ全体を、既存Dashboardのプラグイン配置方式で導入する。本番への配置・有効化はこの変更には含めていない。

- 認証済みDashboardセッションの下で `/api/plugins/group_chat` を提供する。独立した公開HTTPサーバーは起動しない。既存Career APIと同じ親セッション検証を使う。
- 会話ID・グループID・送信IDを分離し、各管理プロファイルの `group_chat/history.db` に受付と発言を保存する。受付後はHTTPリクエストやAndroidの寿命に依存しないワーカースレッドが議論を進行する。
- 各参加者の実行には既存Hermesの `hermes --profile <member> chat -Q -q <prompt>` を使う。追加のモデルAPIクライアント・APIキー・Relay経路は作らない。ホストのCLIと各プロファイルの利用可能性が必要。実ホストのCLI互換性、モデル・推論設定、承認付きツールの動作は導入前に確認が必要。
- 再送には同じ送信IDを使う。受付内容が異なるID再利用は拒否。SQLiteの単一進行制約とOSファイルロックで重複ワーカーを防ぐ。複数Dashboardプロセス構成では所有プロセス以外は503になるため、プラグイン要求を単一プロセスへルーティングする。
- GETは保存済み会話と進行状況を返す。明示的な停止APIだけが現在の議論を停止する。アプリの画面離脱・切断は停止要求を送らない。
- サーバー自体の再起動中に実行していた発言は `interrupted` とする。保存済みの履歴を保持し、外部効果を伴う可能性のある発言を自動再実行しない。未実行の受付済みキューは再開する。
- 既存端末の会話は最初の送信時に履歴を移行する。既にサーバーが持つ会話はサーバーを正とし、ローカル履歴で上書きしない。旧方式で配信未確認の会話は自動再送しない。
- 議論の巡回、発言順、メンションによる優先、続行回数・発言数の上限、最後のまとめをサーバーで制御する。応答本文は発言完了ごとに保存・表示し、現在の発言者を表示する。トークンごとの配信とCLI内部のツールイベント配信は未対応。
- 対応プラグインのないホストで端末制御へ自動フォールバックしない。Androidは更新が必要と表示し、保存済みの会話を保持する。

## ローカル検証

グループの会話タイトルは、Androidが既存のOpenClaw用の無料モデル限定の自動命名処理を再利用して生成する。既存の認証済み設定からOpenRouterキーを取得できる場合だけ、最初のユーザー投稿から命名し、端末の暗号化アーカイブへ保存する。キー未設定・生成失敗時は最初の投稿の仮タイトルを保ち、有料モデルへ切り替えない。以前の会話も開いたときに命名対象となり、接続先が変わった後の生成結果は破棄する。

`PATCH /api/plugins/group_chat/conversations/{id}` は `{groupName, title, expectedTitle}` でタイトルだけを更新する。既存の認証・Origin・profile境界を使い、現在の名前が`expectedTitle`と異なる場合は409で拒否する。投稿、議論の進行、履歴順序は変えない。未対応サーバーでも端末側の生成済みタイトルは再読込で仮タイトルに戻さない。

`python -m unittest discover -s server/group_chat/tests -v`

FastAPIの認証・Origin・管理プロファイル分離、画面への応答後の継続、受付の永続性、再送、停止、再起動、上限を合成Runtimeで検証する。テストでは実Botやモデルを呼び出さない。

実装前に [Zulipのサーバー・イベントキュー構成](https://github.com/zulip/zulip/blob/main/docs/overview/architecture-overview.md)、既存HermesのCLI、既存グループ部品を確認した。外部チャット基盤の追加やコードのコピーは行っていない。

## メインからの議論依頼（0.2.0）

同じプラグインの `register(ctx)` が、メインのHermes会話へ次のツールを提供する。

| ツール | 動作 |
| --- | --- |
| `group_list` | 保存済みグループのID・名前・参加者と実行可否を確認 |
| `group_delegate` | 一覧の既存groupId・目的・完了条件・必要な背景を渡して議論を開始 |
| `group_task_status` | 巡回数、実行状態、現在の発言者、結果・未解決事項を読む。通知も再接続 |
| `group_task_stop` | 依頼を明示停止。追加巡回・評価結果によって停止を取り消さない |
| `group_tasks` | この会話から依頼したタスクの一覧 |

メインは先に `group_list` で既存グループを確認する。例えば一覧に `name:開発会議` がある場合、
`group_delegate` に渡す引数は次の形になる。

```json
{
  "taskId": "design-comparison-1",
  "groupId": "name:開発会議",
  "goal": "候補方式を比較し、採用案を提案する",
  "completionCriteria": "費用・運用負荷・反対意見への回答を根拠とともに示す",
  "context": "既存サービスを維持する。外部APIの追加契約はしない。",
  "maxCycles": 3,
  "budgetSeconds": 1800
}
```

参加者は既存の保存済みグループからホストが解決する。モデルが全botを列挙して参加させたり、
任意の名前で新しいグループを作ったりすることはできない。
会話全体を自動転送せず、依頼元が議論に必要な背景を `context` に選んで渡す。

通常の発言巡回・まとめが終わると、依頼元と同じプロファイルの**独立した評価セッション**が
保存済み議論と完了条件を読む。元の会話を並行実行したり、その履歴に直接書き込んだりしない。
評価は `group_review` という専用の空のtoolsetで実行する。この名前にツールを登録しないこと。
評価結果のJSONに従い、完了・追加議論・本人の判断待ちを分ける。追加議論は不足する論点への
具体的指示を渡して行う。既定3巡、最大5巡。各巡の発言上限は既定6（参加者全員とまとめの
分だけ最低枠を確保）、最大50。時間予算は受付から数えて既定30分、最大2時間。
上限到達時は未解決点を含めて `capped` とし、目的達成を意味する `complete` と区別する。
進行役の判定・未解決点・追加指示も同じグループ会話に保存する。グループ画面向けsnapshotは
評価中も実行中を維持するため、画面の更新が途中で止まらない。グループ画面の停止操作も
依頼全体を止める。依頼の進行中に別の手動送信で巡回を置き換えることは拒否し、完了後は
通常のグループ会話として続けられる。

依頼は既存のプロファイル別 `group_chat/history.db` に追加テーブルとして保存する。
エージェント用ツールはローカルのメールボックスへの受付だけを行い、Dashboardの単一ワーカーが
モデルを呼ぶ。受付IDは依頼元セッションと `taskId` の組で固定し、同じ内容の再送は重複実行しない。
異なる内容で同じIDを使うと `request_conflict` になる。依頼元・管理プロファイル・返却先は
ホストの実行コンテキストから確定し、モデルの引数で変更できない。

返却には既存Hermesの `tools.async_delegation.dispatch_async_delegation_batch` と永続配信台帳を
再利用する。待機処理はモデルを呼ばず、結果を元の会話へ通知し、メインに報告を再開させる。
メインはその間も通常の会話を続けられる。ネイティブのバックグラウンド配信が使えないセッション、
または稼働中のDashboardワーカーを確認できない場合は受付を拒否する。
通知の受付が確定するまで議論は開始しない。独自HTTP・Relay経路や追加APIキーは使用しない。

DashboardとメインのHermesプロセスが同じプロファイル領域を共有し、両方でこの版のプラグインを
読み込んでいる必要がある。既存プロファイルのワーカーは起動時に復旧し、新規領域は5秒周期で
検出する。初回の有効化直後はワーカーの検出まで `group_worker_unavailable` になる場合がある。
発言・評価の実行途中にサーバーが落ちた場合は `interrupted` として返し、自動で再実行しない。
モデル呼出前の受付済みキューは再開する。メイン側の通知待機が中断しても、タスク・結果は残り、
次の会話ターンのhookまたは `group_task_status` で再接続する。明示停止は再開しない。

CLIへ渡す発言・評価プロンプトはUTF-8の匿名一時ファイルを標準入力に接続し、
既存の `chat -Q --query-file -` で読み込む。長い日本語履歴を単一のコマンド引数へ
渡したときのOS制限を避け、本文の切り捨ては行わない。CLIの
[公開入力契約](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/reference/cli-commands.md)
を再利用する。起動後に親のファイルハンドルを閉じても子の入力は維持され、起動失敗時にも
親側の一時ファイルは閉じる。既存の文字数・時間上限、停止と再実行の規則は維持する。

公開実装として [Hermesのプラグイン契約](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/developer-guide/plugins/index.md)、
[非同期委譲の実装](https://github.com/NousResearch/hermes-agent/blob/main/tools/async_delegation.py)、
[Magentic-Oneの進捗評価方式](https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/magentic-one.html#architecture)
を確認し、既存の登録・配信・グループ実行機構を拡張した。外部フレームワークは追加していない。

ローカルテストは合成Runtimeで追加議論、完了条件の評価、通知先、重複防止、停止の競合、
時間上限、再起動を検証する。実モデルによる議論の質・実ホストのCLI/通知互換性・本番導入は別の検証。
`tools/install_server.py` と `tools/update_runtime.py` は旧0.1.0の固定済み配布用なので、
0.2.0の更新手段として流用しない。

## 本番反映・実動確認（2026-10-19）

Slack `#hermes-bro` の依頼 `GROUP-SUPERVISION-20261019-01` で本番反映した。
バックアップはホスト内の `/var/lib/hermes/.hermes/backups/GROUP-SUPERVISION-20261019-01`。
既存グループの会話2件・発言12件・run 2件、メインの69セッション・3,647メッセージについて、
既存行の欠落・本文変更がないことを本番側で照合した。対象外18,562ファイルのハッシュも保持。

通常のSlackメイン会話から `idea1`・`idea2` へ実際に依頼し、再試行ID
`GROUP-SUPERVISION-20261019-01-R1` が2巡で完了した。初回評価の `continue` と具体的な
`followUp`、2巡目の比較・反論対応、最終評価 `complete` / `openQuestions: []` を確認。
メインの通常ターン終了後、状態ポーリングなしで同じ元会話をネイティブ通知が再開し、
結果を返した。配信台帳は `delivered`・試行1回。両巡の評価中snapshotは `running` を維持した。

実ホストのCLIは `-Q` でも先頭へ `Warning: Unknown toolsets: group_review` を出したため、
評価時だけこの完全一致の既知の接頭辞を除去する互換性修正を加えた。未知の出力や参加者本文は
除去せず、JSON検証も維持する。初回の失敗記録は保存した。ワーカー停止時の復旧hookが
障害通知を繰り返さない対策も含め、本番側50件・ローカル54件の関連テストが通過した。
本番の修正済みruntime 2ファイルとCLI回帰テストはローカルのSHA256とも一致している。

確認経路はSlackのメイン会話で、Android実画面の操作試験ではない。対応ブラウザ不足により
認証付きDashboard HTTP読取は未確認。認証の変更・迂回は実施していない。
証跡はホスト内の `validation-evidence.jsonl`、`actual-review-sessions.json`、
`history-preservation.json`、`DEPLOYED-SHA256.txt` と
[Slackの結果報告](https://chatgpt-lll7864.slack.com/archives/C0C74STS7D4/p1792414424965469) にある。


### 長文での参加者起動失敗の修正（同日22:44確認）

`GROUP-MEMBER-FAILED-20261019-01` で、2巡目のまとめ入力がUTF-8で135,005 bytesに
なり、単一argvの上限でCLIを起動できなかった障害を調査した。当時のerrnoは保存されて
いないが、次のCLIセッション不在、復元した入力長、同ホストの無害な同長引数による
`E2BIG`再現が一致した。上述のstdin入力へ修正し、本番のDashboardのみ再起動した。
再バックアップは `/var/lib/hermes/.hermes/backups/GROUP-MEMBER-FAILED-20261019-01`。

ローカル・本番とも関連57件が成功。モデルを呼ばない実子プロセスで360,000 bytes、
実CLI入力処理で170,000 bytesのUTF-8入力をハッシュ一致まで確認した。別の通常メイン依頼
`GROUP-MEMBER-FAILED-20261019-01-VERIFY` は22:40:49〜22:43:02 JSTに2巡で完了し、
実評価の `continue`・具体的な追加議論・`complete` を経て元メイン会話へネイティブ返却した。
状態ポーリングなしで再開し、配信は `delivered`・試行1回。既存行と元seminar依頼の内容は
保持し、失敗した元依頼は再実行していない。長文合成入力もモデルには投入していない。

証跡は上記バックアップの `native-stdin-proof.json`、`preservation-and-ack.json`、
`validation-evidence.jsonl` と
[本番の結果報告](https://chatgpt-lll7864.slack.com/archives/C0C74STS7D4/p1792417483464589)。
認証付きDashboard HTTP読取とAndroid実画面は今回も未検証。


## 既存グループへの依頼

`group_list` はBot一覧画面と同じdefaultプロファイルの
`profile.yaml` → `ui_meta.hermes-bots-groups.rooms` と各botの
`ui_meta.hermes-bots.groups/group` を読む。会話ログ・本文・認証値は返さず、保存済みの
グループID、名前、参加者、実行可否を返す。新しいグループは作らない。

メインは一覧から得た `groupId`（推奨）、または一意な既存名を `group_delegate` に渡す。
参加者・発言順・まとめ役・systemPromptは保存済み設定から確定する。自由なmembers指定で
グループを組み直さない。未知名、同名の曖昧さ、別ホストの参加者、欠けたプロファイルは
エラーで停止する。削除済みグループも復活させない。旧クライアントがroomへ
`members: [{name: ""}]` と保存した場合だけ、同じグループ名への明示的profile所属から
復元する。正常なroom.membersは旧所属タグより優先し、インストール済み全botへの
フォールバックは行わない。

新しい依頼の会話は既存groupNameの配下へ作成され、保存済みのグループ定義自体は変更しない。
個別の依頼・追加議論・完了判定・元メインへの通知は既存の永続委譲処理を使う。
公開のBot Mode仕様と実ホスト・モバイルのメタデータ契約を確認して実装しており、
Desktopだけのgroups操作をHTTP/Relayで迂回して呼び出すことはしない。


### 本番の既存グループ選択（2026-10-20）

本番はメイン側の `group_agent_tools.py`・`group_catalog.py`・`plugin.yaml` の3ファイルを
反映し、Gatewayだけを既存のdrain/reload経路で読み直した。Dashboard・worker・supervisionは
変更していない。現行ワーカーとの隔離互換テスト7件、全体候補のホストテスト73件が通過。
ローカル候補はサーバー85件、AndroidのGroupChatViewModel 75件が通過した。

通常メインが実際の `group_list` から `name:boss, idea1, idea2, deny` を選び、
`groupId`だけで依頼した `GROUP-EXISTING-20261020-01-VERIFY` は、保存済みの
boss/deny/idea1/idea2だけで1巡・completeとなった。元メインの受付ターン終了後、
状態ポーリングなしでnative通知が同じ会話を再開し、delivered・配信試行1回を確認した。
profile定義7件のハッシュと、既存10会話・92発言・18run・8委譲の全列を保持した。

旧storeではgroupIdのDB保持、任意の保存まとめ役・systemPromptのworker転送は未反映。
今回の既存グループは旧ワーカーの既定動作と設定が一致している。
上の説明はローカルの全体候補の契約も含むため、この本番反映範囲と区別すること。
Android側の未知グループで全botを座らせるfallbackはローカルで削除・テストしたが、端末更新と
実画面確認は行っていない。元のゼミ議論は再実行せず、既存グループ定義も書き換えていない。

証跡は `/var/lib/hermes/.hermes/backups/GROUP-EXISTING-20261020-01/verify-result-readback.json`
と[本番検証報告](https://chatgpt-lll7864.slack.com/archives/C0C74STS7D4/p1792473385007579)。

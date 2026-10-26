# 今日やること

ホームを「今日何をすべきか」を確認するリストにする追加プラグイン。
利用中のHermesプロファイルのLLMがメール・manabaを調べ、今日やるべきことを判断する。
検索条件・読むメール・過去の資料との照合はLLMが決める。固定キーワード分類は置かない。
別のモデル・APIキー・有料収集サービスは追加しない。要約には既存プロファイルの
モデル利用枠を使う。現在の候補はGmailとmanabaが対象で、カレンダー等は未対応。

## 表示・保存

- 行動、今日する理由、期限、情報源へのリンクを表示する。
- 今日必須／今日準備／要確認の順に並べる。期限を過ぎた未完了項目も残す。
- 完了・未完了はこの一覧だけの記録。メールの既読、課題提出、外部操作とは独立。
- 取得したメール本文・manaba一覧はプロファイル専用SQLiteにキャッシュし、次回のLLMが再利用する。
  Androidの既存SQLCipher DBには一覧の要約・参照情報だけを保存する。
  既存Hermesの日次セッションにも入力・ツール結果が残る。
- 取得に失敗した情報源の前回項目は保持し、古い情報として表示する。
  部分取得時も今回確認できなかった前回項目を消さない。
- 新着検索の成功やLLM出力からの省略だけでは前回の未完了項目を消さない。
  LLMが今回取得した根拠を付けて `resolved` に明示した項目だけを一覧から外し、解決履歴を残す。
- 資料ごとの取得runと時刻を保存する。キャッシュを読み直しただけでは鮮度を更新しない。
- 日付は日本時間。前日の一覧は今日の確認済み情報として扱わない。
- 接続先・プロファイルと選択アカウントを分離する。アカウント変更時はその情報源の
  ホーム項目を消し、別アカウントの情報を引き継がない。

## 収集

初期状態は無効。Mobileホームの「確認する情報源」で登録済みメールアドレスと
manabaの `/ct/home` URLを指定して有効にする。ホームを開いた日に1回確認し、
「今の情報で調べる」から明示的に更新できる。バックグラウンドの朝定時実行は含まない。
同一更新IDと同日の自動確認を重複実行しない。途中でホストが停止した処理は
自動再送せず、15分後に中断として表示する。画面を閉じても受理済み処理はホストで続く。

Gmailの読取アダプターは既存Career pluginの `GoogleAccounts` / `google_connected_accounts.read_account` を利用し、
選択した接続アカウントに限定する。就活の受信先・企業別送信元設定は変更しない。
LLMが任意のGmail検索式で検索し、必要なメールだけを読む。
前回以降の新着確認と、必要に応じた過去メールの再調査を指示する。
新しい案内・締切変更・提出完了の照合もLLMに任せる。
読み取った本文は資料IDと取得時刻を付けてキャッシュする。
1検索50件まで、日次実行8検索・80通まで、本文切り詰め・一部失敗は部分確認にする。

manabaはローカルPlaywright Chromiumと専用ログイン状態を使う。
収集中はJavaScript、POST、ダウンロード、別オリジンへの通信を禁止する。
`/ct/home_library_query` の未提出課題一覧だけをGETし、授業・課題・期限をLLMへ返す。
時間制限のある課題を開始する可能性があるため、課題の詳細リンクを巡回しない。
一覧もキャッシュする。一橋大学の接続先は `https://manaba.hit-u.ac.jp/ct/home`。
大学ごとのHTMLやSSOには実アカウントでの適合確認が必要。
ログイン切れ、未対応構造、上限到達を空の課題一覧に読み替えない。
LLMは索引から必要なキャッシュだけを読み、原文全部を毎回最初のプロンプトに入れない。

モデルは `hermes --profile <owner> chat --toolsets=today_home -Q --query-file -` の
既存CLI経路を使う。このtoolsetは `today_plan`、`today_sources`、`today_cached_read`、
`today_mail_search`、`today_mail_read`、`today_manaba_read` を登録する。
合成用プロセスにメール送信・課題提出・terminal・汎用browserツールを追加しない。
返答はキャッシュ資料ID付きJSONとして検証する。相対日付の解釈、優先順位、
締切変更の判断はLLMに任せる。存在しない資料IDや壊れた日付形式は保存しない。
検索結果は実ホストの配列形式と辞書形式を受け付ける。検索の成功はその検索範囲の確認とし、
メールボックス全体の照合完了とは扱わない。
同じ作業の `task_key` を維持し、メールの更新やタイトルの言い換えだけで完了状態を失わない。
内容の妥当性は実資料による品質確認が別途必要。

## 導入条件

これはローカルで作成・検証する候補。本番への配置や再起動は未実行。
既存Dashboardの認証・plugin API mountとプロファイル経路を確認してから導入する。

1. このフォルダを対象のHermesの `plugins/today_home` に配置する。
   データや認証ファイルは配布物に含めない。
2. 既存設定を保持して `plugins.enabled` に `today_home` を追加する。
   Gmailは同じhomeの既存Careerプラグインと接続済みアカウントが必要。
3. HermesのPython環境にPlaywrightとローカルChromiumがあるか確認する。
   他ブラウザやクラウドの収集サービスへ自動フォールバックしない。
4. Mobileでメールアドレス・manaba URLを保存する。
5. manaba初回ログインはホストの画面が使える環境で、プラグインをPythonの
   module search pathに載せ `python -m today_core.manaba_login --profile <owner>` を本人が実行する。
   専用ブラウザで本人がログインし、manabaホームへ戻ってEnterを押す。
   ヘッドレスホストへの安全なログイン画面の中継はこの候補には含まない。
   パスワードやCookieをチャットへ貼らない。既存ブラウザのデータもコピーしない。
6. 対応APKとサーバーを更新後、認証済みAPI、選択メールアカウント、manaba課題と
   完了済み表示をそれぞれ実データで確認する。

保存先はプロファイルhome内の `today_home/plan.sqlite3` と
`today_home/manaba-session.json`。ログイン状態は0600、ディレクトリは0700で作る。
Windowsではユーザー専用homeの既存ACLを継承する。認証ファイルを配布・Git登録しない。
0.1.0の既存キャッシュがある場合、0.1.1は `source_cache.last_run` を追加する移行を行う。
既存資料は保持し、移行直後の古い資料を今回取得済みとみなさない。
0.1.2以降は読取上限を部分確認として保存し、日付変更時に個別項目の鮮度も計算する。
解決run・時刻・理由・当時の根拠本文と取得runは独立した追記型 `resolution_events` に保持する。
資料再取得やタスク再登場で上書きしない。履歴はホスト内監査用でMobile JSONには含めない。
接続先変更時にはその情報源のタスク・資料と同様に履歴も消去する。
0.1.3は収集とログインの双方で `chromium_sandbox=True` を明示する。

0.1.5はclassic CLI子プロセスを使わず、既存Gatewayへ標準RPCで接続する。
`discover_gateway_endpoint(home).endpoint` を明示して `connect_gateway(endpoint)` を使い、
ownerの自動起動・枠上限変更・モデル/provider指定は行わない。
`session.create(source=oneshot, toolsets=[today_home], run_budget=...)` → セッション結合保存 →
`prompt.submit(finite=True)` → 同じ `prompt.receipt(include_result=True)` の順で実行する。
接続が切れた場合も既知admissionのreceipt照会だけを1回再接続し、promptを再送しない。
送信ackが不明、結果がunknown、時間上限に達した場合は `uncertain` として読取を失効させる。

プロファイルDBのrun_contextsに、受理時の一意token・設定値/世代・有効期限・初期session ID・
stored session ID・input ID・admission IDを保存する。handlerのコアmetadata.session_idで照合し、
モデル引数のIDや登録時のプロセス環境変数は認可に使わない。run_callsで上限をrun別に永続化する。
終端/不確実/期限切れ/設定変更後は旧日次セッションの読取を拒否する。設定変更後も、旧日次IDを
通常チャットと取り違えないため、daily_sessionsにはIDと不透明なtokenだけを残す。
通常の本人チャットは保存済み計画/資料を読めるが、日次確認以外の新しい収集はできない。

0.1.6では、認可・回数予約と同じトランザクションから受理時の固定設定を返し、
外部アダプターにはその設定だけを渡す。認可後に全体設定を読み直して別アカウントを選ばない。
I/O直前にも有効性を再検査する。失効後は新しい予約を拒否し、予約済み通信がすでに進行している
場合には元のアカウントの読取が完了し得るが、失効後の結果は保存もモデルへの返却もしない。
ネットワーク処理の即時取消を保証する実装ではなく、途中で別アカウントへ切り替えることを防ぐ。

0.1.7では終端/失敗/不確実の変更を `BEGIN IMMEDIATE` 内で照合・更新し、UPDATEにも
一意run tokenとrunning状態の条件を付ける。要求IDの再利用・設定変更に割り込まれても、
旧workerが置換後の処理を変更しない。終端の実行メタデータもtoken別に保存する。
資料には `source_cache.run_token` を追加し、「今回取得済み」と解決根拠はこのtokenで照合する。
旧資料の内容・取得日時・last_runは保持し、新しいrun_tokenは空で移行して未再確認とする。

標準RPCの実装契約は実ホストの調査に加え、NousResearch/hermes-agent の
[Gateway client](https://github.com/NousResearch/hermes-agent/blob/4a0eaf7989b6804319ddb94fa5d96c5a716080a1/hermes_cli/gateway_client.py)、
[session契約](https://github.com/NousResearch/hermes-agent/blob/4a0eaf7989b6804319ddb94fa5d96c5a716080a1/tui_gateway/contracts/canonical_sessions.py)、
[receipt契約](https://github.com/NousResearch/hermes-agent/blob/4a0eaf7989b6804319ddb94fa5d96c5a716080a1/tui_gateway/contracts/canonical.py)
を確認した。SDKの有界イベントqueueは本文を記録せず消費する。
隔離試験では模擬Gatewayと実ツールhandlerを使い、切断/ack喪失/枠拒否/時間切れ/非起動時の失敗、
同一receipt回復、設定世代・run別上限・失効、資料保存から一覧生成までを確認する。

## 完了済み出力の復旧（0.1.9）

最上位の既知の補足欄 `verification_notes` は判断や操作として扱わず除外する。
それ以外の未知欄、各タスクの型・期限・根拠資料、解決判定の鮮度は引き続き検証する。
モデルにも最上位を `tasks` / `resolved` だけにするよう指定する。

ホスト管理者が標準Gatewayから同じadmissionのterminal/completed receiptを再取得できる場合だけ、
`finish(..., recovery_receipt=receipt)` でfailedの保存処理を復旧できる。HTTP・モデルツールへは公開しない。
管理者はcheckpointのrequest ID/tokenとDBの実行世代が一致することを確認し、
新しいPlanStoreの `lease_tokens[request_id]` にその同じtokenを設定する。
`parse_plan_response(receipt['result']['final_response'])` と `cached_inputs(request_id)` を渡す。
receiptのadmission/session/execution generation、保存済みcompleted記録、現在の設定世代、
最新requestとの一致をwriter transaction内で確認し、結果を変更した復旧や二度目の復旧は拒否する。
execution generationは両側の存在と厳密な整数型を必須とし、bool・float・欠落は拒否する。
出力の照合は引数answerとreceipt内final_responseの一致であり、元出力のハッシュ認証ではない。
真正な標準receiptを取得し無改変で渡す保証は、権限を持つホスト管理者が担う。
元requestをrunningへ戻さず、読取権限の再付与・収集・モデル再実行は行わない。
成功時は同じrequestをcompleteへ進め、失敗時は保存をrollbackする。本文やreceiptをログに出さない。

## API・検証

認証済みDashboardの `/api/plugins/today_home` に `GET /plan`、`GET/PUT /settings`、
`POST /refresh`、`PATCH /tasks/{id}` をmountする。GETは収集・モデル呼出を行わない。
0.1.4ではコアの `hermes_cli.plugin_host_http_auth.admitted_for_sensitive_route` を使い、
native owner・Dashboard session・host isolationの標準認証を受ける。
チケットの再消費や独自principal判定は行わない。標準ガードがない環境では503で拒否する。
cross-origin書込拒否も維持する。

```powershell
$env:PYTHONPATH = (Join-Path (Get-Location) 'server/today_home')
python -m unittest discover -s server/today_home/tests -v
```

参照・活用調査（2026-10-23）:
[Google Workspace MCP](https://github.com/taylorwilsdon/google_workspace_mcp)と
[DailyBrief](https://github.com/Prem4777/DailyBrief)を比較。
既存Gmailのアカウント境界・認証を保つため標準の読取bridgeを再利用し、
追加OAuthや別のAIサービスは導入しない。
manaba認証状態の保存・再利用には[Playwright公式API](https://playwright.dev/python/docs/auth)を使用する。
[get-manaba](https://github.com/book000/get-manaba)の公開実装で課題リンク形式も確認した。
大学URLは[一橋大学の公式利用案内](https://www.hit-u.ac.jp/ictc/wp-content/uploads/2025/03/情報環境利用案内_v12.pdf)を参照。

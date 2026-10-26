# Personal AI Shell 監査報告

監査日: 2026-09-06（Asia/Tokyo）  
対象: Task 00。ユーザーforkと仕様指定baselineの実コード監査・検証・計画のみ。Task 01以降は未実装。

> PR 00取込み注記（2026-09-06）: 以下は採用されたTask 00の監査記録。
> 当時のcwd・`.audit`・仕様の不整合は履歴として保持する。現在の製品checkoutと
> 文書で解消した点は [PR00_REPORT.md](PR00_REPORT.md) と
> [ADR-0004](../personal-ai-spec/adr/0004-audit-aligned-foundation.md) を参照。
> HEADは同じで、監査のビルド/テストを再実行していない。未修正のコードリスクは残る。

## 1. 結論と監査範囲

既存アプリを維持したまま、単一 `:app` 内の小さな変更で4-tab shellとtransport境界を導入できる。作業途中でユーザーからfork済みと回答があり、`msidragonsasus/hermes-mobile` のdev、`fd485a69990b358f81d8dd4c12c761e7d2555d24` を実対象として追加監査した。仕様のDI・DBの前提は実コードと異なる。承認要求の選択、秘密値のログ、オフライン送信、DB消失経路を先に扱う条件付き計画とし、Task 01の一括着手は推奨しない。

最も重大な機能上のリスクは、既存承認カードと実際に解決される要求が一致する保証がないこと。forkではrequest ID・pending再取得が追加済みだが、カードcallbackはまだactionだけで、ViewModelは最後の承認を選ぶ。新しいToday/Activityから返答する前に、接続先・profile・保存session・実行session・request IDを固定し、サーバー応答まで確認する必要がある。forkに追加されたAllow session / Always allowも、毎回承認という新仕様と衝突する。

「ソースに存在する」「公開CIで成功している」「このWindows環境で実行できた」「ユーザーのHermes/端末で動いた」を分けて記録する。最後の項目は未確認。実Hermesへの接続、モデル実行、外部write、公開、push、端末へのインストールは行っていない。

## 2. Actual baseline

最初に指定cwdで実行した結果:

```text
pwd
E:\OSHIIRE\software\Hermes

git remote -v
fatal: not a git repository (or any of the parent directories): .git

git branch --show-current
fatal: not a git repository (or any of the parent directories): .git

git rev-parse HEAD
fatal: not a git repository (or any of the parent directories): .git

git status --short
fatal: not a git repository (or any of the parent directories): .git
```

開始時には `docs/personal-ai-spec/` のみ存在し、ルート `AGENTS.md` と `DESIGN.md` は存在しなかった。既存リポジトリのパスを質問しつつ、仕様の指定upstreamを監査専用の別ディレクトリへ取得した。既存working treeをresetしたわけではない。

| 項目 | 監査用checkout |
|---|---|
| pwd | `E:\OSHIIRE\software\Hermes\.audit\hermes-mobile` |
| remote fetch/push表示 | `origin https://github.com/Hy4ri/hermes-mobile.git`（取得のみ。push未実行） |
| branch | detached HEAD、`git branch --show-current` は空 |
| HEAD | `5c8ab7ed6ca573b8b4d1c6da01128cc161a8eb60` |
| checkout直後のstatus | clean |
| 監査時の公開dev | `fd485a69990b358f81d8dd4c12c761e7d2555d24` |
| baseline→公開dev | 127 files changed / 11,077 insertions / 1,714 deletions |
| license | ルート `LICENSE`: Apache-2.0 |
| ユーザーfork / upstream remote | 未設定。公開upstreamへPRを作っていない |

ユーザーの回答後、接続済みGitHubアカウントからforkを特定し、別checkoutを取得した。

```text
pwd: E:\OSHIIRE\software\Hermes\.audit\fork
git remote -v:
  origin https://github.com/msidragonsasus/hermes-mobile.git (fetch)
  origin https://github.com/msidragonsasus/hermes-mobile.git (push)
git branch --show-current: dev
git rev-parse HEAD: fd485a69990b358f81d8dd4c12c761e7d2555d24
git status --short: （空、clean）
```

forkはpublic、default branchはdev。監査時のupstream devと同一SHAだった。本報告の**実装計画はforkのfd485a6を基準**とし、5c8ab7eへ戻さない。元の指定cwdは引き続きGit管理外で、依頼された3文書はその `docs/personal-ai/` に作成する。監査checkout内の製品ソースとリモートは変更していない。

fork差分で重要なのは、request ID付きapproval/pending/received、batch clarification/expire、event replay/seq/epochの追加である。これらは新規に再実装せず利用する。NavigationController、Navigation、ScreenRegistry、HermesDatabase、DESIGN、module構成は指定baselineと同一内容だった。

Hermes Agent調査用checkout: `.audit/hermes-agent`。参照コミットは `9dd6634c5635321cf38840cc30e9b51226689128`。これは公開ソースの観測点であり、ユーザーが運用するHermesのバージョンではない。

## 3. 読んだルールと仕様

監査用mobile checkoutの既存 `AGENTS.md` → `DESIGN.md`、指定された `CODEX_START_HERE.md` / `CODEX_00_AUDIT_AND_PLAN.md`、仕様パッケージのREADME、00_DECISION、PRODUCT_SPEC、UX_SPEC、ARCHITECTURE、MINIAPP_PLATFORM、SECURITY、ROADMAP、DESIGN_REVIEW、ADR 3件、AGENTS_ADDENDUMを確認。Task 01は計画範囲の確認として読んだだけで実行していない。Schema、全3サンプル、SOURCES、BACKLOG、QUICKSTARTも確認した。

既存ルールのNavigationController経由の遷移、画面所有のdrawer gesture、HermesScaffold、semantic colors、既存画面保持を継承する。upstreamのPR-alwaysは今後の変更手順として維持するが、今回は依頼どおり監査文書までで停止する。

## 4. ビルド、テスト、CI

### 実際の構成

根拠: `settings.gradle.kts`、`app/build.gradle.kts`、`gradle/libs.versions.toml`、`gradle/wrapper/gradle-wrapper.properties`。

| 項目 | baseline実値 |
|---|---|
| Gradle modules | `:app` のみ |
| applicationId / namespace | `com.m57.hermescontrol` |
| Gradle / AGP | 9.7.1 / 9.1.1 |
| JDK / bytecode target | 21 |
| Kotlin / KSP | 2.4.10 / 2.3.11 |
| Compose BOM / Navigation3 | 2026.08.00 / 1.1.7 |
| SDK | min 26 / target 37 / compile 37 / buildTools 37.0.0 |
| Room / SQLCipher | `androidx.room3` 3.0.2 / 4.18.0 |
| Networking | Retrofit 3.0.0 / OkHttp 5.5.0 / kotlinx.serialization 1.11.0 |
| DI | **Hilt/Daggerのplugin・依存・annotationはない**。object、手動生成、ViewModel factory |
| tests | 指定baselineは `src/test` Kotlin 109ファイル、forkは113ファイル。`src/androidTest` は両者8ファイル（ケース数とは別） |

上表の主要構成はforkでも同じ。変更はCoil 3.6.0→3.6.1、LaTeX 1.5.3→1.5.4、CI/AGENTSのktlint 1.2.1→1.8.0。forkもHilt未導入、Room3/schema v6、`:app` のみである。

### ローカル検証（2つのSHAを分離）

既存 `JAVA_HOME` は Temurin `jdk-21.0.10.7-hotspot`。PATH上の `java` はJava 8だったため、Gradle wrapperが参照するJAVA_HOMEを確認した。`ANDROID_HOME` は元々未設定、SDKは `C:\Users\keima\AppData\Local\Android\Sdk` に存在した。検証コマンドのプロセス内だけANDROID_HOMEを設定した。

UTF-8は既存 `org.gradle.jvmargs=-Dfile.encoding=UTF-8` に加え、検証プロセスの `JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8` で明示した。ホスト全体の環境変数やプロジェクト設定は変更していない。Gradleが既承諾ライセンスを用い、不足していたBuild Tools 37.0.0とPlatform 37.0を既存SDKへ自動追加した。Gradle依存キャッシュ・ビルド出力も生成されている。

実行コマンドはWindows用 `./gradlew.bat <task> --no-daemon --console=plain`。Git for Windowsのshと各SHAのCIに一致するktlintを、検証プロセスのPATHと `.audit/tools*` だけに追加した。フォークを特定した後に、同じ検証をforkでも実行した。結果の確定値は下表へ記録する。

| 検証 | 指定baseline `5c8ab7e` | ユーザーfork `fd485a6` |
|---|---|---|
| `ktlintCheck` | 成功（1.2.1）。初回sh未検出はprocess PATH整備後に再実行 | 成功（1.8.0） |
| `testDebugUnitTest` | 1,177件中1件失敗、error/skip 0 | **1,275件中2件失敗**、error/skip 0 |
| `lintDebug` | 成功 | 成功 |
| `assembleDebug` | 成功 | 成功 |
| `checkColorLiterals` | 失敗：theme除外のWindowsパス判定 | 同じ失敗 |
| `compileReleaseKotlin` | 成功 | 成功 |
| `testReleaseUnitTest` / `connectedDebugAndroidTest` | 未実行 | 未実行 |

失敗の内訳と根拠:

- 両SHAの `GatewayFileClientTest.normalizePath expands tilde`: expected `/home/test/foo.png`、actual `null`。`GatewayFileClient.kt:122` は `HOME` がなければnullを返す一方、testはHOME未設定時に `/home/test` を期待する。今回のWindowsはHOME未設定。この不整合は確認できたが、remote側の `~` をclient環境で展開すべきかという契約は別途確定が必要。
- forkの `HermesWsClientTest.testSequenceNumberDeduplication`: expected `[A]`、actual `[]`。testはIO coroutineでcollectorを起動し、socket送信後に固定100msのsleepを置く。購読開始の同期がなく、タイミング依存の可能性がある。ただし原因を確定する切り分けや再実行はしておらず、製品のevent欠落ともテストのraceとも断定しない。
- `checkColorLiterals`: `app/build.gradle.kts` の除外が `/theme/` を含む文字列判定で、Windowsの `\\theme\\` に一致しない。ログは除外対象の `theme/Color.kt`、`theme/presets/*` を違反として列挙する。検査を無効化したりtheme以外も除外したりせず、portableな相対path判定を独立PRで直す。

`adb` でonline device/emulatorは0台。実機・emulator試験、APKインストール、署名付きrelease buildは未実行。公開CIの試験結果と混同しない。詳細は `.audit/logs/test-summary.json` / `fork-test-summary.json` と各taskのログ。

補助検証: `jsonschema 4.25.1` を `.audit/python-deps` に限定して配置し、Draft 2020-12 + FormatCheckerでSchema自体とCareer / University / Developmentの3 manifestを検証、全件成功。システムPythonのパッケージは変更していない。`py -X utf8 .audit/audit_checks.py .audit/fork` でforkの補助検査を再現できる。

### 公開CIの観測

指定SHAのGitHub check-runsを読み取り、ktlint、Android Lint、Unit & Integration Tests、Debug APK、Release Compile、Instrumented Tests、CI Summaryのsuccessを確認した。根拠は[5c8ab7eのCI run](https://github.com/Hy4ri/hermes-mobile/actions/runs/33279258993)。forkと同じfd485a6についても、upstreamの[CI run](https://github.com/Hy4ri/hermes-mobile/actions/runs/33969861024)で各jobのsuccessを確認した。保存したレスポンスの `head_sha` と対応する。後者もupstreamの既存実行であり、forkで新たに起動したCIや今回のローカル成功の代用ではない。

`.github/workflows/android.yml` では `ktlintCheck` + `checkColorLiterals`、`lintDebug`、`test`、`assembleDebug`、`compileReleaseKotlin`、API 34 AOSP ATD emulatorの `connectedDebugAndroidTest` を実行する。forkのDetect Changesは文書だけの変更で後続jobをskipできる。`ci-summary` のneedsにはinstrumented-testsが入っていないので、SummaryだけでUI回帰合格としない。release workflowはタグ起動で署名APKを作る。今回タグ・workflow起動・配布はしていない。

### 署名と秘密情報

release署名は `KEYSTORE_PATH`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。値は取得していない。非署名コンパイル時のdummy設定と、本物のrelease署名を混同しない。applicationId・署名を変えずに上書き更新することが今後の前提。

両SHAのtracked textの限定パターン検査では、private key header、GitHub token literal、AWS access IDの一致なし。全履歴・任意形式の秘密値・端末ログの非漏洩を保証する検査ではない。詳細は `.audit/logs/audit-checks.json` / `fork-audit-checks.json`。ログ出力経路の問題は後述する。

## 5. Current architecture map

以下のAndroidパスは `app/src/main/java/com/m57/hermescontrol/` からの相対パス。構造は両SHAで共通、機能差は明記する。

```text
HermesControlApp
  ├ AuthManager / ServerStore / CookieManager
  ├ NetworkMonitor
  └ 起動時preload/update check
MainActivity
  └ Navigation.kt + NavigationController + ScreenRegistry
       ├ ChatScreen → ChatViewModel
       │    ├ HermesWsClient singleton → EventParser → WsEvent
       │    ├ ChatWsEventReducer / ChatStreamingController
       │    ├ ApiClient.hermesApi（REST履歴等）
       │    └ ChatPersistenceRepository → ChatMessageDao → HermesDatabase
       └ 既存の管理ViewModels → ApiClient / HermesWsClient
ChatNotificationService / NotificationReplyReceiver
  └ 同じHermesWsClient + ActiveSessionHolder +通知Intent
```

UI/ViewModelから直接APIとRPCを呼ぶ既存箇所が多い。`ChatPersistenceRepository` はDAOのラッパーで、Hermes protocol repositoryではない。`ChatWsEventReducer` は副作用を分離した再利用可能な境界、`ChatStreamingController` は約33msの更新制御を持つ。これらを捨てず、通信依存だけを段階的に移す。

### 機能棚卸し

| 機能 | 実装と不足 |
|---|---|
| Chat | ストリーミング、Markdown、tool表示、reasoning、添付、モデル選択、slash command、検索、branch、scroll制御がある |
| Session | RPC create/list/resumeとREST履歴・検索・管理。保存IDとruntime IDを別扱いする。resume世代番号とstale応答対策あり |
| Run | typing/tool/subagent/backgroundイベントとsessionの実行状態はある。独立した永続Run model/repository/全体timelineはない |
| Approval | forkはrequest_id、choices、pending再取得、received ACK、resolved確認あり。ただしカード選択とのbinding、元session保持、expiry、対象Runが不足。ACK前に操作を消す。session/always許可も表示する |
| Clarification | forkは選択肢・自由入力・複数質問qid/multi-select、clarify_id/request_id、expire受信あり。ACK前に表示を消す経路、expired成功包絡、process death後の復元を追加検証する |
| Secret / sudo | request ID付き応答とダイアログあり。debug送信全文ログが秘密値を含み得る |
| Cron / jobs | RESTで一覧、作成、更新、pause/resume、trigger、delete。Kanbanにも別WSイベント経路あり |
| Notification | pending reply時のForeground Service、完了通知、inline reply。clarification通知はsession IDがnull。Approval通知・正確なAttention deep linkはない |
| Apps | Hermes dashboard plugins管理画面はある。Mobile manifest、contribution、権限broker、Apps launcherは未実装 |
| Android統合 | 既存音声・添付・assistant serviceあり。仕様上の将来機能だからといって既存機能を削除しない。共有Intentの新規導線は別工程 |

## 6. Endpoint / protocol map

**Dashboard REST と API Server REST は別サーバー面**。同名の `/api/sessions` があってもDTO・認証・機能契約が同じとは限らない。API Serverを既存Retrofit clientに単純に差し替えない。

| Surface | 確認できた契約 | Android baselineの状態 / 根拠 |
|---|---|---|
| Dashboard REST | `/api/status`, `/api/health`, `/api/sessions`, `/api/sessions/{id}/messages`, session検索・詳細・PATCH/DELETE | `data/remote/HermesApiService.kt:163` 以降。Agent側 `hermes_cli/web_routers/status.py:110` に `/api/health` |
| Dashboard管理REST | `/api/cron/jobs` と各job操作、profiles、skills、config、tools、logs、gateway、files等 | `HermesApiService.kt`。既存画面から利用する宣言あり。全endpointのlive疎通は未実施 |
| Dashboard plugins | `/api/dashboard/plugins` 管理、`/api/plugins/kanban/...`、achievements等 | mobile serviceとAgent `web_server_dashboard.py:831` のplugin router mount。Mobile独自routeは存在確認なし |
| Dashboard WS | `/api/ws?token=...` または `?ticket=...`、JSON-RPC 2.0、`method:event` + params.type/payload | `ServerEndpoint.kt:35`、`HermesWsClient.kt`。Agent `hermes_cli/web_routers/chat_ws.py:541` |
| TUI sessions | `session.create/list/resume/history/status/active_list/interrupt/redirect/branch`、`prompt.submit` | `WsMethods.kt` と `tui_gateway/methods_session.py` / `methods_prompt.py`。steer/redirectは同一意味と決めつけない |
| TUI interaction | `approval.respond`, `clarify.respond`, `sudo.respond`, `secret.respond`、`approval.pending/received` | forkは全て既存呼出あり。5c8ab7eではpending/receivedとapproval request IDがない。新UIはfork側の既存処理を継承する |
| TUI replay | `session.events.since {session_id,last_seen}` → events/latest_seq/truncated/epoch | 現Agent `methods_session.py:2122`。forkはseq水位、epoch、受信hold/replayを実装済み。truncatedのsnapshot補完が不足 |
| API health/discovery | `GET /health`, `/health/detailed`, `/v1/capabilities` | 現Agent `api_server.py:1512`、`:2244`。baseline mobileにAPI Server transportなし |
| API session/chat | `/v1/chat/completions`, `/v1/responses`, `/api/sessions` とsession chat/stream | 現Agent route表、programmatic integration docs。baseline mobileのDashboard DTOを流用不可 |
| API runs | `POST /v1/runs`, `GET /v1/runs/{run_id}`, `/events` SSE、`POST .../approval`, `/steer`, `/stop` | `api_server_runs.py:99`。baseline mobile未実装。全Run一覧を得る `GET /v1/runs` はこの表にない |

Dashboard認証にはBearer tokenと、Basic認証フロー由来のcookie + one-time WS ticketの分岐がある。gated modeにBearerを重ねない。`ApiClient.rebuild`、`TokenRefreshAuthenticator`、`DashboardSessionTokenRefresher`、primary/secondary WS ticketの別mint、profile切替の世代管理を維持する。proxy base pathとHTTP→WS変換は `ServerEndpoint` を再利用する。LANの注意文はあるが、cleartextのprivate host限定を実行時に強制する実装ではない。

## 7. session / run / approval / reconnect 詳細

### 承認対象の取り違え

指定baselineの5c8ab7eではEventParserがapprovalのrequest_idを落としていた。forkのEventParserはrequestIdを保持し、`ChatViewModel.kt:3765` がApprovalInfoへ渡し、approval.received/pendingの再取得、`:3912` でrequest_id付きrespondも実装済み。これを「未実装」としてやり直してはいけない。

残る問題は、forkの `ChatBubble.kt:359` 以降も各カードが `onRespondApproval(action)` しか渡さず、`ChatViewModel.kt:3882` が `lastOrNull { approvalInfo != null }` と現在のruntimeSessionIdを選ぶ点。二つのカードが表示された状態で古いカードを押すと、最後のカードのrequest IDを送る可能性がある。ApprovalInfoには元sessionがない。legacyのIDなしpayloadもFIFO fallbackになる。liveで誤操作を起こす再現はしていない。

対策はrequest単位の状態機械。`Pending → Responding → Resolved/Rejected/Expired/Unknown` を持ち、カードcallbackに固定RequestRefを渡し、`resolved` が正しく確認できた時だけ完了にする。forkはapprove→onceの正規化も実装済み。新方針ではonce/denyだけを許可し、session/alwaysをUIとtransportの両方で拒否する。Modify専用endpointは確認していないため、編集で元承認を許可したことにしない。

### 再接続と送信

`HermesWsClient.kt` は指数backoff、network change、ping/watchdog、generation guard、pending RPCのreject、auth expiryを扱う。`send(true)` の送信を無条件にreplayしない対策は既にある。

一方、未接続またはsend拒否時のメッセージを種類によらずサイズ基準でqueueし、再接続直後にflushする。これはforkでも残る。Composerには接続中だけ送信可のUI gateがあるが、接続直前のraceや他の送信元、承認/secret応答はtransport境界で保護できていない。新しいoffline方針には再確認付きdraftと、read RPC / prompt / approval / secretの送信ポリシー分離が必要。

`ChatViewModel.handleGatewayReady` はsession一覧を再取得し、保存済みsessionならresume、未保存sessionなら再createする。forkの `HermesWsClient.fetchReplay:900` にはper-session seq水位、epoch、受信hold/replay/重複排除が追加済み。ただし返却 `truncated` を読んだsnapshot再取得はなく、watermarkはin-memory。再接続機能を全面新設せず、この欠落とprocess death、Run/Attention集約を補う。DROP_OLDESTのevent flowで重要要求を欠落させない再同期も必要。

`NetworkResult.safeApiCall` はmethod非依存で最大2回retryする。新しいwriteをそのまま通さない。POST結果不明やtransport切替時に別protocolで再送すると、二重実行を作る。fallbackは**送信前の機能選択**とread側に限定し、進行中Runの自動乗換えをしない。

### ログと永続化

- `HermesWsClient.kt` にdebugの送受信全文ログ（forkでは `:767,1129,1147` 付近）。`respondToSudo/Secret` の値も通る。forkの `:1033` のURLマスクもtokenのみでticketを覆わない。parse例外に元payloadが含まれる可能性も検査対象。
- `ApiClient.kt` の実値はdebug BASIC / release NONE。root AGENTSのBODY注意書きは現コードより古い。GroupChatViewModelにも本文ログがある。
- Roomはschema version 6、entityはChatMessageのみ。2→3→4→5→6のmigrationあり。Run、Attention、Draft、MiniApp entityはない。chat rowはserver/profile別の列を持たずsession ID中心で、今後のキャッシュキーは接続先スコープを含める。
- `HermesDatabase.kt:85` 以降に旧plaintext DBまたはheader読取エラー時のdelete、さらに `fallbackToDestructiveMigration(false)` がある。引数falseでも非破壊migrationの保証にはならない。既存データ保持要件と衝突するため、端末更新前の停止ゲートとする。
- AuthManagerはKeystore由来AES256鍵のEncryptedSharedPreferencesを使用し、DB password、profile token等を保護。cookieの永続化もEncryptedCookieStore。`allowBackup=false`。添付などのfile cacheまでSQLCipherで暗号化されるわけではない。

## 8. NavigationとUIの変更方法

ScreenRegistryには27定義。Chat、History、Profiles、Bots、CronJobs、Webhooks、Kanban、Skills、Toolsets、Plugins、Config、McpServers、Memory、Model、Pairing、Keys、Channels、Providers、Gateway、System、Logs、Processes、Analytics、Billing、Files、Achievements、Settings。

これ以外にLanding/Auth、Settingsの6 subpage、ToolsetDetailKey、MemoryProviderDetailKey、GroupChatKeyをNavigation.ktが登録する。registryの27件だけを移して完了にしない。

`NavigationController.isPrimaryScreen` はALL_SCREENSを参照し、primary遷移で `[ChatScreen,key]` へresetする。単にToday等を登録すると戻り先が常にChatのままになる。最初に「登録済み画面」と「shell root」「Advanced」「drill-down」のmetadataを分離し、既存の重複防止はcontroller内に残す。

`Navigation.kt:179` のstackは `remember { NavBackStack(...) }` で、saveable stackではない。process recreationで全画面復元済みとは言えない。`NavigationController.pendingChatNavigation` もin-memoryで、MainActivityが通知extraを消費するため、cold start・接続前待機・再生成をまとめて試験する。

最小移行は、既存registryの互換wrapper → 明示的navigation role → saveable shell選択/stack → 4-tab bottom bar → Today/Activity表示の順。Advancedには既存contentをそのまま渡す。Chat再生成や新規session自動createをtab切替で誘発しないよう、session lifetimeをnavigation entry lifetimeから分離する。

共有UIはHermesScaffold、StateViews、DetailDialog/Rows、既存streaming/scroll controllerを再利用。Scaffold内の二重paddingを避ける。6 theme、semantic status color、48dp、TalkBack、日本語、large font、IME/insetsを維持。DESIGNのFAB禁止と既存ChatScrollFabの差は記録し、新規FABを増やさず既存scroll回帰を避ける。

## 9. HermesTransport / capability negotiationの実現性

実現可能。ただし全既存管理画面を最初のPRで移すと全面rewriteに近くなる。

1. `:app` 内にdomainのConnection/Capability/Event/SessionRef/RequestRefと `HermesTransport` を追加し、最初はconnection・capability・read/event観測だけを包む。
2. `DashboardRpcTransport` は既存HermesWsClient / ApiClientへのadapter。同じsocketを使い、第二のreconnect loopやqueueを作らない。Fakeはテスト/previewに限定し、live不足を架空データで埋めない。
3. 既存Chatのwriteを移す際に、no-auto-replay、unknown result、exact approval、acknowledgementをtransport契約に入れる。raw JSONや任意RPCの公開escape hatchを新ViewModelへ渡さない。
4. `ApiServerTransport` は別DTO・別authを持つ独立adapter。Phase 1ではcontractとdisabled理由の定義まで可能。live fallbackの実装は互換性検証が通った機能だけに限る。

capabilityは単純Booleanより `Unknown / Unsupported / Supported / Blocked` + 理由・情報源・観測時刻が安全。connection/profile変更で前の結果を破棄する。versionはhealthで返れば記録し、version番号から機能を決めない。

| Transport | 起動時の実在surface | 判断の限界と扱い |
|---|---|---|
| Dashboard RPC | `/api/health`、`gateway.ready`、必要なら既存read-only `session.list` 等 | readyは主にskin/change_events/heartbeat/replay_epochであり、sessions/runs/approvals全機能一覧ではない |
| Dashboard RPC補助 | `gateway.capabilities` | 現ソースでは `per_session_exclusive_submit` だけ。包括的capability endpointと解釈しない。旧serverのmethod-not-foundはUnknown/Unsupported |
| Replay | `session.events.since` のread-only応答 | epoch/seq/truncatedを検証。欠落・restart時は履歴/status/pendingの再取得。未対応ならsnapshot同期と明示的な状態不明 |
| API Server | `/health`, `/health/detailed`, `/v1/capabilities` | featureとendpoint表をprotocolごとに解析。401と404、壊れたJSON、timeoutを区別。広告された任意URLへcredentialを転送しない |
| Approval安全性 | 実requestのrequest_id + pending照合 +解決ACK | capabilities.approval_eventsだけではexact binding、expiry、外部write全体の人間承認を保証しない |

未対応判定のためにpromptやapprovalを試し打ちしない。read probeで分からない安全性は未確認としてwriteを無効化し、別途隔離contract testで確かめる。RPC実行をlocal Run表示へ正規化する際、local tracking IDをHermesのdurable run IDとして表示/送信しない。

## 10. NousResearch/hermes-agent#84406

2026-09-06にGitHubのissueページとREST APIを確認した結果、[#84406](https://github.com/NousResearch/hermes-agent/issues/84406) は **Open**、関連 [PR #84535](https://github.com/NousResearch/hermes-agent/pull/84535) も **Open / unmerged**。issueは0.20.0のruns/session continuity/approvalの組合せを報告している。

現在の公開sourceには、その後の機能もある。`api_server_runs.py:398–427` はbodyの `session_id`、response chain、`X-Hermes-Session-Key` を使い、既存session履歴を読む経路がある。runごとのapproval namespace、durable idempotency、statusの保存も実装されている。しかし同経路で `X-Hermes-Session-Id` を直接読む処理は確認できず、chat-completions経路とは区別が必要。`/v1/capabilities` の汎用 `session_continuity_header` フラグをrunsにも適用できると考えてはいけない。

結論は「全runsが継続不能」とも「修正済み」とも断定しない。**headerによる互換契約は未解決、body session_idの代替はsource上の候補**。実運用版が未特定なので、Run A→approval→完了→同session Run B→履歴整合→切断復帰を隔離環境で検証するまではAPI fallbackの継続Run/承認を有効にしない。検証時もモデル/ツールはfakeから始め、費用や外部writeを伴う実行を自動で行わない。

## 11. Mini App manifest基盤

三層方式は既存single-moduleに追加可能。Phase 1はmodel/parser/validator/permission型とfixturesまで。Nativeは組込registryのfeature IDへ解決し、manifestのclass名をreflectionでロードしない。SDUI/Webはparse可能でも起動不可状態とする。

既存 `OkHttpProvider.json` のSnakeCase / ignoreUnknownKeysをそのまま使うと、camelCase・additionalProperties:falseのmanifest契約とずれる。manifest専用Json設定、sealed entry、明示的wire名を使う。未知の安全性関連field/permission、重複contribution ID、過大JSON、unsupported schema、未登録feature、URL scheme/origin/path escapeをホスト側でも検査する。

Schemaの `uniqueItems` はID単位の一意性を保証しない。URI formatは同一origin・private network・許可schemeを保証しない。署名metadataが存在するだけでは署名検証にならない。MINIAPP_PLATFORMの「未知fieldは原則無視可能」とSchemaのadditionalProperties:falseは要整合。Phase 1はSchemaの明示契約を優先する案とし、後のspec整合PRで確定する。

`mobile/` 配置および `/api/plugins/personal-ai-mobile/apps` 等は**提案契約**。Hermesに実装済みのdiscovery endpointとして呼ばない。Phase 3の別backend契約が必要。Mini App権限はhost実行時default deny、無効化時に全contributionを除去する。remote DEX/JAR/SO、WebView、SDUI renderer、remote install UIは今回もPhase 1基盤も対象外。

## 12. 仕様と実態の差・決定が必要な箇所

| 差分 / 矛盾 | 計画上の扱い |
|---|---|
| 指定cwdにGit/root rulesがない | fork `msidragonsasus/hermes-mobile` / dev / fd485a6を追加確認済み。文書は指定cwdにあり、製品PRへの文書取込みは次のdocs PRで行う |
| Hilt採用済みという仕様、実コードは未導入 | Phase 1はconstructor injectionと手動registry。Hilt移行はPhase 2の独立判断としてADRに記録する案 |
| root AGENTSのRoom 2.7/KSP 2.3.10と、実際のRoom3/KSP 2.3.11 | code/catalogをbaseline事実とし、次のdocs整合PRで記述を更新 |
| 全capabilityをgateway.readyで取得できるという期待 | 実在する限定handshake + scoped read probes + Unknownで設計 |
| OFFLINE自動送信禁止 vs 既存WS queue | 接続中の通信挙動保持と、未接続write禁止を別PRで明示的に変更 |
| v1はApprove onceのみ vs forkのAllow session / Always allow | upstream互換機能として存在する事実を明示し、新製品policyとしてUIと送信側をonce/denyへ制限する |
| 全データ保持 vs DB delete/destructive migration | 実機更新前に非破壊移行・失敗時停止を先行実装 |
| 任意の外部writeに明示承認 vs 既存汎用Hermes tool実行 | Androidのカードだけでは強制できない。server側のtool policyと実行経路の確認をlive write有効化の条件にする |
| ROADMAPではmanifest parserがPhase 3、Task 01では基盤必須 | Task 01ではoffline parser/validatorだけ。discovery/render/実行はPhase 3 |
| BACKLOGはAdvanced groupingをP1、Task 01は既存管理画面保持を必須 | 4-tab導入と同じ段階でAdvanced導線を保持 |
| DESIGNはFAB禁止、既存Chatにscroll FAB | 新規FABは作らず、既存挙動を保持。無関係なUI修正を混ぜない |
| root AGENTSの「SDKなしではGradle禁止」とTask 00のattempt要件 | 今回SDKあり。未導入SDK componentはGradleが追加した。指示を緩めていない |

## 13. 実装に入る前の停止条件

対象forkとdevのSHAは確認済み。次回の着手時にremote/HEAD/working treeを再確認し、feature branchを作ってから変更する。今回は監査checkoutにPersonal AI実装を始めない。

実Hermes版・接続profileがなくてもFake/純粋contract/registryの準備は可能。ただしlive approval、API fallback継続、端末上書き更新は、対応するサーバー契約・署名・DB移行の証拠が揃うまで止める。製品名、最初のdomain Mini App、FCM、Web hostは最初のPRを止める質問ではない。

PR列・受入条件は [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)、追跡すべき問題は [RISK_REGISTER.md](RISK_REGISTER.md) に記載。

## 14. 証拠の参照先

- [Mobile固定baseline](https://github.com/Hy4ri/hermes-mobile/tree/5c8ab7ed6ca573b8b4d1c6da01128cc161a8eb60)
- [ユーザーforkの監査SHA](https://github.com/msidragonsasus/hermes-mobile/tree/fd485a69990b358f81d8dd4c12c761e7d2555d24)
- [Mobile AGENTS](https://github.com/Hy4ri/hermes-mobile/blob/5c8ab7ed6ca573b8b4d1c6da01128cc161a8eb60/AGENTS.md)、[DESIGN](https://github.com/Hy4ri/hermes-mobile/blob/5c8ab7ed6ca573b8b4d1c6da01128cc161a8eb60/DESIGN.md)
- [Hermes Agent参照コミット](https://github.com/NousResearch/hermes-agent/tree/9dd6634c5635321cf38840cc30e9b51226689128)
- [Programmatic Integration](https://github.com/NousResearch/hermes-agent/blob/9dd6634c5635321cf38840cc30e9b51226689128/website/docs/developer-guide/programmatic-integration.md)
- [API capabilities source](https://github.com/NousResearch/hermes-agent/blob/9dd6634c5635321cf38840cc30e9b51226689128/gateway/platforms/api_server.py)、[Runs source](https://github.com/NousResearch/hermes-agent/blob/9dd6634c5635321cf38840cc30e9b51226689128/gateway/platforms/api_server_runs.py)
- [TUI approval handlers](https://github.com/NousResearch/hermes-agent/blob/9dd6634c5635321cf38840cc30e9b51226689128/tui_gateway/methods_prompt.py)、[exact/FIFO resolver](https://github.com/NousResearch/hermes-agent/blob/9dd6634c5635321cf38840cc30e9b51226689128/tools/approval.py)
- ローカル証拠: `.audit/logs/*.log`、`github-status.json`、`audit-checks.json`。`.audit` は監査素材・依存・生成物で、製品PRへ含めない。

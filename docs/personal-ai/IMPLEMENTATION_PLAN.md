# Personal AI Shell 実装計画

作成日: 2026-09-06  
状態: Task 00監査を採用。PR 00の文書変更をローカルで準備済み。commit・push・PR公開は未実施、PR 01以降は未着手。
PR 00の変更・確認結果は [PR00_REPORT.md](PR00_REPORT.md)。

## 1. 前提と完了条件

実対象は `msidragonsasus/hermes-mobile`、dev `fd485a69990b358f81d8dd4c12c761e7d2555d24`。仕様の参考点 `5c8ab7ed6ca573b8b4d1c6da01128cc161a8eb60` へ戻さない。現forkが持つapproval request ID、pending再取得、batch clarification、expire、event replayを再利用する。

Phase 0は監査・仕様差・検証結果・PR分割の明文化。Phase 1は既存APKを維持しつつ、transport/capability、Contribution registry、4-tab、Today/Activity/Attention、offline manifest検証まで。Hiltや全Gradle module抽出、SDUI renderer、WebView、discovery backend、FCM、追加Mini Appの業務実装は含めない。

製品の作業checkoutは `E:\OSHIIRE\software\Hermes\hermes-mobile`、branchは `docs/personal-ai-audit`、originはユーザーfork。親フォルダの文書は監査時の原本、`.audit`は監査素材であり製品の作業場所ではない。PR 00で仕様と監査文書を製品checkout内へ取り込んだ。公開は別途依頼された範囲で実行する。

## 2. 最も安全な最初のPR

**PR 00: `docs: align Personal AI Shell baseline and audit gates`（S）**

目的: 古い前提のままTask 01を実行し、既存のreplay/approvalを重複実装したり、存在しないHilt基盤へ全面移行したりすることを防ぐ。

作業branch: `docs/personal-ai-audit`、将来のPR target: ユーザーforkの `dev`。公開は未実施。

変更範囲:

- 本監査の `docs/personal-ai/AUDIT_REPORT.md`、`IMPLEMENTATION_PLAN.md`、`RISK_REGISTER.md` を取り込む。
- `docs/personal-ai-spec/` を取り込み、actual baseline、Hilt未導入、Room3、限定capability、既存replay、manifest parserのPhase 1範囲を整合する。
- 既存root AGENTSを置換せず、Room/KSP記載とPersonal AI追補の適用範囲を修正。DESIGNのtheme/Scaffold/gesture制約を保持する。
- ADRとして「Phase 1は手動DI、HiltはPhase 2で判断」「API Server fallbackは検証された機能だけ」「変更が必要な承認/queue policy」を記録する。

受入条件:

1. `origin` がユーザーfork、base SHAがfd485a6または再監査済みの後続SHA。unrelated local差分を取り込まない。
2. 差分がMarkdown/仕様JSON等の文書だけ。`app/src`、Gradle、Manifest、applicationId、署名、DB、実行時挙動に変更なし。
3. 旧基準とforkの検証結果を分離し、失敗/未実施を成功へ言い換えていない。
4. endpoint表の契約にsource根拠があり、Mobile独自routeは提案と表示。#84406の確認日は採用監査の2026-09-06を保持する。文書だけの今回、live互換性を再検証したとは記載しない。API機能有効化前に最新状態と稼働版を再確認する。
5. 3 manifestのSchema検証成功。設定不整合・受入条件・停止点が3文書間で一致する。
6. `.audit`、依存バイナリ、SDK、APK、ログ、秘密値をcommitしない。
7. CIが走る場合は結果を記録し、既存failureを握りつぶさない。文書だけのPRで既存失敗を修正しようとしてcode scopeを広げない。

**Task 00は採用済み。今回はPR 00の文書変更と整合確認を報告して停止する。commit・push・PR公開、次のコードPRは行わない。**

最初のコードPRは下記PR 02のログ抑止を推奨する。Windowsのbaseline修正PR 01は独立して先行または同時期にレビューできるが、同一差分に混ぜない。

## 3. 小さなPR列

規模はS/M/L、時間見積りではない。Lはさらに分割してレビューできる単位にする。新しいKotlinパスは、既存規約に合わせて `app/src/main/java/com/m57/hermescontrol/` 配下へ置く。

| PR | 内容 / 主要変更候補 | 規模・依存 | 受入条件と停止点 |
|---|---|---|---|
| 00 | 監査文書、spec/ADR前提整合 | S、ローカル準備済み | 上記条件。確認結果はPR00_REPORT。ここで今回停止 |
| 01 | Windows品質ゲートの基準修正。`app/build.gradle.kts` のcolor guardパス、`GatewayFileClient.kt` とtestのHOME/remote path契約、ktlint導入手順 | S～M、00後、製品機能と独立 | theme除外を区切り文字に依存させず、非themeの違反は引き続き検出。HOME有無とremote側 `~` の意味を明示。テスト期待値を都合よく弱めない。Linux CI/Windowsで品質ゲートを確認 |
| 02 | **最初のコードPR推奨**: WS payload/secret/ticketのログ抑止。`data/ws/HermesWsClient.kt`、必要最小限の安全な診断helper、関連log test | S、00後 | debug/releaseともsecret/sudo/token/ticket・任意本文をlogに残さない。send/receive/replay/parse例外すべて対象。socket動作・queue・schema・UIは維持。合成secretで検査し、本物のsecretは使わない |
| 03 | DB保護。`data/local/HermesDatabase.kt`、既存migration tests、必要ならデータ保持ADR | M、00後、実機更新前必須 | plaintext/読取不能/未知version/復号不能で自動delete・destructive再生成しない。v2→v6既存データ維持。未知ケースは回復可能なエラーで停止。元DBは保持。鍵喪失時に新鍵で上書きしない |
| 04 | read/event transport境界。新 `domain/hermes/`、`data/transport/HermesTransport.kt`、`DashboardRpcTransport.kt`、test側Fake、限定repository | M、00/02後 | 既存single socket/auth/reconnectをdelegate。connection/profileに紐づくevent。新ViewModelにraw endpointなし。旧Chat送信を変更しない。既存eventとFakeの同じcontract testに成功 |
| 05 | capability / protocol判定。`CapabilityRepository`、`CapabilityState`、`DashboardCapabilityProbe`、API Server DTO/decoder、`PROTOCOL_COMPATIBILITY.md` | M、04後 | full/partial/malformed/401/404/method-not-found/timeout/reconnect/profile切替を試験。unknownをsupportedにしない。gateway.readyの限定範囲を維持。自動probeでRun/writeを作らない |
| 06a | 送信policyと結果不明。transport write部分、`HermesWsClient` queue、Chat/notification replyからのsubmit入口、Draft model/repository | M、04/05後 | offlineでは明示的draft、再接続で送信しない。接続切替raceでもwriteをqueueしない。send accepted後の不明結果をfailedや再送へ変換しない。機能の自動protocol切替で二重submitしない |
| 06b | exact Attention/approval/clarification lifecycle。`WsEvent`、`EventParser`、`ChatMessage`、`ChatViewModel`、`ChatBubble`、新 `AttentionRepository` | M、06a後 | 押したカードの固定RequestRefにだけ返答。old/expired/duplicate/cross-session/cross-profile/結果0/timeoutを試験。既存request ID/pending/received/batch qidを利用。once/deny以外をUIとtransportで拒否。ACK前に完了扱いしない |
| 07 | Contribution registryの互換wrapper。`ScreenRegistry.kt`、新 `navigation/AppContribution.kt` / `ContributionRegistry.kt` / `LegacyScreenContribution.kt` | S～M、00後、04と独立 | 元の27定義・順序・label・contentと、Navigation.kt固有entryを全保持。duplicate ID/NavKey型/routeを拒否、安定sort。Hilt追加やmodule抽出を含めない |
| 08a | navigation roleと復元。`NavigationController.kt`、`NavigationKeys.kt`、`Navigation.kt`、`MainActivity.kt`、`ChatLifecycleEffects.kt` | M、07後 | controllerのdedup維持。root/Advanced/drill-downを分類。保存可能なstack/tab選択と通知待機先を復元。回転/process recreation/Back/連打/認証遷移を試験。まだ新情報画面を作らない |
| 08b | 4-tab shell + Advanced入口。新 `ui/shell/`、`Navigation.kt`、strings | M、08a後 | Today/Chat/Activity/Apps。Chatは既存content。27画面＋全subpageに到達可能。底部/IME/insets二重paddingなし。タブ切替でsession.createやsocket接続が重複しない |
| 09a | Today/Activityのread表示。新 `ui/today/` / `ui/activity/`、`RunRepository`、event normalization | M、04/05/08b後 | 空/ロード/offline/error/未知状態、Needs You優先、実イベントのtimeline。RPCのlocal tracking IDをdurable Runと偽らない。リスト全体が取れないserverでは観測範囲を明示。Fakeはpreview/testのみ |
| 09b | Attention detailと通知route。新 `ui/attention/`、既存 `notification/`、MainActivity routing | M、06b/09a後 | action/target/risk/source/expiry（不明も明示）。元Run/要求へ応答。高影響操作は詳細画面。clarification通知のIDを保持。2タップ目標、TalkBack/large font/重複通知/冷起動/切断復帰を確認 |
| 10 | Mini App manifest foundation。新 `miniapp/contract/`、`MiniAppManifest` / parser / validator / permission types、3 fixture、Apps静的情報 | M、00/07後 | 3例valid、invalid ID/permission/schema/entry/size/重複/origin/pathを拒否。Nativeは組込feature IDへ解決。disabledで寄与除去。network fetch/renderer/WebView/remote installを含めない |
| 11 | Phase 1結合検証・文書。`IMPLEMENTATION_STATUS.md`、`PROTOCOL_COMPATIBILITY.md`、`SCREEN_MAP.md` | M、全基盤後 | 全品質ゲート、通知session、Chat streaming、Advanced、logout/login、replay truncated/epoch、端末非破壊更新を確認。未対応liveは明示的disabled。Phase 2以降へ進まず停止 |

PR 06/09をまとめて一つの巨大PRにしない。backend変更が必要と分かったら、mobile PRへ暗黙に追加せず、独立した互換契約の提案で停止する。Windowsの失敗修正を一般的なcleanupや全依存更新へ拡張しない。

PR 01ではforkで追加観測した `HermesWsClientTest.testSequenceNumberDeduplication` の失敗も切り分ける。固定sleepと購読開始の非同期性は原因候補に留め、製品の欠落をtest修正で隠さない。製品側の修正が必要なら、HOME/pathの変更から独立した差分にする。

## 4. 最初のコードPR（PR 02）の具体的受入条件

タイトル案: `fix(security): remove sensitive WebSocket diagnostics`

最小対象はHermesWsClientの送信直後・queue flush・受信全文・URL表示・parser例外出力。必要なら共通diagnostic helperを使うが、入力payloadを正規表現で部分maskして残すより、固定event名・方向・サイズ・HTTP code・例外型だけを記録する。GroupChatの本文出力も検出した場合は同じ診断漏洩の範囲として除去する。

- 合成のsecret/sudo、query token/ticket、cookie、長文、壊れたJSONがログsink/例外/診断exportに出ない。
- 送信するwire payload自体は正しく維持される。redactionがリクエスト内容を壊さない。
- read/write/reconnect/streamingの既存unit testsを保持。ネットワーク実装を別物へ置換しない。
- version/ID/署名/DB schema/navigationは変更しない。
- forkの品質ゲートを実行し、既存Windows失敗が残るなら明記して解消PRと対応付ける。全成功したようにmerge-readyとしない。
- 実Hermesの認証情報を使う疎通検証は、この漏洩対策後に行う。

## 5. Transportとcapabilityの契約案

最初のinterfaceはreadとevent観測から始め、後でtyped writeを加える。endpoint表やJSON-RPC文字列をdomain modelへ流出させない。

```text
ConnectionScope = server identity + profile identity + connection generation
SessionRef = saved session ID + runtime session ID（混同禁止）
RunRef = transport + server run ID（存在するとき）+ local tracking ID
AttentionRef = scope + session/run + server request ID + question ID（必要時）
CapabilityState = Unknown | Unsupported | Supported | Blocked(reason)
Submission = Accepted | Rejected | Draft | UnknownExecutionState
```

`DashboardRpcTransport` はauth/store/session mapping/replayを既存objectへ委譲する。同じserverでもDashboard認証cookieとAPI Server Bearerを共用しない。引き継ぐ必要のないsecretをdomainへ保持しない。

`ApiServerTransport` はPhase 1の必須live経路にしない。read-only discoveryとfixtureで契約を作り、runs/session continuity/approvalの結合検証が通る版でのみlive writeを実装する。API Serverへ接続しただけでDashboard用管理画面をsupportedと表示しない。

advertised/observed/verifiedを区別する。現TUI gateway.capabilitiesは包括一覧でなく、gateway.readyも全機能広告ではない。実在read probe以外の推測は避ける。Serverの未確認endpointを新設したように書かない。

writeの再試行は、既存WS request IDとサーバーidempotency keyを同一視しない。API Serverの `runs_idempotency.supported/durable/retention_seconds` が検証できる場合のみ、その保持期間・scope内で同一要求を照合する。capabilityがなければ結果不明を保持して人間の再確認へ戻す。

RPC→Run正規化はsessionの実行観測を表す。永続Run ID・全Run一覧がない場合、クライアントで架空のserver IDや全履歴を作らない。Queued、Running、NeedsInput、WaitingApproval、Completed、Failed、Canceledに加えUnknown/Reconnectingをモデル化する。

## 6. データ・画面の非破壊移行

1. `:app` を維持し、新しいpackageを少数追加。Hilt/Gradle構成の全面変更をしない。
2. registryは既存ALL_SCREENSをLegacy contributionとして包む。最初の変更ではroute意味を変えない。
3. role導入時だけNavigationControllerのChat固定rootを解消する。UI callbackは常にcontrollerを通す。
4. 4-tabへの移行後もAdvancedから既存27画面と全subpageへ到達可能にする。旧authフロー・gesture・通知Intentは維持する。
5. UI復元用データにsecretを入れない。通知RequestRefは認証完了まで保持して一度だけ消費。古いprofile/要求へ自動返答しない。
6. DBの新tableは既存schema v6からの明示migrationで追加。既存chat内容・timestamp・tool_call_id/display_kindを保持。server/profile scopeを新cache keyに含める。既存rowを推測で別serverへ帰属させない。
7. draftは暗号化し、再接続後に送信/編集/破棄を選ぶ。expiry後の承認レスポンスをdraftとして自動復活させない。
8. applicationId、署名、既存versionCodeを確認して上書き更新。インストール失敗をアンインストールやDB消去で回避しない。既存端末アプリへのdowngradeも行わない。

UI feature flagを設けるなら、旧shellへ戻せても安全性制約は解除されない設計にする。DB migrationを入れた後の単純APK downgradeはrollback手順にしない。

## 7. 最小4-tab導入の受入マトリクス

| 検証 | 維持・追加する内容 |
|---|---|
| Chat | 同じsession、streaming 33ms程度のbuffer、scroll follow/unfollow、添付、group chat、batch clarification |
| Navigation | 全27 registry画面、auth、6 settings subpage、3種類のparameterized detail、Back/dedup/gesture |
| Recreation | 各タブ、Advanced drill-down、送信前draft、接続前通知pendingの再生成 |
| Attention | 二つのカードのうち古い方を押す、逆順ACK、要求失効、再接続、別profile、missing request ID、resolved=0 |
| Reconnect | 元のsocket lifecycle、seq重複/順序逆転、truncated、epoch変化、server再起動、auth失効、no-auto-send |
| UI状態 | Today/Activity/Appsのempty/loading/offline/error/content、Unknown state、unsupported理由 |
| Accessibility | 48dp、TalkBackで操作/対象を識別、色だけに依存しない、font拡大、日本語折返し、IME操作 |
| Device | 同署名上書き、DB/設定保持、通知から同session、外出先Tailscaleの接続（後の実機gate） |

新たに作る画面のstate testだけで、既存Chat/Advancedへの到達性試験を代替しない。

## 8. Mini App foundationの制約

manifestの専用Json decoderを使い、camelCaseとSchemaのadditionalProperties方針を揃える。wire contractは `schemas/miniapp-manifest.schema.json`。native/sdui/webのsealed entry、default-deny permission、schemaVersion/minHostVersion、localization、重複ID、登録済みfeatureを検証する。

URLはformat検証だけでなく、scheme/userinfo/origin/port/path prefix/redirect先を実行時にも検査する。許可済みoriginへresolveした相対URLだけをfetchし、秘密値をquery/JS/MiniAppへ配布しない。このfetch/enforcement実装自体はPhase 3以降で、Phase 1はvalidatorとnegative fixturesに留める。

3 manifestがparseできてもCareer等の業務アプリが実装されたとは扱わない。未登録Native featureは「未提供」、未実装SDUI/Webは「未対応」とし、Fakeのライブ表示をしない。

## 9. 品質ゲートと停止点

コードPRではforkのCIと同じktlint 1.8.0を利用し、`ktlintCheck`、`checkColorLiterals`、`testDebugUnitTest`、`lintDebug`、`assembleDebug`、`compileReleaseKotlin` を実行。CIの `test` はrelease variantも含むため別途CI結果を確認する。UI変更ではAPI 34 ATDの `connectedDebugAndroidTest` を含め、ci-summaryだけを信頼しない。

本監査で見つかった既存失敗はAUDIT_REPORTの結果表を基準にする。テスト成功のためにHOMEの意味を偽装したり、color guardの除外範囲を全体へ拡大したりしない。合格した確認を新たな理由なく繰り返さない。

明示停止点:

- **今**: PR 00の文書差分・解消した矛盾・未確認事項を報告して停止。コードPR/Task 01は未承認。
- contract不明: Unknown/disabledとFakeで検証し、live writeを停止。
- serverが毎回承認を保証できない: 任意ツールRunやMiniApp external writeを有効化しない。Android UIだけで保証したと報告しない。
- data保持不能: 元DBと鍵を保持して端末更新を停止。削除・再作成しない。
- Phase 1完了: 品質ゲートと対応範囲を報告して停止。Phase 2～6は別依頼。

## 10. 今後必要になる入力

forkのowner/repoは確認できたため聞き直さない。名称・色・domainアプリ順序は最初のPRを止めない。

実Hermes検証前にだけ、実稼働version/commit、LAN/Tailscaleの接続先と認証方式、安全なtest profileを確認する。secret本文を文書やチャットへ転記しない。実機更新前にだけ、対象端末、現在のアプリ署名/version/schemaと鍵の連続性を確認する。APIや端末がなくても文書・pure model・Fake・registryの作業は可能。

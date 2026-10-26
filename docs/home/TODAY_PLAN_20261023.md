# ホーム「今日やること」候補

2026-10-23。ホームで「今日何をすべきか」が分かるようにする。
一橋大学のmanaba未提出課題と、メールから読み取った就活の締切を主な情報源にする。

本番サーバー0.1.9 / Android統合版v52。実LLM・Gmail検索4回・本文17通の取得とキャッシュ保存、
同じcompleted receiptからの12件の保存復旧と本番plan GETを確認済み。manabaはauth_requiredで一覧は未取得。
補足欄verification_notesによる保存失敗を修正し、新たな収集/モデル再実行なしで同じrequestをcompleteへ進めた。
ローカルと実ホスト53試験・独立17試験成功。同時実行上限8は新Gatewayでも確認済み。
v52はClaudeの変更とホームを含む現作業ツリー全体から作成し、APK/Release Kotlin/lint/色チェック成功。
976入力ファイルのビルド中変更0、従来と同じ署名。全体unitは2358件中2356件成功。
失敗2件は旧履歴fixtureの組立不整合で修正し、対象の履歴/指示/ホーム15件が再検証成功。
manabaは専用初回ログイン待ち。実端末は未接続で、APKの端末更新・表示確認も未実施。

LLMが1日1回、必要な検索・読取・比較・優先順位の判断を行う。
固定のメールキーワードや本文の正規表現分類は使わない。
前回取得したメール・manabaの情報と前回の判断を保存し、次回がキャッシュを使えるようにした。
ホームを開いた日に自動確認し、手動更新も可能。朝定時のサーバー実行はまだ含まない。

## 表示

既存のMaterial 3、テーマ、`OverviewHeading`、ボタンを共有する。
余計な枠を増やさず、見出しと行動のリストを主役にする。
日付と今日の対応を先頭に置き、従来のApp提供情報はその下に残す。
各行に、具体的な行動・今日する理由・期限・元の情報への入口・完了チェックを置く。
設定と完了項目の開閉は `AnimatedVisibility` を使う。

## 実装範囲

- [Androidの一覧](../../app/src/main/java/com/m57/hermescontrol/home/TodayPlanPanel.kt)
- [キャッシュ・接続単位の状態](../../app/src/main/java/com/m57/hermescontrol/home/TodayPlanState.kt)
- [日次エージェントと接続条件](../../server/today_home/README.md)
- [LLMの読取ツール](../../server/today_home/today_core/agent_tools.py)
- [資料・判断・完了状態の保存](../../server/today_home/today_core/store.py)

アカウントやプロファイルは本人が選んだ範囲に限定する。
LLMに渡すのは読取とキャッシュ用のツールで、外部送信・提出ツールは追加しない。
キャッシュはホストのプロファイル専用DBに保持する。
manabaの取得では一覧だけを開き、小テストの開始画面へ進まない。
認証切れ・部分取得・前回情報を区別する。

## 検証

- Python 47件成功。任意検索の引渡し、取得内容の再起動後の再利用、失敗時のキャッシュ保持、
  ソース変更時の分離、日付境界、同一処理の重複防止、API認証、安定キーによる完了保持、
  模擬LLMを使ったAPIからの資料保存・計画反映を確認した。
- Androidの対象unit test 15件成功。
- 専用の新規エミュレーターで画面テスト4件成功。明暗テーマ、文字サイズ200%、
  完了操作、未確認状態の表示を確認した。画面は合成データ。
- Debug APK、Release Kotlin compilation、色リテラルチェック成功。
- Android `lintDebug` 成功。
- Kotlinは変更対象のHome部品・テストをktlintで整形した。

実メール取得と実LLMの12件提案は確認済み。ログイン後の課題一覧と提案内容の判断品質は未検証。
本番は0.1.9の登録・標準認証API・実LLM/Gmail・12件の保存復旧を確認済み。物理端末の更新は未実施。
APKは現在の作業ツリー全体から作成しており、既存の未コミット変更も含む。
本番反映前にその範囲、既存の認証・データ・署名を確認する。

## 更新用APK

2026-10-23の現行ローカル配布記録はv50まで。今回の更新候補にversionCode 51 /
versionName 1.0.51-localを使用する。Gradleのプロセス引数で指定し、共有の既定値は変更しない。
最初のversionCode 1 / 1.0-devの検証APKはレビュー専用で、更新配信には使用しない。

## 実ホストレビュー後の修正

0.1.0の反映を止め、0.1.1へ修正した。実ホストのメール検索は配列を返すため、
辞書形式に加えて配列も扱う。既知の空結果 `No messages found.` だけを空検索へ変換し、
その他のJSON解析失敗は失敗のまま保持する。検索件数上限への到達は部分確認とする。

狭い検索や取得失敗で前回項目を消さず、未確認として残す。今回取得した根拠を伴う
LLMの明示的な `resolved` 判定だけを一覧から外し、判断理由を監査記録に保存する。
資料ごとの取得時刻と取得runを保持し、キャッシュを読んだだけでは最新にしない。
旧DBへの列追加と記録保持、項目省略、根拠の鮮度、解決判定を回帰テストした。
Androidにも資料の取得時刻を表示し、一部未確認の場合に全件を最新扱いしない。

修正版もunit 15件、専用エミュレーター画面4件、Debug APK、Release Kotlin、lint、
色リテラルチェックが成功。versionCode 51 / 1.0.51-local、署名SHA256
`8561de18c9143b61a26d970ec28c30a20f4260ffba6879eab8250badc3f3524b`。
APK SHA256 `00b15907c7f742643a96c0613cea14226876e70de7f676108e0fbaa360ce9a7e`。

本人の承認後、Slack #hermes-broの[反映スレッド](https://chatgpt-lll7864.slack.com/archives/C0C74STS7D4/p1792725018486459)
で実ホストレビュー・安全な反映・実データ確認を依頼した。現時点で反映成功の証拠は未受領。

続く独立レビューを受け、サーバー候補を0.1.2にした。検索8回・本文80回・manaba2回の
上限に達した際は対象情報源を部分確認とする。解決イベントを現在のタスク状態と分離し、
当時の根拠本文・取得run・解決run・時刻・理由を保持する。資料の上書きやタスクの再登場で
履歴を消さない。日付変更時には取得日を使い個別項目もstaleにする。追加回帰試験も成功。
Android側はv51のままで、サーバーJSONの実効的なstaleを既存表示へ渡す。

実DashboardのPython 3.14.7環境でも0.1.2の29件が成功したとの報告を受領。
Chromiumは既存Playwright 1.62.0に対応する正規のユーザーキャッシュへ追加され、
sandbox有効の起動も確認された。0.1.3では収集と初回ログイン双方で
`chromium_sandbox=True` を明示した。Python29件再成功。APKはv51のまま。
manabaの専用ログイン状態は未作成。本人に対象Gmailを確認中で実収集は無効。
本番有効化時のプラグイン再読込の影響をHermesが調査している。

0.1.3はdefaultプロファイルの本番plugins/today_homeへ配置・正規enable済みとの報告を受領。
原本15ファイルと配置ハッシュ一致、6ツール登録、既存PID/開始時刻とプラグインコードは維持。
ただし本番native-owner GETは候補独自ガードが標準principalを受けず401だった。
0.1.4はコアの `admitted_for_sensitive_route(request)` に認証判断を統一し、
cross-origin書込拒否を維持。helper不在は503で拒否する。ローカル31試験成功。
標準ガード許可時のsession不要、拒否時の偽ヘッダ無効、helper不在、コア拒否の伝播を確認。
コアの実ガードを使う本番GETと実LLM処理はHermesへ依頼中で、ローカルstub試験と区別する。
本人がGmail対象を指定し、実Google profileとの一致確認が成功したとの報告を受領。
アドレス・メール本文・認証情報はこの記録には含めない。

0.1.4は実ホストで31試験・6ツール登録に成功。実標準ガードと一回限りのチケットを使う
隔離HTTP試験でnative-owner GET 200、未認証/偽認証401、cross-origin/profile不一致403、
GET後の資料・タスク・収集要求0件との報告を受領した。配置15ファイルは原本と一致。
正規のターン終了待ちreloadが `state=draining` で受理され、checkpointからの再開を依頼した。
切替後の本番GETと実LLMによる日次処理はまだ未確認で、上記隔離試験と区別する。

切替後Gatewayが新PIDで復帰し、0.1.4登録・本番native-owner settings/plan GET 200、
未認証拒否とcross-origin書込拒否、GET後の収集要求/資料/タスク0件を確認したとの報告を受領。
指定Gmailの本人一致を再確認して収集設定を保存し、checkpointと同じ実行IDで日次処理を
一度だけ要求する段階へ進んだ。通常Mobileセッション・物理端末表示の検証とは区別する。

初回の受理済みrequest_id `verify-v014-20261023-once` は `failed` で終端し、子CLIも終了。
既存セッションのモデルAPI/ツール/メッセージ、資料キャッシュはすべて0件との報告を受領。
同じIDで要求行が1件のままであることも確認された。実メール0件の調査結果ではなく、
調査開始前の起動不具合としてセッション初期化・実行枠・接続経路をHermesが切り分け中。
自動リプレイや別IDでの再起動は行っていない。

原因はclassic CLIが1枠を取り、Gatewayの実行が別枠を取ることで上限2/2に達したこと。
標準receiptでterminal/failedが確認され、拒否通知のActiveSessionRefusalコピー失敗も報告された。
加えてHERMES_TODAY_RUNを子CLIへ設定してもGatewayの登録済みhandlerへ届かないことが判明。

0.1.5候補では標準Gatewayクライアントの段階的RPCへ変更した。受理時のtoken/設定世代/期限、
初期セッションID2種、input/admission IDを永続化し、コアのhandler metadataで日次権限を照合する。
run別上限も永続化し、設定ABA・旧worker書込・期限切れ・終端後を拒否する。
接続喪失は既知receiptだけ1回読み直し、送信ack喪失/unknown/時間切れでは読取を失効させる。
標準SDKを模擬した42試験に、実登録handler→資料保存→plan反映、切断回復、再送防止、
実行枠拒否、時間上限、owner不在、イベントqueue消費と権限の境界を含む。
0.1.5 ZIPはownerのみのDriveへ保存し、同じSlackスレッドへ反映・隔離レビューを依頼済み。

0.1.5の実ホスト42試験後、独立レビューで設定予約後の再読込、旧workerの終端書込競合、
再利用request_idと資料鮮度の混同が見つかった。0.1.7では予約と同じトランザクションで凍結設定を
返し、終端書込をtoken条件とSQLite writer transactionで保護し、資料の鮮度を一意run_tokenで判断する。
旧キャッシュの本文・取得時刻は移行で保持するが、新しい実行の再取得済みとは扱わない。
SQLite writer競合、同一ID再利用、設定失効と進行中読取の回帰試験を追加し、ローカル47件成功。

0.1.7は実ホストでも47件成功、サイズ・SHA256一致、17ファイルの検査成功との報告を受領。
実コアの隔離認証ではnative GET 200、未認証/偽認証401、cross-origin/profile不一致403、収集0件。
本番DBの非公開コピーで既存行を保ったrun_token列追加に成功。本番は0.1.4のまま、
配置の漂移なし、プラグイン/設定/SQLiteバックアップ済み。3修正の独立レビュー中。
固定ID verify-v015-20261023-onceは未送信で引継ぎ済み。実データ調査成功とは区別する。

実行枠はCLI・desktop・TUI・Gatewayが共有する開いているセッションの上限で、待機中も数える。
現在の実効上限2は固定仕様ではなくmax_concurrent_sessions設定による。上限変更・他セッション停止は行っていない。

3修正の独立レビューと再実行が成功し、defaultのplugins/today_homeへ0.1.7を配置したとの報告を受領。
17ファイルは検証済みZIPとハッシュ一致。0.1.4・設定・DBの退避を保持し、設定変更はなし。
切替直前の稼働処理はHermesの現ターンのみで、正規reloadはdraining/restart_waitを受理。
別Dashboard・workerは元PIDで生存。ターン終了後、同じcheckpointから本番確認を続けるよう依頼した。

0.1.7の実処理はAPI7回・ツール26回、admission1件でterminal/completed。Gmailはpartial、
manabaはauth_required。保存0件だがメール17通を保持し、同日自動重複抑止と日次実行枠の解放を確認。
0.1.8では未知タスク欄や根拠の検証を維持し、既知の補足だけを除外する。
ホスト専用の保存復旧は同じfailed request/token/設定/terminal receipt/最新requestをトランザクション内で
照合する。runningへの復帰、読取権限再付与、新規モデル/メール処理を行わない。52試験成功。

本人の「増やせ」を受け、同時実行上限を2から8へ正規設定で更新済みとの報告を受領。
他設定の変更なし、退避済み。正規reload後の新Gateway PID2258192で実効上限8を確認済み。
本人の「Claudeの変更もまとめてAPK」を受け、v52を作成した。新しいhermes.native/2に必要な
mobile_display0.5.0はローカル25試験中22成功・実コア用3skip、実ホストの互換対応を依頼済み。
実ホストは既に0.5.0で候補8ファイルと一致し、実コア登録/hookでhermes.native/1と/2の両方を確認。
同等の配置なので再反映は行っていない。today_home0.1.8は実ホスト52試験、本番DBの非公開コピーで
同じreceiptから12件保存・途中失敗rollback・読取失効維持・二重復旧拒否に成功。独立レビュー待ち。

統合APK v52 / 1.0.52-local / com.m57.hermescontrolは46,572,334 bytes、SHA256
`584cb8e44f2b9cbc98e43cc9b3728645871c42288bd035e6038681899392b634`。
apksigner v2検証成功、署名は従来と同じ`8561de18c9143b61a26d970ec28c30a20f4260ffba6879eab8250badc3f3524b`。
assembleDebug/compileReleaseKotlin/lintDebug/checkColorLiterals成功。実端末へのインストールは未実施。
RichAnswerHistoryTestの2件は現行指示から過去形式を組み立てて不正な二重終端を作っていたため、
既存の旧版resourceを使うよう修正し、assertは維持。対象15件と当該ファイルのktlint成功。
APK作成後の変更はこのテストfixtureのみで、APKに入るコードの変更はなし。

0.1.8の独立レビューでexecution_generationの欠落同士・整数とbool/floatの比較を拒否できない
問題が見つかり、本番反映は保留された。0.1.9はwriter transaction内で両側の存在と
type(value) is intを確認してから一致を照合する。欠落/欠落・1/true・7/7.0・true/1・7.0/7の
拒否と状態不変を追加し、53試験成功。真正な標準receiptを無改変で取得・引渡しする保証は
ホスト管理者の責務であり、元出力のハッシュ認証を実装したものではない。再レビューを依頼済み。
0.1.9は実依存環境でも53試験成功。真正な既存receiptとの一致、非公開DBコピーでの12件保存・
rollback・読取失効維持・二重復旧拒否を確認したとの報告を受領。独立レビュー中で本番は未変更。
続く同梱53件・独立17件の成功後、対象18ファイルの0.1.9配置が完了。旧版・設定・DBは退避済み、
設定と他プラグインは不変。正規reloadはdraining/restart_waitで受理され、切替後の登録・本番GET・
同じ完了receiptによる1回の保存復旧をcheckpointから継続するよう依頼した。保存復旧はまだ未確認。

17:12 JST、新Gateway PID2262764でtoday_home0.1.9/6ツール/errorなし、実効上限8を確認。
本番native-owner settings/plan GET 200、未認証401、GETによる収集・DB変更なし。
同じ真正receipt/request/tokenによる1回の復旧でfailedからcompleteとなり、本番plan GETは12件。
coverageはGmail partial/manaba auth_required、cache17通の内容・admission1件/API7回/ツール26回は不変。
読取権限の再付与なし、二重復旧拒否とその前後のDB不変も確認。新しいモデル/メール処理は行っていない。
残る本人対応はmanaba専用初回ログイン。実機表示・v52端末更新は未実施で、APK作成と区別する。

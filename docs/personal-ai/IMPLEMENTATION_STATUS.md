# Personal AI Shell Phase 1 実装状態

更新: 2026-09-06。作業branch: `feat/personal-ai-shell-phase1`。
引継ぎHEAD: `c9384043ebbbda71ae9315dab5f42837de9aa54d`。

## 今回の作業範囲

ユーザーの2026-09-06の依頼により、従来のPR00/PR02終了時の停止とTask 01以降の
未承認という作業範囲を更新した。PR01〜11の実装・修正・自己レビュー・テスト・
小さなローカルコミット・統合検証を依存順に続行する。
過去の報告書は当時の記録として保持する。安全・データ保護・秘密値保護は継続し、
push・merge・PR公開・実環境適用・個人の実端末へのインストール・Phase 2は実施しない。
監査SHAは比較点でありreset先ではない。単一`:app`、手動DI、既存Room 3を維持する。

## 進捗

| 単位 | 状態 / コミット | 検証・自己レビュー / 次の条件 |
|---|---|---|
| PR00 | 完了 `33f69be` | 文書整合確認済み。PR00_REPORT参照 |
| PR02 | 実装済み `c938404` | 追加6回帰成功、自己/独立レビュー済み。既存unit 2件・color guardはPR01、Release試験タスクは未提供。PR02_REPORT参照 |
| PR01 | 完了 `8f48796` / `144b885` / `4f618ee` | Debug/Release各1,281件 failure/error/skip 0。ktlint/color成功。自己レビューで検査範囲と同期を確認 |
| PR03 | 完了 `5136a02` | 関連unit Debug/Release各46件、SQLCipher移行・保全・起動停止UIのemulator 3件成功 |
| PR04 | 完了 `7bf9270` | 単一socketの固定scope/event/read境界。既存WebSocket 54件、新contract 6件。自己レビューで固定所属とno-queueを確認 |
| PR05 | 完了 `ad29751` | scopeごとのread probe、Unknown/Blocked区別、API専用offline decoder。追加4件。live writeはdisabled |
| PR06a | 実装済み `b6d161d` / `8491e8e` / `39116b9` / `ffef902` / `51c1379` / `c7fa9a1` / `b1d55a9` | queue禁止・暗号化draft・固定scope submit・Chat下書きUI・通知返信を検証。commandのscope・下書き復元の本文/queue保持も確認。全体統合ゲートはPR11で実施 |
| PR06b | 基盤実装中 | 固定要求の状態管理・typed応答・once/deny通信制約を検証。Chat/UI・replay・通知への接続は未完了 |
| PR07〜08b | 未着手 | 既存27画面を保持してregistry/復元/4-tabを統合 |
| PR09a〜09b | 未着手 | 実イベントの観測範囲と正確なAttention通知 |
| PR10 | 未着手 | strict manifest parser/意味検証、静的表示のみ |
| PR11 | 未着手 | 全品質ゲート、利用可能なemulator、互換表/画面表/APK記録 |

現時点でPhase 1完成・本番利用可能・安全性認定済み・merge-readyとは扱わない。
実Hermesの契約・実機データ保持の確認は未実施。未確認機能はUnknown/disabledを維持する。

## PR01の検証と根拠

- `checkColorLiterals`: source相対パスを`invariantSeparatorsPath`に統一。非themeの
  16進Color/名前付きColorを一時fixtureで両方検出し、fixture除去後も成功した。
  検査範囲やtheme以外の除外を拡大していない。
- `GatewayFileClient`: `~`は端末HOMEへ置換せずGatewayへ渡す。採用監査のserver source
  `hermes_cli/web_server_files.py::_resolve_managed_path`が`Path(text).expanduser()`する契約を確認。
  HOMEをテスト用に偽装していない。未知ユーザーの`~name`を拒否し、absolute pathとURL encodingも検査。
- seq重複除去: replayなしSharedFlowへ非同期collectorの購読開始前に送信できるtest race。
  `UNDISPATCHED`の購読と受信latch、thread-safe list、後続Bの順序barrierへ置換。
  expected `[A]` / `[A,B]`とwatermark確認を保持。製品のseq処理を変更していない。
- Release unit test: [AGP 9の既定値変更](https://developer.android.com/build/releases/agp-9-0-0-release-notes)
  に従い`android.onlyEnableUnitTestForTheTestedBuildType=false`を明示。依存更新なし。
- 最初の追加URL期待値では`~`と`%7E`の差を検出した。仕様どおりのpercent encodingを
  正確にassertするよう修正し、その後の全Debug/Releaseが成功。テスト削除/skip/握り潰しなし。
- 証跡: checkout外`../phase1-validation/pr01-debug-results` / `pr01-release-results`と
  `../pr01-<task>.log`。各variant 1,281件、既存2失敗・新規失敗とも0。
  PR02の6回帰も両variantで成功。Linux CIと実接続は今回実行していない。
- 自己レビュー: build設定、remote path修正、test同期を3コミットに分離。
  署名/ID/version/依存/通信の送信仕様は変更していない。

## PR03の検証と制約

- plaintext/短いDB/残存journal/非ファイルを検出して保全停止し、自動deleteと
  `fallbackToDestructiveMigration`を除去。SQLCipherでversionを確認し、未知versionは
  Roomのjournal変更・移行前に停止する。既存のv2→v6 migration・schema 6を維持した。
- 既存DBの鍵欠落、鍵の復号/形式不正で新しいDB鍵を発行しない。新規DBの鍵は
  同期commit成功後だけ使用する。起動時は既存DB/暗号化preferencesがある場合の
  Keystore alias欠落も、MasterKeysによる生成前に拒否する。
- IOでRoomの実open・migrationを完了するまでMainNavigationと通常の接続/通知lifecycleを
  待機させる。失敗は本文・例外詳細を出さず、データを保持した停止画面（日本語/英語）にする。
- Debug/Release関連unit各46件成功（DB保護9、既存migration 4、AuthManager 33）。
  新旧鍵の同一性、書込拒否、commit失敗、元ファイルのbyte保持を検査した。
- 専用Android 36 emulator `Codex_Personal_AI_Phase1` / `emulator-5580`で
  `connectedDebugAndroidTest`の対象3件成功。保存済みschema v2〜v6から実SQLCipher
  fixtureを作り、production Room builderで本文・timestamp・reasoning・tool_call_id・
  display_kind等の保持を確認。鍵不一致・version 99はbyte不変で停止し、元の鍵で本文を再読取。
  plaintext fixtureでは起動callbackが進まず、Composeの保全メッセージが実表示され、元byteを保持。
- ktlintCheck / lintDebug / compileReleaseKotlin成功。Debug APKもinstrumentation用に生成。
  手動起動で既存Landingへ到達。画面証跡はcheckout外`../phase1-validation/pr03-startup.png`。
- 初回のコンパイルで鍵alias参照名、fixtureでv2のindices省略、UI試験でComposeに対する
  TextView matcherの不適合を検出して修正した。上記は修正後の結果であり、失敗をskipしていない。
- 自己レビュー: destructive fallback/DB delete除去、鍵書込条件、lazy Room openの強制、
  例外詳細の非表示、UI開始前の停止を確認。新しい依存・DB version・署名変更はない。
  実端末のDB/鍵、OS権限故障の実機再現、旧plaintextの変換・鍵復旧操作は未実施。
  plaintext/失われた鍵の復旧は元データと鍵を保持した別手順が必要で、自動初期化しない。
- この時点の全体unit結果はPR01時のもの。Phase 1統合後に全体ゲートを再実行する。

## PR04〜05の検証と制約

- DashboardRpcTransportは既存socketを観測し、listenerで固定したserver/profile/process/generationを
  付ける。本文/token/任意payloadを新しい観測モデルへコピーしない。saved/runtime session IDを分離。
  token省略とイベント欠落を区別し、seq stream欠落・replay truncated・epoch変更はUnknownを通知する。
- `session.list` readは既存request ID/pending/timeout解決を使い、scope/URLの変更を検査。
  offline/送信拒否時にqueueや再接続を作らない。probeのtimeoutは5秒。受信後にもscopeを再確認する。
- CapabilityRepositoryは接続世代ごとに証拠を破棄し、read成功した機能だけSupportedにする。
  readyやAPI広告からRun/approval全体の対応を推測しない。API decoderは64KiB/深さ32で制限し、
  advertised URLへアクセスしない。live API writeは常にdisabled。
- 関連unit一式はDebug 63件、Release 64件成功。最後のepoch/depth/5秒timeout追加後は
  対象12件を両variantで再実行して成功。各実行failure/error/skip 0。ktlintCheckと最終差分ktlint成功。
- 初回は既存testのreflection constructor引数変更（10件）と追加testのimport不足を検出。
  scope fixtureとimportを修正し、元のqueue/reconnect/diagnostic期待値を維持して成功させた。
- 証跡: checkout外`../phase1-validation/pr04-05-*-results`、`pr04-05-final-*-results`、同prefixのlog。
  自己レビューでは古いACK/別profileへの所属付け、readの遅延送信、本文ログ、広告URL追従がないことを確認。
  新UIへの手動DI接続はPR08〜09で行う。実Hermesのversion/auth/Run/approval互換は未検証。

## PR06aの途中経過

- 明示したread methodのみ既存のreconnect queueへ入れる。prompt・承認・質問・sudo・secret・
  添付・設定変更・未知methodはoffline/送信拒否時に未送信エラーを返し、自動queueしない。
  `send(true)`後の要求をreplayしない既存条件も維持した。
- Releaseの通信関連57件成功。Debugは最初の57件中、oversized送信拒否でsocketをcancelする
  回帰1件を検出し、サイズ超過時は既存socketを維持する条件へ修正。修正後の対象5件成功。
  自己レビューで別操作の拒否が進行中の応答待ちを消す問題を修正し、追加ケースとoffline拒否を
  Debug/Release各2件で成功確認した。追加テストのprivate setter直接参照による初回compile失敗も修正済み。
- 最終4ファイルktlint、diff check成功。証跡はcheckout外`../phase1-validation/pr06a-*`。
  送信結果不明の表示、Chat/通知の固定scope submitは未実装なのでPR06a完了とはしない。
- `8491e8e`: SQLCipher Room v7へ`outgoing_drafts`を追加する明示的6→7 migration。
  scope・saved/runtime session・本文・添付情報・DRAFT/UNKNOWNを暗号化DB内へ保存する。
  世代/process変更後も同じserver/profileの記録だけ復元し、別profileからの削除を拒否。
  repository自身に送信・再試行の機能はなく、破損記録を黙って無視しない。
- 保存関連unitはDebug/Release各3件成功。専用emulatorのDB保全4件成功。
  v2〜v6からv7への既存本文等の保持、v7再openでUNKNOWNと添付を保持、別profile削除拒否、
  鍵不一致/version 99のbyte保全、起動停止UIを検証した。初回instrumentation compileで
  RoomDatabaseへの`use`非対応を検出し、既存方式の`try/finally`へ修正後に4件成功。
  最終対象ktlint/diff check成功。schema 7はKSP生成。Chat/通知/UIへの接続はまだ行っていない。
- `39116b9`: 既存socket/request counter/pending/timeoutを利用する固定scope writeと、
  prompt/添付/resumeのtyped境界を追加。socket受理と確認ACKを区別し、未送信/結果不明を返す。
  timeout・scope変更・不明な結果・RPCエラーから別methodやprotocolへ再送しない。
  sourceで確認したstreaming/queued/redirected/steered、添付成功フラグ・file参照だけを確認済みとする。
  新しいAPI Server write、Run ID、idempotency keyは追加していない。
- 関連9件をDebug/Releaseで成功確認後、ACKを旧Chatエラー処理へ二重通知しない調整を
  含む6件、拒否時の既存応答待ち保持を含む3件を両variantで成功確認。最終7ファイルktlint/diff check成功。
  逆順ACK、別scope/不正method、送信拒否後replayなし、timeout後replayなしを実socket境界で検査した。
  設定scopeはserver設定の一つのsnapshotを使い、接続やticket発行なしに読める。
  この境界のChat/通知への配線と画面上の結果不明表示は引き続き残作業。
- `ffef902`: legacy session create/resume/branchを含むwriteも、登録callback後のsocket送信直前に
  接続scopeとprofileを再照合する。明示的に別profileを渡したwriteも拒否し、queueしない。
  WS/policy/typed関連Debug/Release各66件成功。固定scope fixtureを使い、元の送信検査を維持した。
- Chatの通常prompt・添付・redirectは、暗号化DRAFT保存後に固定scopeへ送信する。
  最初の通信直前にUNKNOWNを保存し、確認ACK後だけ履歴cacheへ保存して下書きを削除する。
  添付読取失敗では本文だけ送らず入力を復元。添付受理後のprompt拒否はUNKNOWNを保持する。
  切断・画面再生成・再接続では自動送信しない。UNKNOWNには下書き復元ボタンを出さない。
  subagent停止/修正は要求しただけで完了状態へ変えず、実イベントの状態を保つ。
- ReleaseのChat関連173件成功（failure/error/skip 0）。Debugは173件中1件の保存障害表示を修正し、
  修正後の関連9件成功。初期の旧動作期待5件も、awaited RPC errorのfixture、添付保持、
  session切替前の未送信保持、subagentの実状態を検査する形に変更してRelease一式で成功した。
  新規ケースはUNKNOWN先行保存、再生成/再接続で再送なし、別profile拒否、部分添付、
  cache障害、未追跡の遅延error、観測済みstreaming状態の保持を含む。
- 専用AVD `Codex_Personal_AI_Phase1` で下書きUI 2件成功。日本語/文字サイズ200%で
  状態と元会話ボタンへスクロールで到達し、送信確認中は復元/削除不可を確認。
  最初はComposeのLocalResourcesへ日本語設定が届かず失敗し、test側のlocale設定を修正した。
  Debug APK/AndroidTest APK buildと差分9 Kotlinファイルのktlint成功。
  証跡はcheckout外`../phase1-validation/pr06a-chat-release-results`、`pr06a-chat-focused-debug.xml`、
  `pr06a-draft-ui-locale.log`、`pr06a-drafts-ja-large.png`。画像は合成データのcomponent試験。
  実端末、実Hermes、TalkBackの手動読み上げ、通知返信、全体結合ゲートは未確認。
- 通知返信は元のscopeをIntentのextraとidentityへ固定し、別profile/serverでは元の接続先へ
  DRAFTを保存するだけにした。毎回typed resumeで保存済みIDを解決し、UNKNOWN先行保存後に
  promptを一度送る。確認ACK後だけcacheと確認表示を更新する。旧通知などscope不明時は送信しない。
  保存不能/結果不明の通知には入力と状態を表示し、再送actionを付けない。通知はPRIVATEのまま。
- ActiveSessionHolderの既存APIを保持し、正確なscopeを持つ照合と、背景返信のID対応32件の上限を追加。
  背景resumeは開いているChatを変更しない。WS通常受信/seq受信/replayすべてでscopeを使って
  保存済みIDを付け、不明なruntime IDを保存済みIDと推測しない。通知表示は同じ接続先なら
  idle切断後も維持し、接続先変更時に抑止。返信時は改めて固定scopeの送信境界で検査する。
- 通知/ID対応/WS完了イベントの29件はDebug/Release各failure/error/skip 0。
  その前のChatを含むDebug一式も成功。表示条件の最終変更後は通知Service 6件を両variantで成功確認。
  初回のreplay側scope nullableによるcompile失敗を修正した。nullable時に他の接続先へ所属付けせずnullを保つ。
  専用AVDでIntentのprofile/generation/sessionによるidentity分離と、結果不明の通知本文/PRIVATE/no-actionの2件成功。
  旧receiverテストの固定sleepをvirtual timeへ置換し、cache成功/失敗/timeout、入力境界を維持した。
  新たに元profile保持、scope欠落、応答待ち前のUNKNOWN、手動の別返信、通知の入力保持を検査した。
  証跡は`../phase1-validation/pr06a-notification-{Debug,Release}-results`、
  `pr06a-notification-display-final.log`、`pr06a-notification-android.log`。
  実通知から実Hermesへの返信、冷起動の最終route、TalkBack、command経由のpromptとPhase 1全体は残作業。

## PR06a コマンド応答の接続先固定

- `command.dispatch`、`slash.exec`、`prompt.btw`を既存socketの`writeScoped`へ移した。
  実行前のscope・会話世代を固定し、切り替え後の結果・エラー・registry missから新しい
  会話へのprompt/alias/fallback/prefillを発生させない。結果処理は会話選択と同じMainで行う。
- 既存120秒timeoutと正確なregistry missだけのslash fallbackを維持した。再接続queueは使わない。
  実際のJSON応答も既存`toAny`で変換し、Mapだけのfakeで隠れていた出力欠落を修正した。
- サイド質問はruntime session確認後だけ送信する。offlineでは質問と未送信の表示を保ち、
  spinnerと自動再送を残さない。通知返信の変更は`c7fa9a1`に分離済み。
- 関連Chat/command/固定scope送信テストはDebug/Release各179件成功。
  追加のoffline表示修正後はSlashCommandDispatchRpcTestを両variantで再検証した。
  初回の新テストでは切替先resume ACKがないfixtureを検出し、実際のACKを返す手順を追加。
  期待値は維持した。途中のDebug全体1,334件ではこのfixtureの1失敗のみだったが、
  これは最終全体ゲート成功という扱いにはしない。
- 証跡: `../pr06a-command-{Debug,Release}-final.log`、
  `../pr06a-command-offline-final.log`、`../phase1-validation/pr06a-command-*-results`。
  ktlintと差分チェック成功。実Hermes接続は実施していない。
## PR06a 下書き復元の送信方法

- 復元した下書きはスラッシュで始まっても本文として送る。`/queue /help`の本文を
  `/help`コマンドとして再解釈することを防ぎ、同じdraft IDをACK後だけ削除する。
- `OutgoingDraft.queued`を既定falseの任意JSON項目として保持する。旧payloadを読めること、
  repository再生成後もtrueが残ることを検証した。Room schema/table変更はない。
  queued draftの手動再送でredirectへ変換しない。旧版へのdowngradeは今回検証していない。
- Debug/Release各13件、failure/error/skip 0。本文のliteral送信・送信1回・queue=true・
  command/redirect呼び出し0・ACK後draft削除を検証。ktlint/差分チェック成功。
  証跡: `../pr06a-draft-restore-final.log`と
  `../phase1-validation/pr06a-draft-restore-{Debug,Release}-results`。
- 直前のコマンド変更は`b1d55a9`。offline表示追加後のcommand classは両variant各21件成功。
  PR06aの実装単位はここまで。Phase 1全体・実接続・最終全品質ゲートの完了は示していない。

## PR06b: 固定要求と応答基盤（途中）

- `AttentionRepository` はscope・runtime/saved session・request ID・question IDをキーに保持する。
  ACK前はSUBMITTING、exactな成功ACK後だけCONFIRMED。期限切れ・古いACK・重複タップを拒否し、
  結果不明はUNKNOWNとして保持する。replayでは状態をリセットしない。未送信だけPENDINGに戻す。
- `HermesAttentionTransport` を既存Dashboard adapterへ追加。承認はonce/denyと`all=false`、
  `resolved=1`だけ成功と解釈。0/複数/不正型を完了へ変換しない。質問はrequest IDとbatch qidを
  固定し、`status=ok`だけその質問のACKと解釈する。render受領ACKは応答と分離する。
- `writeScoped` 自体にも要求ID必須・広範囲承認禁止を追加。既存socket/待機呼出/timeoutを使い、queueしない。
- Debug/Release各13件成功（状態管理6、adapter6、実WSクライアントのsocket fixture1）。
  ktlint対象7ファイルとdiff check成功。実Hermesとのlive通信や画面操作を行った結果ではない。
- この基盤時点では画面未接続。承認画面への接続は下記で実施。質問・横断Needs Youは引き続き未完了。

## PR06b: Chat承認の固定依頼への接続（途中）

- 押したカードのmessage IDから元RequestRefを取得する。最後のカード選択とACK前消去を廃止。
  once/denyのみ表示し、送信中・確認済み・結果不明・保留を表示する。期限未提供は不明、修正未対応は
  理由付きdisabled。操作は48dp以上、依頼元と実行コマンドを省略せず表示する。
- 承認のlive/session.infoはsocketで採取したscopeを使う。resumeは元RPCのscopeを使い、
  pending照会は固定scope/runtimeへ送り、全件をexact identityで取り込む。replayは応答状態をリセットしない。
  古いsocketのイベント、識別情報不足、別scope、offlineでは応答しない。
- render受領ACKと承認応答を別々に扱い、旧unscoped sendからの承認書込も拒否する。
  履歴の重複整理では同じ説明文でも別依頼を統合しない。
- ChatViewModelTest全体はDebug/Release各142件成功。最終差分の承認9件とsocket境界1件は
  両variant各10件成功。ktlint対象7ファイル・diff check成功。
  証跡: `../phase1-validation/pr06b-approval-chat-full-{Debug,Release}.xml`、
  `../phase1-validation/pr06b-approval-final.log`。実Hermes通信・今回の画面実機検証は未実施。
- PR06bはまだ途中。clarificationの固定request/question単位送信、期限切れ、複数依頼表示、
  Today/Activityとの共有、UI検証が残る。PR07〜11・Phase 1最終品質ゲートも未完了。

## PR06b: Chat確認質問の固定依頼への接続

- 確認質問を会話内の複数カードで保持する。押したカードの元scope/runtime/request/question IDへ
  回答し、現在の最後の質問へ読み替えない。旧unscoped送信はWS境界でも拒否する。
- 入力のある質問だけ送信し、質問ごとのACK前は送信中、成功ACK後だけ受付済みとする。
  未送信は次の明示タップで再試行できる。結果不明・期限切れは再送しない。
  辞退も元依頼の未回答質問に限定した明示応答とし、ACK前のUSER履歴追加・カード消去を廃止した。
- 同一request IDの別question IDを区別する。識別情報不足・重複batch qid・別scope・切断中・
  会話切替後の古いカードを拒否する。期限切れはscope/runtime/requestが一致する質問だけ反映し、
  遅いACKで戻さない。replayで状態・入力をリセットしない。
- 選択肢・自由記述・一部回答を維持。操作48dp以上、改行入力、状態・依頼元・期限不明を表示する。
- ChatViewModel全体144件とWS境界1件がDebug/Release各145件成功。
  最終の同一request別qid・未送信再タップ・会話切替を含む質問9件も両variantで成功。
  ktlint対象7ファイル・diff check成功。証跡は `../phase1-validation/pr06b-clarify-*`。
  初回Debugのテスト間例外は、socket fixtureのACK待ちを完結させて解消した。
- PR06bのChat応答実装まで完了。Today/Activityとの共有・通知詳細はPR09で接続する。
  今回の画面操作・実Hermes通信は未実施。PR07〜11と最終品質ゲートは未完了。

## PR07: 既存画面のContribution互換wrapper

- 27画面の既存定義をそのまま保持し、`ScreenRegistry.ALL_SCREENS`を互換入口として継続する。
  順序・label・icon・drawer section・contentは同一インスタンスで維持する。
- 組込画面の安定ID/route/NavKey型を登録する。認証2件・設定詳細6件・詳細画面3件も予約し、
  Navigation.ktの専用entryは変更していない。合計38件を重複検査の対象にする。
- contribution ID・destination ID・route・NavKey型の重複、空の識別子、画面型の不一致を構築時に拒否する。
  同じorderは宣言順を保持し、未知routeを別画面へ読み替えない。単一app・手動登録を維持する。
- Debug/Release両方でContributionRegistryTest 5件と既存NavigationControllerTestが成功。
  ktlint対象5ファイルと差分チェック成功。証跡は `../phase1-validation/pr07-*`。
  新shell・復元・画面操作の検証はPR08以降。画面到達性の最終確認はまだ実施していない。

## PR08a: 画面role・保存可能な履歴・通知待機

- 既存38画面をROOT/ADVANCED/DRILL_DOWN/AUTHに分類。controllerの同一画面連打防止と既存Backを維持する。
- Navigation 3の`rememberNavBackStack`を使用し、認証状態ごとに保存状態を分離する。
  ログアウトした復元では内部画面を表示しない。古いCompositionが新しいcontrollerを切り離さないようにする。
  設計参照: [Android Navigation 3の状態保存](https://developer.android.com/guide/navigation/navigation-3/save-state)。
- 通知のsession ID・スクロール要求・要求番号をActivity saved stateへ保存する。
  処理済み・新しい通知より古いsnapshotを復活させず、未認証中はログインを越えて遷移しない。
  接続復帰は通知先sessionのresumeを優先し、不要なsession.createを呼ばない。
- Debug/Release各31件成功（NavigationController 23、Contribution 5、GatewayReady 3）。
  専用エミュレーター `emulator-5580` の保存状態復元2件成功（詳細引数の復元、logout/login分離）。
  これはStateRestorationTesterによる保存・再生成であり、OSによる実process killや実通知配送の確認ではない。
  ktlint・diff check成功。証跡は `../phase1-validation/pr08a-*`。
- タブ選択・タブごとの保持はPR08bで4タブと合わせて接続する。実Hermes接続・実端末更新は未実施。

## PR08b-1: タブごとの履歴と選択状態

- Today / Chat / Activity / AppsそれぞれにNavigation 3の保存可能な履歴を用意した。
  タブ選択は詳細画面を保持し、通知などからの明示的なChat遷移はChatの先頭を開く。
  同じ詳細への再遷移は既存位置まで戻り、重複画面を積まない。
- 認証されたCompositionから外れると全タブを破棄し、再ログインはTodayから開始する。
- Debug単体テスト3件成功。専用エミュレーターの保存状態テスト4件成功
  （前段2件に加え、選択タブと非表示タブの詳細復元、logout後の全タブ破棄）。
  StateRestorationTesterによる検証であり、OSの実process killではない。
- この段階では状態モデルを追加したのみ。画面・下部バー・controllerへの接続は次の変更で行う。
  証跡: `../phase1-validation/pr08b-shell-state.log` と `pr08b-shell-restore.log`。

### PR08b-2 — Shell controller attachment

- Added guarded shell attachment and synchronous selected-stack routing for tab switches, notification Chat navigation, root reset, and Back. Existing non-shell navigation remains available until the UI migration.
- Verified Debug unit tests: 3 ShellControllerTest and 23 NavigationControllerTest passed (0 failures/errors). New coverage checks consecutive callbacks without recomposition, notification routing with retained Today history, and stale disposal after a replacement attaches.
- Targeted ktlint formatting/check and git diff --check passed. UI attachment and bottom navigation are the next PR08b step; these tests do not claim rendered UI completion.

### PR08b-3 — Four-tab UI and Advanced entry

- Authenticated navigation now starts at Today with Today / Chat / Activity / Apps tabs, separate saved stacks and a per-tab SaveableStateHolder. Chat retains its existing content and Activity-scoped ViewModel; the shared top bar adds Settings / Connection / Profiles / Advanced. Advanced lists all 27 existing screen definitions and keeps their original content and drill-down wiring.
- ShellContribution adds 3 roots plus Advanced; the existing 38 legacy entries and their routes remain covered by registry tests. Authentication attachment explicitly clears the old shell controller binding before disposal can run.
- Root content is deliberately only navigation entry points at this migration step. Today/Activity observation content (PR09) and Apps manifest information (PR10) are not completed here.
- Debug unit checks passed: NavigationControllerTest 23, ShellNavigationTest 3, ContributionRegistryTest 6, ShellControllerTest 4 (including auth attachment). Debug main and instrumentation compilation passed. Dedicated emulator-5580/API 36 ran ShellChromeTest 1 and AppBackStackTest 4: all 5 passed, none skipped. These prove touch navigation/menu and saved-state restoration, not a live-server session or real-device update.
- The shell now hides its bottom bar while IME insets are present so Chat remains the IME owner. This final inset adjustment receives compilation/color checks separately; full Chat keyboard, large-font, screenshots and live-scope limitations remain for PR11 integration checks. No real server/device, push, merge or deployment was used.
- Final inset-adjusted Debug compilation and checkColorLiterals passed; modified Kotlin files received targeted ktlint formatting/check, and git diff --check passed.

### PR09a — Observed Today and Activity

- Added an application-owned RunRepository over the existing read/event transport. It stores at most 100 session observations and 200 timeline events in process memory. It does not create durable Run IDs, connect another socket, or submit work. Session names are loaded only through the scope-checked read boundary.
- Today and Activity now render actual observations, prioritize requests for input/approval, and distinguish loading, offline, reconnecting, authentication, read failure, empty, and unknown state. Both disclose the observation range and process-local retention. A saved session link is enabled only when its saved ID is known; runtime IDs are not passed off as saved IDs.
- Disconnects/gaps preserve the last confirmed execution state with explicit uncertainty. Different destinations clear the previous private history; reconnect generations retain uncertain observations until new events arrive. Duplicate events are ignored while a gap and a real event sharing one ordinal both apply. Known saved IDs survive later tool events.
- Debug compilation and checkColorLiterals passed. RunRepositoryTest: 5 passed, including cross-scope filtering, reconnect, same-ordinal gap/event, retention limits and unavailable reads. Shared AttentionRepository lifecycle tests also passed during integration. Targeted ktlint checks and git diff --check passed. Dedicated emulator-5580/API 36: ObservedActivityTest 2 passed, covering visible offline uncertainty, exact saved-session routing and disabled runtime-only navigation.
- Evidence: `../phase1-validation/pr09a-integrated.log`, `pr09a-gap-events.log`, `pr09a-observation-ui.log` and the copied XML. This is a session observation surface, not a complete mission-control server API. Attention response/details and notification identity are still PR09b; full visual/accessibility/lifecycle integration remains PR11. No live server or real device was used.

### PR09b — Shared Attention details and notification identity

- Application-owned incoming Attention normalization now observes approval and every clarification question even while Chat is not open. Legacy Chat replay uses the same mapper and repository. Metadata enrichment preserves the original response lifecycle and never replaces an existing explanation. Unknown target, impact, permission, reversibility and expiry are displayed as unknown; command matching patterns are not represented as granted permissions.
- Today/Activity and Chat open one detail destination identified by the immutable RequestRef. All approval decisions now pass through the detail view; only once/deny can reach the transport, and explicitly unadvertised choices are disabled. Clarification reuses the existing choice/input controls and preserves its question ID. Activity-scoped response jobs survive tab/configuration changes; confirmation removes a resolved request from Needs You without inventing Run completion.
- Gaps put unresolved requests on hold. Reconnect replay cannot turn an unknown/already-attempted answer into a new pending response. Scope/expiry/duplicate/ACK gates remain in the shared repository and transport. No automatic response or submission was introduced.
- Approval/clarification notifications carry only validated reference metadata. Immutable content intents and notification tags distinguish request/question/scope identities; duplicate notifications reuse the same identity. Notifications have no inline approval/answer action. Pending detail routes survive authentication and saved state, while missing or old-scope requests remain read-only. Cold-start recovery does not fabricate request content or rebind an old request to a new socket.
- Final focused Debug unit run: 43 tests passed (Attention coordinator/repository, normalization, reference decoder, navigation, Run repository, and 18 existing Chat approval/clarification cases). The registry's 6 tests also passed after the new drill-down registration; all 38 legacy entries remain intact, 43 entries total. Dedicated API 36 emulator: initial 11 tests passed for detail UI, notification objects, and saved stacks; after the final replay/layout changes, 7 relevant detail/observation UI tests passed, including 2x font controls and confirmed-request removal. checkColorLiterals and targeted ktlint passed. One instrumentation compile attempt had an invalid test-only import; it was corrected before the passing runs.
- Evidence: `../phase1-validation/pr09b-attention-tests.log`, `pr09b-attention-ui.log`, `pr09b-final-lifecycle.log` and copied final XML. Live-server approval ACK/pending recovery, actual OS process death, TalkBack speech output and real-device update are not claimed by these tests; PR11 records the integration evidence and limits.

### PR10 — Offline Mini App manifest foundation

- Added a dedicated strict camelCase parser/model and typed core/plugin permissions. UTF-8 decoding reports malformed bytes; a 64 KiB / depth-32 guard rejects excessive input and duplicate JSON members (including escaped names) before a JsonObject can overwrite them. Unknown fields, explicit nulls, primitive coercion, missing required fields and unknown entry/icon kinds fail closed.
- Metadata validation covers schema/component versions, Unicode text bounds, IDs, permissions, contribution/keyword duplicates, routes, priorities, offline retention, hash metadata and semantic versions. URL policy is deliberately restrictive: explicit HTTPS origins, no userinfo/query/fragment, no encoded/traversing paths, and SDUI paths within the declaring plugin's mobile namespace. Validation does not verify signatures, grant permissions, or establish a working renderer/backend.
- Native resolution uses explicit compiled NavKeys for existing Chat/Activity features, never a class-name loader. Minimum-host-version checks reject unknown/non-semver versions rather than guessing compatibility. The legacy build version `1.0-dev` is not silently normalized to a supported manifest host version.
- Copied the 3 specification examples byte-for-byte into local assets. All 3 validate with the explicitly test-only Development fixture registration; the live built-in registry correctly rejects the unimplemented Development business feature. Apps validates and displays only the examples' metadata, clearly marked unavailable and ungranted. No enable/install/fetch/renderer/WebView/action execution is present. The declaration index starts disabled and removes all contributions on disable while retaining other registrations.
- Debug compile/color checks passed. Final MiniAppContractTest: 17 tests passed, including the original example byte comparison, invalid members/types/IDs/permissions/paths/origins, native entry rejection, prerelease ordering and disabled contribution removal. Dedicated emulator-5580/API 36: 2 Apps tests passed, including 2x font scrolling and permission review without navigation/activation. Targeted ktlint formatting/check and git diff --check passed; the published JSON Schema was not modified.
- Evidence: `../phase1-validation/pr10-contract-tests.log`, `pr10-final-contract.log`, `pr10-apps-ui.log` and copied XML. This completes the offline foundation only. Final whole-app gates, screenshots and integration limitations are recorded in PR11.

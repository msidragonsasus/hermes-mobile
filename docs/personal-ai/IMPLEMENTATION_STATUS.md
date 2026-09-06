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
| PR06a | 実装中 `b6d161d` / `8491e8e` / `39116b9` / `ffef902` / `51c1379` | queue禁止・暗号化draft・固定scope submit・Chat下書きUI・通知返信を検証。残るcommand経路のscope確認と統合レビューは残作業 |
| PR06b | 未着手 | 固定要求とACK、once/deny、失効と古い応答 |
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

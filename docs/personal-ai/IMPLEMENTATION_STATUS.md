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
| PR03 | 完了（本節と同じstorageコミット） | 関連unit Debug/Release各46件、SQLCipher移行・保全・起動停止UIのemulator 3件成功 |
| PR04〜05 | 未着手 | 単一socketを委譲、scope/event/capabilityを検証 |
| PR06a〜06b | 未着手 | offline write禁止、結果不明、固定要求とACK |
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

# PR02 — 秘密情報のログ抑止

作業日: 2026-09-06

## 作業場所とコミット境界

- Checkout: `E:/OSHIIRE/software/Hermes/hermes-mobile`
- Origin: `https://github.com/msidragonsasus/hermes-mobile.git`
- 作業branch: `fix/sensitive-websocket-diagnostics`
- PR00基準commit: `33f69bef15b4c9b79d904f537917c3b681b2ab1b`
  (`docs: align Personal AI Shell baseline and audit gates`)
- その親: `fd485a69990b358f81d8dd4c12c761e7d2555d24`

開始時は`docs/personal-ai-audit`上に、確認済みPR00のroot文書4件と新規文書30件だけがあった。
追加の無関係な差分は検出されなかった。34文書だけをstageし、パス・拡張子・限定秘密値パターンを
確認してローカルコミットした。そのcommitからPR02 branchを作成し、旧SHA/devへのresetはしていない。
PR00_REPORTの未commit記述は当時の記録であり、今回の保存状況は本節を参照する。

## 修正した経路

パス先頭は `app/src/main/java/com/m57/hermescontrol/`。

| ファイル | 塞いだ診断経路 | 変更後 |
|---|---|---|
| `data/ws/HermesWsClient.kt` | 送信・受信・queue flushの生JSON | 固定方向/eventと文字数のみ。wire文字列は変更しない |
| 同上 | 接続URLのtokenだけ部分mask | URL全体をログへ渡さず、固定の接続開始eventだけ |
| 同上 | ticket mint、parser、socket failureのThrowable引数 | 例外型だけ。message、cause、suppressed、stackを出力しない |
| 同上 | health/replayの例外message、session ID、pending method名 | 固定eventと例外型だけ。任意method/sessionを出力しない |
| `data/ws/EventParser.kt` | 未知event typeの出力（live/replay共通） | 固定の未知event通知のみ |
| `ui/bots/group/GroupChatViewModel.kt` | 送信全文・完了本文抜粋、RPC/REST等の例外message、group/bot/lease識別子 | 固定event・数値・例外型のみ |

生payloadを加工・maskして残すhelperは追加していない。入力を受ける各Log呼出の引数だけを
変更した。debug限定ログは同じ条件を維持し、常時ログも安全なmetadataに限定した。
HTTP/close codeや接続状態、件数・長さ・時間は引き続き診断に利用できる。

送信JSON、callback順序、lock、queue/reconnect方針、replay/seq、認証失効判定、
RPCエラー契約、`WsEvent.Unknown.raw`、GroupChatのメッセージ状態は維持した。
UI・navigation・承認処理・Mini App・DB/schema・署名・ID/version・Gradle/依存の変更はない。

## 追加回帰テスト

`app/src/test/java/com/m57/hermescontrol/`配下:

- `data/ws/HermesWsClientTest.kt` に5件追加。
  - 合成secret/sudo/長文を送信し、wire値とqueue再送frameが保持されること、sinkに残らないこと。
  - 壊れたJSON・未知type・例外cause/suppressedの抑止と、Unknown.rawの保持。
  - token/ticket/cookie/任意query/percent encodingを含むURLの非出力と、handshake URLの保持。
  - replay RPCエラー・pending任意methodの非出力、pending失敗/replay終了状態の保持。
  - ticket取得例外・壊れたticket JSONの非出力と、失敗時null契約の保持。
- `ui/bots/group/GroupChatViewModelTest.kt` に1件追加。
  送信・完了・RPC失敗を通し、本文は会話状態へ保持しながらLog sinkには混入しないことを確認。
- `util/DiagnosticLogCapture.kt` を追加。
  Android Logのd/i/w/e・2引数/Throwable付き3引数を捕捉し、Throwable全文も検査対象にする。
  合成sentinelを含むログがあればassertionが失敗する。部分maskの自己テストではない。

既存テストの期待値は変更していない。変更したLog.eの2引数overload用stubだけを既存fixtureへ
追加した。新テストはdebug/releaseの出力条件を区別し、どちらでも秘密値の不在を検査する。
実credential・実Hermesは使わず、mock socket / MockWebServerのloopbackだけを利用した。

## 検証結果

実装は完了し、対象の追加回帰6件は成功。検証状態は **blocked（全品質ゲート未合格）**。
既存のunit 2件・color guard失敗とRelease単体テスト未実施が残るため、完全検証済み・merge-readyとは判定しない。

JDK 21、既存Android SDKを使用。ANDROID_HOME、JAVA_TOOL_OPTIONS
(`-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8`)、Git shとktlint 1.8.0のPATHは
検証プロセス内で設定した。ホスト設定・Gradle・依存は変更していない。

共通実行形式: `./gradlew.bat <task> --no-daemon --console=plain`。

| 順序・コマンド | 結果 | 分類 |
|---|---|---|
| 対象6ファイルのktlint 1.8.0 format/check、`git diff --check` | 最終確認成功 | 最初のformatで新テスト2行の長さを検出し、折返しを修正。製品挙動・期待値は変更なし |
| `testDebugUnitTest --tests '*HermesWsClientTest.testDiagnostics*' --tests '*GroupChatViewModelTest.testDiagnostics*'` | 6件成功、failure/error 0 | 追加回帰。全6件を含む最終focused実行成功 |
| `ktlintCheck` | 成功 | 必須ゲート |
| `testDebugUnitTest` | 1,281件、failure 2、error/skip 0 | 以下の既存2件のみ失敗。追加6件を含め新規失敗なし |
| `lintDebug` | 成功 | 必須ゲート |
| `assembleDebug` | 成功 | 必須ゲート。インストールしていない |
| `checkColorLiterals` | 失敗 | 既存Windowsパス判定。theme内Color.kt/presetsが列挙された |
| `compileReleaseKotlin` | 成功 | 必須ゲート。署名済みAPK配布の検証ではない |
| `testReleaseUnitTest --tests '*HermesWsClientTest.testDiagnostics*' --tests '*GroupChatViewModelTest.testDiagnostics*'` | タスク不存在で起動不可 | 追加で試したRelease回帰は未実施。新規テストのassertion失敗ではない |
| 実機/emulator/実Hermes/GitHub CI | 未実施 | 今回の対象外。認証情報なし、公開なし |

既存失敗の比較:

1. `GatewayFileClientTest.normalizePath expands tilde`: expected `/home/test/foo.png`, actual `null`。
   採用監査と同じHOME未設定時の不整合。環境を偽装したり期待値を変えたりしていない。
2. `HermesWsClientTest.testSequenceNumberDeduplication`: expected `[A]`, actual `[]`。
   採用監査と同じ失敗名・内容。原因未確定のままで、今回の修正効果や新規回帰とは判定しない。
3. color guardも採用監査と同じtheme除外のWindowsパス問題。検査を無効化していない。

追加テストのsink検査では、合成秘密値・本文・URL・例外詳細の混入は再現しなかった。
wireの値、同一queue frame、handshake URL、Unknown.raw、GroupChat本文の保持をassertした。
既存の通信/reconnect/queue/replay系テストも全単体テストで実行し、上記以外の失敗はなかった。
Releaseは同じ安全なsinkのソース確認とコンパイルまでで、実行試験の成功は主張しない。

既存計画の「testがrelease variantも含む」という推定は今回のタスク選択結果では裏付けられない。
本checkoutでRelease単体テストが提供されるかは、将来のビルド検証工程で確認する。

ログ・JUnit XML・集計JSONは製品checkout外の `E:/OSHIIRE/software/Hermes/pr02-validation/`
へ保存した。`.audit`の既存ktlintをツールとして参照したが、製品変更は正式checkout内のみ。
これらのログ・ツール・SDK・APK・依存バイナリはコミット対象ではない。

## 確認範囲と残る制約

- 読み取り専用の独立調査・候補レビューを各1回実施。対象3ファイルのsinkに、残存する
  入力本文/URL/Throwable流入や通信回帰を示す具体的指摘はなかった。レビューは実行試験の代用ではない。
- 全health例外分岐・GroupChatの全catch分岐を個別実行したわけではない。対象sinkの確認と
  代表経路の回帰テストを組み合わせた検証であり、全アプリの漏洩ゼロを保証するものではない。
- 既存Logs画面のexportはRESTから取得したサーバーログを出力する別機能。今回のWS診断を
  集約する経路は確認されなかった。サーバーログの内容・過去に保存されたログは修正/削除していない。
- 独立したKanbanEventsClientのURLログ、ChatViewModelの他の診断経路は本PRの変更範囲外。
  本PRだけでアプリ全体のdiagnostics/exportを安全性認定したとは扱わない。
- PR01のWindows品質ゲート修正、DB/approval/queue policyの問題は混ぜていない。
- 実機・emulator・実Hermes疎通、署名済みrelease配布、GitHub CIは未実施。
  push・merge・PR公開・実機インストールを行わず、PR02のローカルコミットで停止する。

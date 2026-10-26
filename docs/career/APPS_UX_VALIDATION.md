# Apps・就活コーチ 0.3 検証記録

2026-09-08/09 JST。ローカル未コミット変更。基点 `719a809810f69503e2aafcc5ba5a0e295b63e27f`、ブランチ `feat/career-coach`。

## 実装

- Appsの通常一覧は組込の就活App。既存のmanifest例はAdvancedの開発用一覧へ移した。
- 起動時は接続先・プロファイルごとの前回の安定した画面を復元する。履歴がなければChat。認証と初期設定を優先し、一時ダイアログは復元しない。
- 通常Chatと就活Appでモデル・推論強度を分けて保存する。通常Chatは既存セッションの設定を優先し、サーバーの確認後に選択値を保存する。就活の自動調査には独立したサーバー設定を設けた。
- 就活は会話を主画面にし、記録・できること・設定を補助画面にした。実際の次の行動がないときは「今日の提案」を表示しない。
- モデル一覧はCodexのアカウント向けカタログから取得し、Hermesが変更せず送信できる推論強度だけ提示する。掲載は実推論の成功を保証しない。
- 各Runは受付時の実行設定を保持する。再送・再開で変更せず、モデルへの送信直前にも照合する。受付値と送信確認値を画面で確認できる。
- 旧サーバーは機能フラグで判別する。キャッシュだけを根拠に新しい設定を送らない。実行設定のない旧Runは明示再開時の確認を必要とする。
- 成果物カードは保存操作の台帳から生成し、文書の版・本文・文字数・サーバー保存・GitHub同期を区別する。修正は表示中の文書IDと版に固定し、旧版を保持する。
- 未完了操作は暗号化保存してから送信する。再送で操作IDと実行設定を維持し、暗号化キャッシュを読めない場合は既存キャッシュへの上書き・外部送信を止める。

## 実施した検証

- Python単体60件成功。受付設定の固定、旧クライアントの再送、日次設定のCASとプロファイル分離、旧Runの明示再開、文書版の衝突、保存台帳、GitHub未同期、実行アダプターの送信直前照合を含む。Hermes・Codex・GitHubは模擬で、実モデル実行の証拠ではない。
- Android全体単体1,430件・128スイート成功。その後、暗号化キャッシュ読込失敗時の保存・送信防止を追加し、CareerCoachViewModelTestの15件成功。全体1,431件を一括再実行したという意味ではない。
- `ktlintCheck`、`checkColorLiterals`、`lintDebug`、`assembleDebug`、`assembleDebugAndroidTest`、`compileReleaseKotlin` 成功。文書画面の文字倍率の最終修正後にも全関連ビルド・静的検査が成功した。
- 専用エミュレーター `emulator-5554 / Codex_Personal_AI_Phase1` に既存APKを上書き更新。アンインストール・データ消去なし。Apps・ナビゲーション・シェルの9件成功。
- 就活画面9件成功。実アプリと同じedge-to-edge / adjustResizeで確認した。文書画面の文字倍率修正後は該当1件も再実行して成功し、本文の実描画fontScaleが2であることを検証した。キーボードはフォーカス中のウィンドウのIME可視性と実Insetsを確認する。会話入力と文書修正のOSキャプチャを目視し、本文・入力欄・送信ボタン・IMEが同時に見えることを確認した。テスト用の保存先・起動ANR・PixelCopyタイムアウトを経て、内部保存先とOSキャプチャで検証を完了した。
- 更新スクリプトの5件はWindowsと導入先Linuxで成功。DB・設定保持、旧コード保存、起動失敗時の旧コード復元、互換性失敗時にサービスを停止しないこと、ZIPハッシュ照合、旧モジュール混入防止を検証した。サービス操作は模擬。

## 配布・導入の境界

サーバーZIPはcareerプラグインとそのSkill・テスト・資料のみ。実DB、会話、就活データ、認証情報、署名鍵、Git履歴は含めない。ZIP全体と内部ファイルのSHA-256を更新スクリプトで照合する。

更新スクリプトは実HermesのAPI・モデル関連関数・送信フックを確認し、候補APIを独立プロセスで読み込む。成功後にサービスを止め、設定・SQLiteをバックアップし、旧コードを保存して切り替える。失敗時は旧コードを戻し、DBは保持する。設定・認証・タイマーを自動変更しない。

準備先は `/home/keiya/career-apps-ux-update/`。稼働コードの更新には管理者本人のsudo入力が必要。HTTP 401だけでは認証後のAPIやモデル推論を検証したことにならない。

AndroidのapplicationIdは `com.m57.hermescontrol`、versionCodeは1、署名証明書SHA-256は `8561de18c9143b61a26d970ec28c30a20f4260ffba6879eab8250badc3f3524b` で従来と一致する。実機の更新・表示は未実施。

## 実環境で未確認

1. 新サーバーの有効化、認証後のcapabilitiesとモデル一覧。
2. 実カタログの異なる2モデル・対応推論強度での受付設定と送信設定の一致、実返答。
3. MobileからのES依頼・回答・保存、画面終了・再接続、全文を見ながらの修正と旧版保持。
4. 実GitHub同期、実データ取り込み、日次タイマーの無人実行。

`server/career/smoke_coach.py --live --model ID --reasoning EFFORT --second-model ID --second-reasoning EFFORT` は一時DBの合成データでサーバー側の2モデル・Skill・保存・再開・修正を検証できる。実行IDはサーバーの候補から選ぶ。Mobileの実操作とGitHub同期の確認は別途必要。

## ローカル証跡

`E:/OSHIIRE/software/Hermes/career-validation/` の `apps-ux-backend-final.log`、`apps-ux-gates-final.log`、`apps-ux-cache-test.log`、`apps-ux-artifact-font-build.log`、`apps-ux-device-final.log`、`apps-ux-artifact-font-device.log`。`apps-ux-screenshots/` の2つのfont200-ime画像は合成データの実キャプチャ。

# 企業詳細の改修記録 — 2026-09-15

対象は `feat/career-coach` の既存Hermes Mobile。未コミットの設定画面・モデル選択・メール・ES関連変更を残した上で実装した。アプリとサーバーのローカル成果物であり、本番への適用記録ではない。

## 実装

| 画面・処理 | 実ファイルと接続 |
|---|---|
| 企業一覧・企業詳細 | `CareerCompanyScreen.kt` → `CareerCompanyPager.kt`。既存の `CareerCompanyKey` を使用。旧 `CareerMailKey` も同じ画面のメールタブへ接続 |
| 4ページ | Compose Foundation `HorizontalPager` とMaterial 3のスクロール可能なタブ。マイページ / メール / 対策 / 管理。既存の固定ヘッダー設定でタブとステータスバーの重なりを防止。依存ライブラリ追加なし |
| 状態・取得 | `CareerCompanyViewModel.kt` / `CareerCompanyModels.kt` / 既存 `CareerCompanyRepository.kt` → `/companies/{company_id}`、`/collection/connections`、明示操作の `/collection/*` |
| ログイン後の案内 | `mypage.py` / `mypage_reader.py` → 既存AXOL接続のホームのみ。`POST /collection/mypage-check` は明示操作のみ。旧 `AxolReader.fetch()` は呼ばない |
| メール | 既存MailCollector / MailStoreを利用。キーはアカウントID＋メッセージID。検索・未読フィルターは保存済み直近100件が対象と明示 |
| 志望度・選考状況 | `POST /collection/company-preferences`。本人が指定した値のみ、企業の版を確認して保存。他の企業フィールド・取り込み済み選考記録を保持 |
| 相談の参照 | `CareerTarget.source_refs` → `CareerCoachViewModel` → `CoachStore.submit()` → `accepted_sources()`。企業・募集の既存スレッド、文書IDと版を保持。別企業・別アカウントの資料を拒否し、選択時のサニタイズ済み本文を実行リクエストへ固定 |
| 自動調査 | `CoachWorker` のdaily/researchは保存済みマイページ案内・メールを整理。公開サイトの自動巡回を外し、当該実行からの `career_fetch` も拒否 |

### 閲覧画面から移したもの

- マイページにはログイン後の案内・締切だけを表示。公開募集・メール本文・ES編集ボタンを混在させない。
- メールの送信元登録、Gmail接続、端末保存のログインID、本人の志望度・選考状況、取得履歴は管理へ移動。
- ES編集・保存済み文書・企業研究・面接準備は対策へ集約。既存のES編集・保存・版管理・会話再開を使用。
- 原文は詳細から選択・閲覧可能。過去版の原文は管理の履歴から開ける。

### 取得・保存の扱い

- タブ切替はネットワーク・LLM・ES取得を起動しない。タブごとのリスト位置、メール検索・フィルター、募集選択、入力中の管理項目、参照資料を保持する。
- 未取得・取得中・成功0件・失敗を分ける。失敗時もキャッシュと最終成功日時を保持し、データがなければ件数不明と表示する。
- 保存データのGETや取得の受付だけでは最終成功日時を更新しない。Gmailの明示取得後は最大20秒、保存状態を確認し、完了が確認できなければその旨を表示する。
- 企業データのGET成功と接続メタデータのGET失敗を分離。端末キャッシュ書込に失敗しても取得済みデータは表示し、保存失敗を通知する。
- 同じ案内のIDは1件。内容変更は原文履歴を保持し、ホームの現在の一覧だけを差し替える。本文・取得履歴・メールをGitHub同期対象へ追加していない。
- 日付だけの締切に時刻を補わない。年がない日付・時間帯不明・複数種類の締切は原文と精度を残す。提出済み等の本人状態を案内から推定しない。
- 資格情報の保存先・OAuth権限・既存の停止設定・アプリID・署名・グローバルナビゲーションは変更していない。

## 検証

検証ファイルは `../../../career-validation/company-redesign-implementation-20260915/` に保存。

| 確認 | 結果・ログ |
|---|---|
| Python回帰テスト | 157件成功。`backend-final.log`。合成データのみ |
| Android就活関連単体テスト | 45件成功。XML結果のfailures/errors/skippedはいずれも0 |
| ビルド等 | `assembleDebug`、`assembleDebugAndroidTest`、`ktlintCheck`、`checkColorLiterals`、`lintDebug`、`compileReleaseKotlin` 成功。`android-final.log` / `ui-final-build.log` |
| Compose・ナビゲーション | 41件成功（各対象の最終結果を集計）。既存26件は `instrumentation-final.log`、企業詳細7件は `company-regression-final.log`、新規Pager8件は `pager-final.log`。一覧は `ui-results.json` |
| 画面キャプチャ | 通常4タブ、キャッシュあり・なしのメール取得失敗、360dp/200%/dark、390dp/light、430dp/130%/light。テスト用の幅制約外の余白を含む |

実行コマンド（Windows、JDK 21、Android SDK、UTF-8設定済みのプロセス内）:

```text
py -3.12 -X utf8 -m unittest discover -s server/career/tests -p "test_*.py" -q
gradlew.bat assembleDebug assembleDebugAndroidTest testDebugUnitTest --tests "com.m57.hermescontrol.career.*" ktlintCheck checkColorLiterals lintDebug compileReleaseKotlin --console=plain
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r -e class com.m57.hermescontrol.career.CareerCompanyPagerTest -e additionalTestOutputDir /data/user/0/com.m57.hermescontrol/files/company-redesign com.m57.hermescontrol.test/androidx.test.runner.AndroidJUnitRunner
```

検証途中のLint内部解析エラーは、編集終了後の再実行では再現せず成功。検出器は無効化していない。旧「公開募集をマイページに表示する」UIテストは、今回の要件に合わせ、非表示と既存ES対象の保持を検証する内容へ変更した。Composeノードの画像取得はエミュレーターで失敗したため既存のUIAutomationキャプチャを使用。縦スクロール後に可動ヘッダーが隠れるとタブがステータスバーと重なるケースを確認し、企業詳細に既存の `pinTopBar` を適用した。状態復元テストはタブの移動完了を確認してから復元する。

## 検収IDとの対応と未確認範囲

| ID | 確認内容 |
|---|---|
| IA-01〜04、NAV-01〜02、DATA-01 | 実画面へのルート、4タブのタップ・スワイプ、読取元の分離、検索・フィルター・入力・スクロールの保持、10回のタブ切替で操作コールバック0回を確認 |
| DATA-03〜07 | 案内重複・原文の版、同名で異なるID、締切の種類・精度・不明年、本人状態を推定しない処理を合成データで確認 |
| MAIL-01〜04 | 既存の完全一致仕分け・読取専用テストと、アカウント＋メッセージの識別、検索0件の表示を確認 |
| ST-01〜08 | 未取得/成功/失敗、最終成功日時とキャッシュ保持、企業ごとの状態分離、取得中断、保存読込と取得の区別を実装。実ネットワーク遮断中の実機操作は未実施 |
| PREP-01〜03、SAFE-01〜03 | 参照IDの送信・所属検証・既存文書と入力の保持、ES編集保存の回帰、旧ES取得を新経路から呼ばないことを確認 |
| UI-01〜03、BUILD-01 | 幅・文字倍率・テーマ別の描画とビルドを確認。既存シェル/バックスタックの回帰テストを実施 |
| NAV-03〜05、PREP-04、UI-04〜05 | 標準Pager/タブ/BackHandler/SelectionContainer/インセットを使用。TalkBackの読み上げ全文、OS戻るジェスチャーと斜め操作、実機IME・強制停止後の復元の全面検収は未実施 |

**ライブの取得を成功と認定していない。** マイページの対応範囲は、既存の日鉄エンジニアリングAXOL接続のログイン後ホームのみ。実際のHTMLへの適合、追加認証、案内詳細ページ、他社ポータル、実Gmailの接続エラーの原因、実モデル推論は未確認。未知の構造・接続未設定は成功0件に置き換えない。

実機アプリ・本番サーバー・systemd設定への適用、push、mergeは実施していない。停止中の自動取得を有効化していない。

## ローカル成果物

`../../../career-validation/company-redesign-implementation-20260915/`:

- `hermes-company-redesign-debug.apk`: 前段の設定・モデル選択修正も含むAndroid改修版。
- `hermes-career-company-redesign.zip`: 既存のコード限定allowlistから作成するサーバー更新用ZIP。新規モジュール・検証記録を収録し、ユーザーデータ・資格情報を含めない。
- `CHECKSUMS.txt`: 成果物のSHA-256。
- `task-code.diff`: この実装開始時のスナップショットに対するコード差分。以前からの未コミット変更を打ち消すパッチではない。
- `ui-screenshots.tar`: エミュレーターで実際に描画した合成データの画面キャプチャ。


## 本番反映確認（2026-09-16）

ユーザーがバックアップ付き更新スクリプトを実行し、`UPDATE_OK`を確認。旧コード・データのバックアップ先は`/var/lib/hermes/career-update-backups/20260916-203046-67181d33`。Dashboardは20:30:48 JSTに再起動し、`active/running`・再起動回数0を確認した。停止中のresearch/syncタイマーはinactiveを維持。

接続実機RFCY201PM5Fのインストール済みAPKには、検証済み成果物と同一のCareerコード（classes16.dex、1,071クラス）が含まれていたため、古いAPKによる再インストールは行っていない。実機で企業詳細の4タブ、メールへのスワイプ、対策・管理へのタップ、未取得時の件数不明表示を確認した。再起動後の実機ログで認証付きCareer APIのHTTP 200を確認。未認証HTTP 401のみを接続成功の根拠にはしていない。

実ポータルのログイン取得、実Gmail受信、モデル推論、通知配信は今回実行していない。更新スクリプト末尾のpublic official-page researchは旧確認文言であり、今回の取得対象には含めない。検証記録は`../../../career-validation/company-redesign-deploy-20260916/deployment-status.json`。

# 企業設定のログインID共用と自動入力

## 変更

- 「登録先の名称」「アカウント名」の入力欄を削除。既存の `label` / `accountLabel` は保存時も保持する。
- ログインID欄を取得設定と共用し、「取得用ログインID」の二重入力をなくす。複数IDがある場合は取得に使うIDを明示的に選ぶ。
- 追加のユーザー指示により、単独のログインIDカード・追加/削除/単独保存ボタンを削除。取得設定カード内で「ログインID」「パスワード」を直に上下に並べる。既存IDがない企業も、その場で入力・保存できる。
- 接続の保存成功後、同じIDを既存の企業別暗号化端末ストレージにも保存する。他のIDや既存メタデータは保持する。
- 接続保存の失敗時は入力を保持し、端末の保存済みIDを変更しない。接続は保存済みでも端末保存に失敗した場合は、その状態と再保存方法を表示する。
- 共用IDに `ContentType.Username`、パスワードに `ContentType.Password` とパスワード用キーボードを設定。IDとパスワードを同じLazyList項目に配置し、自動入力時に片方だけが破棄される構成を避ける。
- パスワードは `remember` のみに保持し、保存成功・画面再生成・別IDの選択で破棄する。
- サーバーのAPI、秘密情報保存、GitHub同期形式は変更しない。

## 既存実装の調査

追加ライブラリは不要。Androidの公開サンプルと公式のCompose Autofill APIを使用する。

- [Android公式 Autofill in Compose](https://developer.android.com/develop/ui/compose/text/autofill)
- [android/snippets の AutofillSnippets.kt](https://github.com/android/snippets/blob/6985d27f61db1baeb055bec33a0f36a9b3e9b7b4/compose/snippets/src/main/java/com/example/compose/snippets/text/AutofillSnippets.kt)

2026-09-20の企業設定仕様にあった端末内IDと取得用IDの別入力を、今回のユーザー指示で共用に変更する。接続の保存ボタンを押した場合に既存の取得設定APIへ送信し、成功後に端末のID表示も更新する。

## 検証

検証ログと変更前ファイルは `../career-validation/company-autofill-20260921/` に保存する。

- `assembleDebug`、`assembleDebugAndroidTest`、`compileReleaseKotlin`、`lintDebug`、`checkColorLiterals` 成功。
- 変更したKotlin 4ファイルのktlint成功。
- `CareerCompanySettingsTest` 3件、`CareerCompanyViewModelTest` 22件成功。
- API 36エミュレーターの `CareerCompanySettingsUiTest` 7件、`CareerMyPageSettingsUiTest` 8件成功。
- 自動入力テストは現行Composeの `OnFillData` を使い、ID・パスワード入力から取得APIへの引き渡し、端末ID保存、既存の名称情報保持を確認。
- 初回のUIテストでは旧 `OnAutofillText` を使った1件が失敗。テストを現行APIに修正し、該当クラス8件を再実行して全件成功した。

実際のパスワードマネージャーによる候補表示、企業サイトへのログインは未確認。

## 実機反映

ユーザーの「反映しといて」を受け、2026-09-21 19:32 JSTにSC-51Fへ `adb install -r` で上書き更新した。
`firstInstallTime=2026-09-06 23:04:00` と既存データディレクトリを維持し、MainActivityの起動成功とプロセス継続を確認した。
実機のbase.apkのSHA-256は検証済みAPKと一致：`315ae8234fe4cbcf5e1a8f3ad4a5e331121e47d989534974097eb7010c708ae8`。
サーバー変更は不要。実機のパスワードマネージャーでの候補表示・入力操作は未確認。

## ID・パスワード隣接配置の追加修正

単独IDカードを削除し、取得設定内の1つのID入力欄をパスワードの直上へ移動した。
`CareerMyPageSettingsUiTest` 9件成功。ID入力欄が1つで両欄の間隔が16dp以内であること、ID未登録企業でパスワードを先に自動入力してもID入力後に保持されること、保存・失敗・複数ID選択を確認した。
Debug APK、テストAPK、Release Kotlinコンパイル、Lint、色チェック、変更したKotlin 3ファイルのktlintも成功。

2026-09-21 19:54 JSTにSC-51Fへ上書き反映し、初回インストール時刻の維持と起動成功を確認。
実機APKと検証済みAPKのSHA-256は一致：`d19a76be67221d2652d8fa77a8d7dd032e09430ddda15aa6adb863a6739c2f2a`。
証跡とAPKは `../career-validation/company-autofill-adjacent-20260921/` に保存。

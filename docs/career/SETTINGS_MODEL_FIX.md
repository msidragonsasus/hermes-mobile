# 設定導線と自動調査モデルの修正（2026-09-14）

対象は `feat/career-coach` の既存未コミット変更上。企業詳細の4タブ改修は計画段階のまま、追加で依頼された設定画面とモデル制限を局所修正した。

## 変更

- `Navigation.kt` の `CareerSettingsKey` を `CareerSettingsScreen()` へ接続。既存ViewModel・設定コンポーネントを共用し、設定には「相談／記録／設定」の内部タブを表示しない。旧版で設定内に保存された選択タブが「記録」でも、設定として開く。
- 相談から設定を選んだ場合も同じ設定destinationへ移動。取り込み・同期・保留操作・利用停止の確認は維持し、グローバルナビゲーションは変更しない。
- 設定から公開サイト調査の実行ボタンと結果表示を除去。自動調査モデルの設定を独立した項目として残す。保存応答を受け取れなかった場合は、同じ操作IDで再確認できる。
- `coach_execution.py` はHermes本体の `hermes_cli.inventory` を用いてモデル候補を取得。Codex以外の接続済み候補も返す。Codexのログイン確認に失敗しても、他の候補を消さずプロバイダー別の失敗を表示する。
- モデル識別にはproviderとmodelの組を使う。自動調査の設定は既存のCAS・操作ID・profile境界を維持し、既に受け付けたRunの設定を後から変えない。
- `coach_runtime.py` は明示的なprovider/modelで本体のruntime resolverを使用。`chat_completions`、`anthropic_messages`、`codex_responses`に対応し、未対応方式はエラーで停止する。Codexは既存OAuthと推論強度を維持。それ以外の推論設定は「Hermes標準」（内部値`default`、AIAgentには`None`）とし、架空の強度を列挙しない。
- 送信前にprovider、接続先、API方式、model、推論設定を照合する。fallbackは空、credential poolの途中切替と環境変数からの接続再選択は使わない。秘密値を端末や進捗イベントに含めない。

本体インターフェースは既存の参照checkout `E:/OSHIIRE/software/Hermes/.audit/hermes-agent` のcommit `9dd6634c5635321cf38840cc30e9b51226689128` で確認した。`inventory.py`、`runtime_provider.py`、`agent_init.py`、`turn_api_call.py`を確認し、新しいHTTPモデルクライアントは作っていない。稼働サーバーのソースは管理者認証が必要なため照合していない。

## 企業詳細の計画への反映

「マイページ」はログイン後の募集・案内・締切だけ。公開採用ページを含めない。計画は親ディレクトリの `docs/company-redesign/IMPLEMENTATION_PLAN.md` に反映した。

現行のログイン後取得は停止している。今回の設定修正では、取得の再開、ES設問取得の復活、既存日次ジョブの処理内容や停止設定の変更を行っていない。ログイン後の募集・案内専用の収集経路は企業詳細の実装で必要となる。

## 検証と適用

検証ログ・変更前コピー・今回だけの差分は `E:/OSHIIRE/software/Hermes/career-validation/settings-models-20260914/`。

- サーバー全テスト144件成功。OpenRouterのrouting policy保持を調整した後、実行設定の関連19件を再実行して成功。
- AndroidのCareer単体テスト41件成功。
- `assembleDebug`、`assembleDebugAndroidTest`、`ktlintCheck`、`checkColorLiterals`、`lintDebug`、`compileReleaseKotlin` 成功。旧設定タブの復元対策後にも再確認して成功。
- Compose操作テスト34件成功。CareerScreen/Company/Mail/EsとAppBackStack/ShellChromeをテスト専用エミュレーターで実行。文字2倍・旧タブ保持状態での設定専用表示、別プロバイダーの自動調査モデル選択、既存の戻る操作を含む。設定の実描画も確認した。
- 初回の保存スナップショット起動ではエミュレーターがADBへ応答しなくなり中断。データ消去せず音声なしでコールド起動した。次のGradle操作テストは34件中8件が画像出力先のEPERMで失敗。テスト内容は変えず、同じAPKをテスト端末へ上書き導入し、出力先を対象アプリのprivate filesへ変更してAndroidJUnitRunnerを直接実行した結果が上記34件成功。失敗ログも保持している。
- `git diff --check` 成功。変更前コピーと照合した今回だけの差分は `task-only.diff`。
- 外部プロバイダーへの実推論・課金API呼出・本番のログイン後取得・実機適用・サーバーデプロイは未実施。モデルの実行試験は合成データと偽runtimeによる検証であり、候補掲載を実推論成功とは扱わない。

作成した配布候補（ローカル保管のみ）:

- `hermes-settings-models-debug.apk`：SHA256 `66310ed463212a45be7b1e8c67b8745a685b3d9ac0020472aa9233ef9ebb655c`
- `hermes-career-settings-models.zip`：SHA256 `d00b483251a678d0b8f5fca8dac75c511274da205a07433f8585a7f78548b48d`

サーバーZIPは既存の `server/career/tools/package_release.py` とallowlistから作成。既存の変更も含む現在のコード一式であり、今回の差分だけのパッチではない。公開・転送・更新処理は実行していない。

# 企業別の就活App 0.4 検証記録

対象: `msidragonsasus/hermes-mobile`、`feat/career-coach`、基準HEAD `719a809810f69503e2aafcc5ba5a0e295b63e27f` 上の未コミット変更。
2026-09-09の承認済み実装。既存の差分・DB・GitHub状態・Androidデータを保持する。

## 実装した内容

企業一覧を入口にし、企業詳細から募集選択、根拠の確認、相談、ES保存、修正、面接の練習へ進む。
会話は全体・企業・募集で分かれる。本人情報の分類シートやURL一覧の準備は通常の利用条件にしない。
挨拶だけで形式的な今日の提案や回答待ちを表示しない。

Gmailは、既存Hermes bundled `google-workspace` の `google_api.py` とOAuth保存先を再利用する。
公式ラッパーの `_gws_binary / _run_gws / build_service` 契約に合わせ、`gws`・Pythonの両経路を読み取りに限定する。
参照コード: [公式google_api.py](https://github.com/NousResearch/hermes-agent/blob/main/skills/productivity/google-workspace/scripts/google_api.py)。
新規Gmail認証処理は削除した。Googleの認証ファイルを新しい秘密保存先へ複製しない。

マイページ用Chromiumは独立したPython環境を使う。既存Hermesの依存関係を上書きしない。
マイページのID・パスワード、ブラウザ状態は暗号化し、モデルやGitHubから分離する。
本人向け案内・設問の来歴と版はサーバー内で保持する。確認できない設問、字数、締切は推測しない。

## ローカルで確認したこと

- Android: `assembleDebug / testDebugUnitTest / ktlintCheck / checkColorLiterals / lintDebug / compileReleaseKotlin` 成功。単体テスト1,436件、失敗・スキップなし。
- 安全な専用AVD `Codex_Personal_AI_Phase1`、`emulator-5554` の画面テスト14件成功。
- サーバーの回帰テスト105件成功。合成データのみ。実環境と同じcryptography 50.0.0で確認。
- Googleラッパーの両バックエンド、読取操作制限、権限拡大時の停止、異なるホーム・アカウントの取り違え防止を検証。
- 私的な子プロセスのタイムアウト・中断・終了、秘密をstderrから外へ出さない処理、ブラウザへGoogle/Codex認証を渡さない処理を検証。
- メール差分の再開、重複抑止、締切の矛盾、認証切れでも既存根拠を保持する処理を検証。
- 企業・募集ごとの会話、設問の変更検出、文字数、ES修正時の旧版と関連保持、別企業・別募集への上書き拒否を検証。
- 360dp・文字倍率150%で長い企業名、検索、募集選択からES相談への移動を確認。
- 通知先の再起動後復元と別プロフィールの拒否を合成通知で確認。実際のバックグラウンド配信は別途確認が必要。
- パッケージの再現性・明示allowlist・上書き防止、更新前の検査失敗でサービスを変更しないこと、再起動失敗で旧コードとDBを保持することを検証。

## 実環境のメタデータ確認

2026-09-09、本人が管理者端末で実行した診断結果を確認した。
実際のHERMES_HOMEは `/var/lib/hermes/.hermes`、既存の `google_token.json` と `google_client_secret.json` が同ホームに存在する。
配置済み `google_api.py` のSHA256は `d3822ef5cbdfad1d280fb619b9d85f2bdc0f8d8756398eb0189a126e8a6fbf89` で、今回参照した公式コードと一致した。
`gws` はPATH上になく、既存ラッパーのPython経路を使う構成。Dashboardは稼働中、careerは0.3.0。
cryptography 50.0.0は導入済み。専用Playwright/Chromium、マイページの暗号鍵・秘密保存先は未準備。
この診断ではGoogle認証の有効性やメール本文の取得は確認していない。

## 本番反映前の確認

1. `tools/collection_preflight.py` を管理者端末から実行し、実際のHERMES_HOME、既存ラッパー、ファイル配置、稼働版を確認する。本文や秘密値は出力しない。Himalayaの有無やMCP件数ではGmailの接続可否を判断しない。
2. コードZIP・APK・ハッシュと以下の変更を確認して、本番反映を承認する。
3. `tools/update_server.py --archive <確認したZIP> --sha256 <確認したSHA256>` を既存管理者権限で実行する。稼働中のresearch/sync timerがある場合は自動変更せず停止する。設定とDBのバックアップ、候補コードの契約確認、コード交換、Dashboard再起動を行う。
4. `tools/prepare_browser.py --install` を管理者端末から実行する。別環境にPlaywright 1.58.0と公式Chromiumを用意し、空のブラウザを起動して検査する。OSライブラリが不足する場合は停止し、必要なOS変更を別途確認する。既存Hermesのvenvを変更しない。
5. `tools/setup_collection.py` を実行する。既存Fernetと専用ブラウザを検査してから、マイページの秘密保存先と暗号鍵用drop-inを追加する。Dashboardを再起動する。Google OAuthファイルや権限を変更しない。
6. Androidは同じapplicationId・署名のAPKを `adb -s <対象端末> install -r <確認したAPK>` で上書きする。署名等で更新できない場合は停止する。アンインストール・データ消去は行わない。
7. Appの「接続」で既存Gmailが検出されることを確認し、AXOLのID・パスワードを専用画面から入力する。本人が定期取得と端末通知を有効にする。
8. 本人向け設問の取得、出典、ES保存と旧版、Appを閉じた後の取得、端末通知からの再開を実際に確認してから運用開始とする。

## 復旧と保持

- 更新前のコード・設定・SQLite backupは `/var/lib/hermes/career-update-backups/<実行時の名前>` に保持する。
- コード交換・再起動に失敗した場合は更新スクリプトが旧コードへ戻す。DBを過去へ巻き戻さない。
- 問題が見つかった場合はAppの定期取得を停止する。Googleが不調でも停止できる。
- 旧コードへ戻す場合はDashboardを停止し、失敗版を別名で残して旧コードを戻す。新しいDB列・テーブルは残し、バックアップへの破壊的置換をしない。
- 秘密保存のサービス設定に失敗した場合は旧drop-inへ戻して再起動する。暗号鍵・認証ファイル・取得した情報は削除しない。
- 独立したブラウザ環境は無効化後も残せる。復旧のためにHermesの依存関係を戻す必要はない。

## 未確認・制限

本番への配置・再起動、実機更新、実Gmail取得、認証済みAXOL、アプリ終了後の実通知はこのローカル検証では実施していない。
ラッパーの配置とコードの一致は確認済み。既存Google認証を使ったAPI呼び出しは本番接続試験が必要。
先行対応は日鉄エンジニアリングのAXOLと指定送信元だけ。別企業・別年度の自動認証は含めない。
MFA/CAPTCHAの対話操作画面は未実装で、検出時に取得を停止する。事前アンケートの自動回答やESの自動提出は行わない。
Googleの同意・認証が必要な場合は既存Hermesの接続を本人が回復する。別のOAuth接続へ自動で切り替えない。

# Home implementation — 2026-09-16

## 実装範囲

- 主要タブはホーム／チャット／Apps。旧 Activity の直接リンクは診断画面として維持し、詳細設定の「診断 → 実行イベント」から開く。
- ホームには承認・回答待ち、期限が近い対応、重要な案内、届いた成果だけを表示する。欄ごとに最初の5件を表示し、同じ欄で展開する。通常の実行イベントや一般メールを並べない。
- Career の `HomeView` が保存済みデータをルール判定する。ホームAPIは取得・モデル実行を起動しない。ページング中に対象が変わった場合は409を返し、端末は全件を取り直してからキャッシュを置換する。
- メール／マイページは元の件名・保存済み本文を参照する。結果案内から合否を推測しない。異なる出典を企業名や文章の類似だけで統合しない。
- メール／マイページ／成果物／回答は、対象IDと版を検証して保存済み内容を直接表示する。締切は既存の就活記録ダイアログへ進み、既存の編集・保存処理を使う。
- 結果の既読と対応の完了を分離する。対応項目は読むだけでは消えない。案内への対応は就活Appの共通閲覧画面で明示的に記録する。ホームには完了ボタンを置かない。
- 通常チャットは端末で受信した完了イベントと保存先セッションを利用する。同じ会話の結果は一件にまとめ、表示された回答だけを既読にする。古い接続のイベントと保存先不明のイベントは保存しない。
- SQLCipher Room 7→8 の追加移行でキャッシュ・項目別既読・表示設定を保持する。接続先／プロファイルを分離し、GitHub同期対象にしない。メール本文や認証情報をホームキャッシュへ複製しない。
- 表示・復帰・手動更新・表示中60秒間隔で読み直す。取得失敗時は前回情報を保持し、確認状態と設定への入口を表示する。App表示設定は取得・通知設定から独立する。

## 検証

- Python：`unittest discover -s server/career/tests -q`、171件成功。締切・対象企業・完了案件・日付精度・未確認期限・重要案内・一般告知・将来の結果予告・重複・取得状態・ページング・認証・読み取り無副作用を含む。
- Android：`testDebugUnitTest`、全1,462件成功。その後のホームの既読・並び順・接続検証の仕上げについて、ホーム単体テスト4件と下記エミュレーターテストを再実行し成功。
- エミュレーター：Compose／保存／移行の20件成功。HomeCardTest、HomeScreenTest、HomeRepositoryTest、ShellChromeTest、AppBackStackTest、DatabasePreservationTest、CareerScreenTestの直接遷移テスト。回答の実表示による既読、5件表示からの展開、表示設定、取得失敗時の保持、保存先不明・旧接続イベントの除外、保存状態復元、暗号化DBの既存会話・下書き保持を確認。
- `assembleDebug`、`assembleDebugAndroidTest`、`compileReleaseKotlin`、`lintDebug`、`ktlintCheck`、`checkColorLiterals`、`git diff --check` 成功。既存のGradle／Compose APIの非推奨警告は残る。
- 初回の旧タブ構成を期待するテスト（画面登録数1件、起動先2件）は新仕様に合わせて更新し、最終実行で解消した。失敗を無視したテストはない。

実行ログはローカルの `build/home-validation/`、Debug APKは `app/build/outputs/apk/debug/app-debug.apk`。Pythonの不足依存は `build/home-test-deps` 内へ隔離して導入し、ホストのPython環境を変更していない。

## ライブ検証の範囲

ローカルの合成データとAndroidエミュレーターで検証する。本番サーバーへの配備、実機へのAPK更新、実メール・マイページの取得、実際のAI回答を通したライブE2Eはこの工程に含めない。端末が受信しなかったチャット完了の復元、汎用プラグイン実行基盤も対象外。

既存のCareer／メール／会社詳細などの未コミット変更を前提として接続しており、それらの差分は保持している。

## 本番反映の進捗（2026-09-16）

- 接続された実機 `RFCY201PM5F` に `adb install -r` で上書き更新成功。`firstInstallTime=2026-09-06 23:04:00` を保持し、更新日時は `2026-09-16 23:22:04`。起動と新しいホームタブの表示を確認。利用者が別アプリを操作中だったため、画面の継続操作は停止した。
- APK SHA256: `737040f0b75c5268479efa4ed8ee29190ddc451de41db0e78c1c1365164b6710`。
- 配布allowlistに `home.py` と `test_home.py` を追加し、更新前検査に `/home` と `/home/content` を追加。関連Pythonテスト22件成功。
- サーバーZIPとバックアップ・自動復元付き更新スクリプトを `/home/keiya/career-home-20260916/` へ転送し、ZIP SHA256 `088880a33647d6eeaa87e820e4c21c3aac8282c285a47761e1f627b335e1e34f` の一致を確認。
- サーバーへ配布物を転送後、管理者認証でバックアップ付き更新を実行。`UPDATE_OK: career 0.4.0 installed; Dashboard restarted` を確認した。更新前のruntime契約・候補ルート検査、既存DBバックアップ、Dashboard再起動後の稼働確認を通過した。未認証の `/api/plugins/career/home` は `401 unauthenticated/no_cookie` を返し、認証境界を確認した。認証済みホームAPIの実データ確認は、端末の接続済み画面から続けて確認する。
- 同じログに `Failed to load plugin 'pc-power': No __init__.py ...` が出ている。これは今回のCareer配布物に含まれない別プラグインの既存配置不備で、Career更新は完了している。PC電源Appの本番機能はこの反映の完了条件に含めず、別途 `server/pc_power/tools/install_server.sh` の管理者実行が必要。

# 更新後の履歴取得エラー修正（2026-10-22）

## 現在の状態

APK版47をDriveへ保存・metadata readbackで確認済み。サーバーAPI候補はレビュー・scratch検証合格、
限定バックアップ作成済み。本番ファイルはまだ変更していない。本人へDashboardのみのsudo停止を依頼し回答待ち。
停止・サーバー反映の許可は先の同じスレッドで取得済み。新しい承認待ちではなく本人のOS管理者操作待ち。
別gateway/worker lockへは触れない。パスワード再設定や会話の再送、モデル呼出しは不要。

## 確認した原因

本人の通常Hermesチャット履歴は行が表示されるが、上に「チャット履歴を取得できませんでした」と出る。
ConversationHistoryDrawerがSessions成功結果に、Bot一覧またはグループ履歴の失敗を同じerrorMessageとして合成する。
認証済みbrowserのgroupConversations GETで本人がdetail.code=worker_already_runningを確認した。
GroupService生成時の実行所有ロックが別gateway PID2029151と競合する。読み取り一覧に実行所有権は不要。
Hermesのreadonly確認ではSessions 120件/9519messages、group 11会話/99messages/19runs、両DB quick_check正常。
これは当時の値であり、別gatewayの継続書込みによる自然な件数変化を障害扱いしない。

## 変更

Androidは共通ConversationMenuSheetとSessionsUiState、mergeConversationHistoryでソース別に失敗を保持。
グループだけの失敗で通常履歴error/searchErrorを上書きせず、取得済みの行は選択可能。
グループ専用の文言と再試行を出し、失敗を正常な空一覧として表示しない。グループの失敗自体も隠さない。
保存DB・認証・queue・UNKNOWN送信・画像/UI能力補足・モデル設定に変更なし。

サーバーはgroup_api.pyのみを変更。GET /conversationsの認証/profile検証を維持してSQLite mode=roで
既存conversationsをSELECTする。GroupService constructor/実行lock/worker/queue回復/DDLを呼ばない。
有効profileでDB未作成ならファイルや親ディレクトリを生成せず空配列。破損/非互換/権限/読取エラーは
history_unavailable 503で拒否する。immutableは使わず、現行writerのWAL commitを読める。
他のGET snapshotや書込み・停止routeのworker所有権はこの候補で変更していない。

修正前に公開実装/公式資料を確認し、既存共有UIとPython標準SQLite readonly接続を再利用した。
- https://developer.android.com/topic/libraries/architecture/paging/load-state
- https://docs.python.org/3/library/sqlite3.html#how-to-work-with-sqlite-uris

## 検証と限界

Python全95件成功。追加6件は実所有ロック保持中のGET 200、最新WAL commit、auth/profile拒否、
欠損不生成、破損非改変、readonly書込み拒否。サーバー現行GroupServiceを使うscratch版6件もHermesが全成功と報告。
サーバーの限定独立レビュー合格、ブロッカーなし。
Androidの対象10suite/83件成功、エミュレーター実画面テスト10件/23.535秒成功。色ガード成功。
履歴・画像・Intelligent UIの選択済み既存回帰テストも含む。新規5 Kotlinファイルのktlint成功。
SessionsViewModelは元からあるjava.util.Locale import順でktlintが失敗しており、無関係な整形はしていない。
全JVM suiteは成功扱いにしない。初回は別作業のSettingsViewModelTestコンパイル不整合で停止した。
別build/test-srcでの再実行が広いWebSocket suiteまで含んでMockWebServer teardownで待機したため、
所有確認済みの当該worker PID80928だけを終了。その後明示--testsで83件を実行し成功した。
テスト結果はtargeted-unit.logと該当10suite XMLを使い、中断時の他suite XMLを集計しない。
所有エミュレーターemulator-5584は終了済み。本人スマホのデータ取得・install・clear・uninstallは行っていない。

## 成果物

APK: output/history-partial-fix-20261022/HermesMobile-history-fix-v47.apk
SHA256: 4daea7ba1a7baa795c66185caff7dbf57e94aab87ccb2d9f90f4be2e42463f25
versionCode47、1.0.47-local、46261014 bytes。署名SHA256は前版と一致。
Drive: https://drive.google.com/file/d/1Z1V-dTGB8mg6oASoOmN0ry24kjZvErtm/view?usp=drivesdk
Drive readbackは名前/MIME/size確認。コネクタがchecksumを返さないためリモートSHA一致までは主張しない。

ZIP: output/history-partial-fix-20261022/group-history-readonly-20261022.zip （4538 bytes）
SHA256: c5e9cf6243b50edf98c7063e3178a9e830979755eda7e5d1f431477d4389817f
Slack添付は相手側HTTP403だったため、同じZIPを2分割Base64で同スレッドへ送り、相手の復号後SHA一致済み。
依頼: https://chatgpt-lll7864.slack.com/archives/C0C74STS7D4/p1792598227249999
ZIPに含む本番差替え対象はgroup_api.pyだけ。local group_service.pyは別作業を含みサーバーと不一致なので送っていない。
API before: f48c6ad94de50d6271e88c31e658c09f66551e677fde08a2cf340cf7b7376e99
API after: b602a69ce10fa8bdbaed94f50880002ef54f8aa9876a65cd0c72c68d9a5a6036
保持するserver group_service: cd4aaf2f822b313f5a6446a6c9f6fa78bed5de89d569f75900e9a7fa035b2438
backup: /var/lib/hermes/.hermes/backups/group-history-readonly-20261021T160245Z

## 次の操作

2026-10-23追記：Slack親threadを読み直したところ、本人側（Claude経由）から、Hermesの
利用制限のため手動でbefore/after hashを照合し、group_api.pyのみ差し替えてDashboardを
起動、activeになったとの報告があった。別gateway・lock・group_service.pyは変更していない
との申告。これは本人の適用報告であり、こちらで認証済みAPIや稼働hashを再検証した証拠ではない。
下の手順1〜3は再実行せず、残作業は手順3の事後確認と手順4。Hermes側はprovider利用制限中。

1. 本人はDashboardで実行中の作業がないことを確認し、新規操作を控えた状態でサーバー端末から
   `sudo systemctl stop home-hermes-dashboard.service` を実行、停止したと返信する。
2. 通知後、Slack #hermes-bro C0C74STS7D4の同じ親thread1792579272.392279でHermes U0C7537MQTGをmentionし、
   停止境界とbefore hashを確認して当該APIのみ差し替えるよう依頼する。許可は既にあるので取り直さない。
   登録済みrouterには正規reloadがなく、rescan/hot-mountは差し替えにならない。注入・monkey patchは禁止。
3. 相手の適用・hash報告後、OS管理者認証が必要なら本人へ `sudo systemctl start home-hermes-dashboard.service`。
   health/providers200、未認証401、service/NRestarts、after hash、保持するgroup_service/mobile_display/auth/config、
   gateway稼働とDB整合を確認。未認証401を失敗・rollback理由にしない。
4. 同じ認証済みbrowserのgroup history URLまたはMobileの再試行で一覧成功と警告解消を確認する。
   成功応答は本文/タイトルを共有させず、成功したとだけ通知してもらう。パスワードやcookieは求めない。

# Phase 3：企業1社のマイページSDUIとApp更新の最小縦通し

2026-10-03。既存 `feat/career-coach / 719a809` の未コミット変更を含む現在ツリーから隔離コピーで実装。旧Phase 2Aを丸ごと取り込まず、既存ContributionRegistryに独立したSDUI destinationを1件追加した。Phase 2B、全Appの動的discovery、Web Mini App、Hermes core変更は対象外。

## 動く範囲

- 企業詳細の概要から「マイページの状況を見る」を開く。最初はmanifestと画面のGETだけ。
- 6種のnative componentと4種の固定actionだけを許可。既存Mono Expressive、Scaffold、状態表示、認証済みApiClientを使用。
- 手動確認は既存MyPageBotStoreの接続とRunを使用。未接続企業で従来のブラウザ収集へfallbackせず、既存設定へ戻れる。
- 同一操作IDの受付結果を永続化。同時操作は既存Runの重複抑止と操作ID予約で保護。接続先・profile・企業・releaseとの結合を検査する。
- 新APIは既存Dashboard session/origin guardの内側。browser credential runtimeフラグ、許可、cron設定を変更しない。
- 画面JSONとApp専用Bot entryを同じリリースpayloadとして作業コピー→差分→SHA-256→固定合成テスト→明示的review hash→合成反映→コード復旧する。

## 独自API v1

既存 `/api/plugins/career` 配下：

| Method | Path | 役割 |
|---|---|---|
| GET | `/mobile/manifest` | 固定App ID、component/protocol version、release、許可の対応宣言 |
| GET | `/mobile/sdui/company/{company_id}` | その企業・profileの配信画面 |
| POST | `/mobile/actions/mypage.check` | UUID付きの明示的な手動確認受付 |

全て `profile` queryを使う。App IDは `career.mypage`。protocol/componentは1。POSTは `protocol_version, release_id, company_id, operation_id` のみ。manifest宣言を端末のブラウザ権限として扱わない。

Components：Section、Column、Text、Status、KeyValue、Button。actions：refresh、mypage.check、native.settings、native.browser。任意URL/path/HTTP/JavaScript/shell/DEX/class名は禁止。未知version/field/action/component、64KiB超、64node超、深さ6超を拒否。画面を部分的に解釈して実行しない。未知契約/旧サーバーは利用不可表示と既存native導線を残す。

## App編集・更新の境界

`server/career/app_runtime/` の `protocol.json`, `mobile_screen.json`, `mypage_handler.py` が専用payload。作業コピーにDB、資格情報、実サイトrecipe、Hermes coreを入れない。

`server/career/tools/app_release.py` は **合成環境専用**。新規の `career-mypage-synthetic` ディレクトリと固定markerを要求し、3ファイル以外・リンク・改ざん・古い基準・未テスト版・review hash不一致を拒否。反映/復旧はatomicな `active.json` の切替だけで、観測データを上書きしない。HTTP編集/反映endpointはない。

初期本番ルーターは同梱payloadを使用し、外部release rootを探索しない。テスト用runtimeは明示的なconstructor injectionでのみ差し替える。Appの編集権限も反映権限も本番へ付与していない。

Pythonのファイル範囲は編集・成果物の境界であり、**Python実行を隔離するOS sandboxではない**。固定テストも候補のApp Pythonを信頼されたローカルプロセスで実行する。本番Hermesに自動編集を任せるには、専用uid/container等の隔離、code-only deploy adapter、idle確認、監査・権限境界を別途検証して承認する必要がある。稼働Hermesの自己改修が完成したとは扱わない。

## 導入の順序（未実施）

1. 変更ハッシュとdiffを確認。更新された本番Hermesの現在version・plugin mount/auth契約を確認する。9月27日のfixtureを最新契約として扱わない。
2. まず独立した合成/stagingで既存pluginルーターと新API、認証/旧APK互換を検証。App専用Pythonはコードレビュー後に実行する。
3. 初回APKはhost/renderer/actionを内蔵。applicationId、署名、データを保持する更新として導入する。アンインストール/データ消去は不要。端末導入は別の明示的許可が必要。
4. 対象pluginのcode-onlyバックアップを取り、配信payloadとAPIをまとめて導入する。既存DB・credentials・recipesは退避/コピーで上書きしない。稼働サービスの復旧/再起動は本作業に含めない。
5. 実機で表示→明示的確認→同一Run表示→接続切替→既存native画面を確認。実資格情報runtimeは別途承認されるまでOFF。
6. APKなしの次回UI/内部Bot更新は初回host契約内で可能。ただし本番release loader/deploy adapterの有効化は別段階。新component/action/native権限にはAPK更新が必要。

## 復旧

- 合成環境：`app_release.py rollback --fixture-root <.../career-mypage-synthetic> --expected-active <現SHA256>`。想定版・復旧先payloadのhash一致を確認してからpointerを戻す。
- 本番：今回の合成CLIを本番ディレクトリへ使わない。旧pluginコードをcode-onlyで戻し、更新後に作成されたDB/観測データを維持する。server停止/復旧は別の承認済み手順に従う。
- 新APKと旧サーバーの組合せは新画面のみ未対応表示となる。既存企業/相談/並べ替え/ブラウザルートを保持。
- operation予約後にサーバープロセスが失われ、受付結果を保存できなかった場合は `operation_pending` として新規Runを作らない。自動再enqueueで解消せず、該当operationとRunを操作者が照合する。APIから予約を解除する機能はない。

## 検証の区分

ローカル合成API、App単体テスト、固定releaseテスト、Androidコンパイル/静的チェックを証拠にする。端末表示・実資格情報・更新後本番Hermesとの通信・本番deployment・7日運用の完了とは区別する。具体的な実行結果とAPKハッシュは成果reportを参照。

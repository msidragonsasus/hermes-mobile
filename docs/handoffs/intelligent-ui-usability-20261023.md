# Hermes Intelligent UI — 2026-10-23 最終状態

## 現在の状態

- Android v48（1.0.48-local）：ビルド・対象単体64件・Android操作10件が成功。Driveへ配布済み。実機へのインストールと実Mobile応答は未確認。
- default profileのmobile_display：0.2.0→0.3.1へ本番反映済み。稼働gatewayのnative plugins.listでversion0.3.1/enabled=true/tools0/hooks1/error空を確認。
- 古い429は2026-10-22 21:06の記録。現在の制限と誤認していたが、2026-10-23にHermesの正常応答を確認して訂正。利用制限を理由に保留する必要はなかった。
- 実ワーカーのメモリ内hook本文・新規static組立結果のハッシュと、実Mobileターンへの適用は未確認。隔離プロセスの検証と区別する。

## 修正内容

文字と余白をコンパクトにし、数値と単位を横並びにした。関連する入力や指標を共通のグループで表示する。表示枠の上限は通常520→1200dp、展開2000dp。長いUIには上部へ「全体を表示」を出す。展開しても同じWebViewと入力状態を保持する。より長い内容はiframe内スクロールで読む。

シミュレーターでは入力→結果→グラフとする生成指示へ変更。0.3.0レビューで継続会話フックに古い「結果先行・入力は下」の文が残る不整合を発見し、0.3.0は適用しなかった。0.3.1ではstaticと継続Mobile hookの双方が同じstyle_instructions()からSTYLE_GUIDE.mdを取得する。Androidの共有ガイドとも一致する。

Androidの文字拡大・チャット文字設定・オフラインsandbox・権限拒否を維持。外部ネットワーク、ストレージ、native bridge、追加モデル/APIは導入していない。古い会話に保存されたHTML自体を並べ替える変更ではない。

## 検証

- 対象JVM64件、Android操作10件、checkColorLiterals成功。全JVMスイート成功とは主張しない。
- nativeタッチで月30,000円→75,000円、表示資産1,217.4万円→3,043.5万円を確認。
- グラフ下に移した操作欄へ指の縦スクロールで到達し、スライダーを操作できた。
- NISA幅320dp/文字1.3倍、回路幅320dp/文字1.5倍を確認。system1.25×chat1.2でtextZoom150。
- 本番Kotlin builderから出したHTMLをブラウザー幅320/390で確認。入力・期間・利回り・リセット・比較・details、積立0・負利回り・元本上限を確認。操作対象は44px以上、狭幅の横はみ出しなし。
- pluginのローカル23件中20成功、実ホスト契約3件はローカル環境不在でskip。サーバーでは実ホストのPython/bootstrap/PluginManager/PluginContextを使う隔離検証8件がすべて成功。static3790文字/hook2005文字、入力先行ガイド共有と旧controls belowの不在を確認。
- 最終Androidスクリーンショット13枚を保存して目視確認。エミュレーターとローカルプレビューは終了済み。
- ユーザー提供NISAソースは途中で切れていたため、検証用NISAは完全な代表例。元の出力の完全再現や実モデル生成の証拠ではない。

## 配布物

APK v48（アプリIDと前版debug署名を維持。アンインストール・データ消去不要）
https://drive.google.com/file/d/1SVMxU3MheDZEjmTwuzmah2D2vgmLbUsz/view?usp=drivesdk
47,016,664 bytes / SHA256 82e22c536109c99e7fbb3e9a6dba909d9602f619bf4c1a64d1cd88e8252ca0d8
署名SHA256 8561de18c9143b61a26d970ec28c30a20f4260ffba6879eab8250badc3f3524b

サーバーplugin0.3.1（以前の0.3.0 DriveファイルIDを最新版へ置換済み）
https://drive.google.com/file/d/15V7W5rceUbQ0WM9CTr1fRLr9t-24YSqf/view?usp=drivesdk
7,894 bytes / SHA256 5a682e581214007eb2cd513a660ff1a9f0db0dae91f9dc68f42139a55c270fb3
Driveは名前・MIME・sizeのreadback確認済み。リモートchecksumはコネクタが返さず未検証。共有権限は拡張していない。

## サーバー反映の証拠

https://chatgpt-lll7864.slack.com/archives/C0C74STS7D4/p1792714503743759

実行直前はactive1件/same_request=true/今回以外の作業0件。本番before・候補after・設定hashが一致してから7ファイルだけを配置し、配置後hashを照合。標準CLI disable→enable --no-allow-tool-overrideが両方exit0、gatewayのreload応答あり。

profile全体の標準再検出は発生したが、gateway PID2029151は不変、Slackターンも継続。設定SHA256 9c4b8b2c4995014303049d55fe532b61c27362c1448c3817e519625ca64bd2a0と他pluginソース380ファイルは不変。gateway drain/restart、履歴・認証・lock操作、接続先変更は実施していない。

バックアップ：/var/lib/hermes/.hermes/backups/mobile-display-031-20261023T000442Z/

## 残る確認と境界

v48で実際のMobileターンと新規セッションを表示し、操作と生成内容を確認する。既存会話のcached system promptは書き換えていない。更新hookは以後のMobileターン、更新staticは新規セッション向け。

履歴修正group_api.pyは本人がClaude経由で別途手動適用済みと報告しており、再実行しない。group_service.py・別gateway・worker lock・認証・DBは今回の修正対象外。Hermesへの依頼はSlack #hermes-broのみ。Relay/SSH/direct HTTPの代替依頼経路を使わない。

ローカル証拠：output/intelligent-ui-density-20261023/validation.json、android-final/、plugin-tests-0.3.1.log、gradle-final.log、native-final.log。

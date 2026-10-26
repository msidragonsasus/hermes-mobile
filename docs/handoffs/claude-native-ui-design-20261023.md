# Claude向け：Hermes Mobile 対話UIのデザインレビュー

2026-10-23作成。対象は **v50のネイティブ対話UI**。まず見栄え・視認性・操作感をレビューし、具体的な改善案を出してほしい。ユーザーは「Claudeにデザイン見てもらうから。引き継ぎ資料出して」と依頼している。この資料だけでコード変更・配布・サーバー操作まで依頼されたとは解釈しない。

## 最初に読んでほしいこと

Hermes MobileはAndroid / Jetpack Composeの会話アプリ。回答内に、積立シミュレーター・比較表・グラフを表示する。従来はモデルがHTML/JSを生成していたが、v50では短いJSON設定を、アプリ側の部品ライブラリで描画する方式を追加した。OpenAIのIntelligent UI内部実装を再現・検証したものではなく、Hermes独自の実装である。

機能テストは通っているが、デザインの完成度は改めて評価してほしい。「Composeの標準部品だから良い」という判断は不要。ユーザーは対話UI内の厳密なMaterial Design準拠にはこだわっていない。一方、アプリのテーマ・読みやすさ・操作の一貫性は保つ。

ユーザーの問題意識は次のとおり。

> 「Hermesの方は見栄えが悪いというか視認性が悪い上に操作がまともに効かない。操作するための場所が表示すら出来なかった点はかなり問題」
>
> 「滑らかじゃない。バーはタップしないと反映されない、もっとインタラクティブに、ライブに」
>
> 「別にここはmaterial designには拘らんけども」

これらは旧HTML方式へのフィードバック。v49で旧HTMLのタッチ競合を修正し、v50でネイティブ部品方式を追加した。**旧スクリーンショットを現在のv50画面と取り違えないこと。** 操作の発見しやすさとライブ感は、デザイン変更後も必須。

## 渡すファイルと見る順序

ZIPを展開して、このREADME → screenshots/current → screenshots/reference → sourceの順に確認する。ZIPはレビュー用のソース抜粋であり、そのままビルドできる完全なチェックアウトではない。

| パス | 内容と証拠の範囲 |
| --- | --- |
| `screenshots/current/native-savings-held.png` | v50・ダーク。月額を指で変更中の積立UI |
| `screenshots/current/native-large-text-light.png` | v50・ライト・文字1.5倍。条件、結果、グラフの配置 |
| `screenshots/current/native-chart.png` | v50の系列切替・選択位置つきグラフ |
| `screenshots/current/native-four-series-bars.png` | v50の4系列・負値を含む棒グラフ |
| `screenshots/current/native-comparison.png` | v50の比較表・選択状態 |
| `screenshots/current/native-mixed-stream.png` | v50の複数部品を含む生成途中の例 |
| `screenshots/reference/old-hermes.jpg` | ユーザー提供の旧Hermes。巨大な結果、下方に隠れた操作部が問題になった画面 |
| `screenshots/reference/chatgpt-reference.jpg` | ユーザー提供のChatGPT参考画面。条件→結果→推移の情報設計が参考。画面の丸写しは要求していない |
| `source/` | 現在の未コミット実装から採った、対象コード・テスト・設計資料 |
| `examples/` | 描画用の短いJSONL設定例 |
| `evidence/validation.json` | 前回実装時の検証・配布状態の記録 |
| `evidence/implementation.md` | プロトコル・状態保持・採用判断の詳しい実装記録 |
| `evidence/touch-latency.txt` | エミュレーターでの指保持中の更新計測 |
| `MANIFEST.sha256` | 同梱ファイルのハッシュ |

currentの6枚はエミュレーター上で、本番のMarkdown描画と履歴ジェスチャーを使ったテスト画面。入力データは手書きfixtureである。**実機や実モデルが生成した会話のスクリーンショットではない。** アプリ全体のヘッダー・入力欄・ナビゲーションを再現した画面でもない。ライト画像のステータスバーなど、テストActivityの外装は本番不具合の証拠にしない。

前回配布の[最終v50 APK](https://drive.google.com/file/d/1U6W7sKiHZvTY37ti_iwV6gpX5-TIkyAw/view?usp=drivesdk)と[検証資料](https://drive.google.com/file/d/1BFvuNJaqaEsjSl5fo9Dw59nPNlkoMvC7/view?usp=drivesdk)も参照可能。APKはZIP内に重複同梱しない。

## レビューしてほしい点

まず画面から判断し、必要に応じてコードで原因を確認してほしい。以下は確認候補であり、すべてを確定不具合と主張しているわけではない。

1. **条件と結果の関係**：初見で動かす場所が分かるか。条件を確認しながら変化を読めるか。余白やリセットの配置で、グラフが過度に押し下げられていないか。
2. **スライダー**：トラック・ハンドルの存在感が強すぎないか。見た目を軽くしても48dpの操作領域、連続更新、アクセシビリティを維持できるか。月額100円刻みなど、表示精度と操作精度が用途に合うか。
3. **数値の階層**：最終資産、元本、損益、円/万円、桁区切り、符号の読み分け。狭幅・大文字でも重要値を欠落させない設計。
4. **グラフ**：軸・単位・凡例・選択値の関係、系列切替の発見しやすさ、スクラブと縦スクロールの共存。色だけに頼らない系列識別。
5. **比較表**：選択チップの意味、横スクロールの案内、列数が増えたときの読みやすさ、選択と表示内容の対応。
6. **途中・例外状態**：逐次追加、不正な後続行、生成中断、空/ゼロ/負値/大きな値で、何が起きているか分かるか。
7. **相談への接続**：「この条件で相談」は入力欄に下書きを追加する動作。ラベルとフィードバックが、自動送信と誤解されないか。

余計な装飾やアニメーションを足すより、条件・結果・図の読みやすさと直接操作の気持ちよさを優先する。積立の例だけを作り込むのではなく、比較・汎用グラフでも一貫して使える規則にしてほしい。

## 現在の実装と変更時の注意

モデルは `hermes-ui-v2` フェンス内にNDJSONを出力する。カタログ名は `hermes.native/1`、JSONの版は `v:1`。フェンス名のv2とスキーマのv1は別物。現行はsavings / comparison / chart（line・bar）の3種。閉じるフェンスを待たず、検証済みの完成行から表示する。

```hermes-ui-v2
{"v":1,"id":"plan","type":"savings","title":"積立試算","monthly":30000,"years":20,"rate":5}
{"v":1,"id":"choices","type":"comparison","title":"候補比較","columns":["候補","利点"],"rows":[["A","軽い"],["B","大きい"]]}
{"v":1,"id":"trend","type":"chart","title":"推移","labels":["開始","終了"],"series":[{"name":"値","values":[0,10]}]}
```

| 主要ファイル（source内はリポジトリと同じ相対位置） | 役割 |
| --- | --- |
| `app/src/main/java/com/m57/hermescontrol/ui/chat/NativeUiCard.kt` | 主なデザイン変更点。NativeSavings / NativeRange / NativeMetric / NativeComparison / NativeChart |
| `.../NativeUiProtocol.kt` | 検証、更新順序、型、積立計算。デザインレビューだけなら変更不要 |
| `.../NativeUiState.kt` | 会話・ターン・部品の識別、入力保持、暗号化設定への保存 |
| `.../NativeUiInstructions.kt` | モデルへ渡す短い仕様。server/mobile_display/NATIVE_CATALOG.mdと一致をテスト |
| `.../MarkdownText.kt` | 新旧フェンス振り分け、生成中の表示、Markdown画像 |
| `.../fullbleed/FullBleedChatList.kt` | ターンの識別、ストリーム表示、本番会話への統合 |
| `.../ChatScreen.kt` | 現在の入力欄へ相談用の下書きを追加 |
| `.../ConversationHistoryGesture.kt` | 会話の横スワイプと子の操作の調整 |
| `.../IntelligentUiCard.kt`ほかIntelligentUi*.kt | 旧HTML方式。過去の回答・独自図解向けに残す |
| `app/src/androidTest/java/com/m57/hermescontrol/ui/chat/NativeUiRenderingTest.kt` | ネイティブの操作・表示・状態保持テストとfixture |
| 同ディレクトリの`IntelligentUiRenderingTest.kt` | 旧HTMLの操作回帰テスト |
| `DESIGN.md` | Design continuity / Native interactive answers / Accessibilityを優先して読む |

実装に進む場合も、次を維持する。

- ドラッグ中に**指を離さず**数値・結果・グラフをローカル更新する。ネットワークやモデル呼び出しを挟まない。見た目の補間で結果を遅延させない。
- グラフの水平スクラブは水平ドラッグのみで捕捉する。縦方向は外側の会話スクロールへ渡す。履歴横スワイプのために子のスライダーを奪わない。
- 後続モデルrevisionが届いても、ユーザーが変更した値を初期値へ巻き戻さない。不正更新も有効な表示を消さない。
- 積立の値は会話・接続先・ターン・部品ごとに保持する。ドラッグごとのディスク書込はせず、操作終了時に既存の暗号化設定へ保存。保持は最大128部品で、すべての履歴再構成での復元保証はない。
- 「相談」は既存下書きを残して現在値を追加する。自動送信しない。送信ACK / Unknown、認証、履歴取得の既存処理に触れない。
- 任意JS・式・ツール実行・URL取得をネイティブ部品に追加しない。表示能力はツールやアカウント操作の権限ではない。
- 古いHTML回答は自動変換されない。既存の隔離HTML描画・タッチ修正、Markdown画像表示を維持する。
- 積立は汎用計算器。NISA制度や税制を判定する機能ではない。前提と上限を表示し、サンプルの税率・制度説明を無検証で組み込まない。
- モデルに毎回HTML/CSSを書かせないことがトークン削減の狙い。外観調整をアプリ側で完結させ、カタログ説明を膨らませない。

## 採用判断と検証の範囲

コードを書く前にAndroidX A2UIとDivKitの公開実装を確認した。A2UIの標準Material描画alpha02は、現行アプリと異なる厳密なMaterial3/Compose依存を要求していた。今回は既存Compose + kotlinx.serializationで小さな型付きカタログを作り、新規ランタイム依存は追加していない。**A2UI互換とは称していない。** 詳細と一次資料リンクはimplementation.mdを参照。

前回実装時の結果（今回の資料作成で再実行した結果ではない）：

| 項目 | 確認状態 |
| --- | --- |
| 対象JVMテスト | 82件成功。全JVMスイートではない |
| Android操作テスト | エミュレーターで25件成功：native13 / legacy HTML12 |
| ビルド等 | Debug / AndroidTest / release Kotlinコンパイル / 色リテラル検査 / 変更範囲ktlint成功 |
| 全体lint | 長時間のUAST探索後に中断。未完了、成功ではない |
| 指保持中の更新 | 12段階で表示更新。注入→次描画p95 72.6176ms。小標本のエミュレーター参考値で、FPSや実機性能ではない |
| トークン | 積立サンプルが参考tokenizerで3048→64、表示指示40.06%削減。実モデル課金・複数用途の中央値は未測定 |
| サーバー | 前回HermesのSlack報告でmobile_display 0.4.0有効化を確認。今回再確認はしていない |
| 実機・ライブモデル | 未検証。実モデルが作った画面の操作性を保証する証拠はない |

レビュー後に実装するなら、ダーク/ライト、幅320・390dp程度、文字倍率1.0/1.5、日本語の折返し、TalkBack/キーボード、0円・負利回り・上限到達・多系列・途中終了を確認する。静止画だけで操作の滑らかさを合格にしない。

## 作業環境と再現

実際のGit rootは `E:/OSHIIRE/software/Hermes/hermes-mobile`。親のHermesはGit rootではない。

- remote：`https://github.com/msidragonsasus/hermes-mobile.git`
- 資料作成時branch：`feat/career-coach`
- HEAD：`719a809810f69503e2aafcc5ba5a0e295b63e27f`
- 既存の大量の未コミット変更あり。今回の実装も未コミット・未push。**GitHubのHEADだけではこのソースにならない。ZIPのsourceは現ワークツリーからの抜粋。** 変更ファイル全体を今回の差分と扱わない。
- JDK21：`C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot`
- Android SDK：`C:/Users/keima/AppData/Local/Android/Sdk`
- Gradleは `--max-workers=1` で直列実行する。既存ビルドを並列に走らせない。
- 検証用エミュレーターは前回終了済み。物理端末へインストールした証拠はない。

完全なチェックアウトで、変更に合わせて対象テストを選ぶ。例えばPowerShell：

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot'
$env:ANDROID_HOME='C:/Users/keima/AppData/Local/Android/Sdk'
.\gradlew.bat --max-workers=1 testDebugUnitTest --tests com.m57.hermescontrol.ui.chat.NativeUiProtocolTest --tests com.m57.hermescontrol.ui.chat.NativeSavingsTest
.\gradlew.bat --max-workers=1 assembleDebug assembleDebugAndroidTest compileReleaseKotlin checkColorLiterals
```

操作テストは`NativeUiRenderingTest`と`IntelligentUiRenderingTest`。コードのtest fixtureと既存の実行記録を参照し、端末・出力先を明示して実施する。ユーザーデータのクリア・アンインストールでテストを通さない。以前のAPK版・署名・アプリIDはvalidation.jsonにある。

リポジトリのAGENTS.mdを必ず読む。端末/サーバー配布、外部送信、push/PR等はレビュー依頼から自動的には許可されない。Hermesへ依頼する際の経路はSlackの **#hermes-bro**（workspace `T0C7AE5UBGC`、channel `C0C74STS7D4`、Hermes `U0C7537MQTG`）のみ。Relay・直接HTTP・SSH・旧試行スクリプトへの迂回は禁止。

## ほしい返答

1. 現状の問題を最大5件、優先順に。各項目に「該当画面/コード」「ユーザーへの影響」「具体的な変更案」を付ける。観察した事実と、追加操作が必要な仮説を分ける。
2. 第一候補のレイアウト案。条件・結果・グラフ・補足・相談の配置、字の階層、余白、操作状態を具体化する。画像かワイヤーがあれば歓迎。
3. 実装する場合の変更対象と、残すべき挙動、検証方法。スクリーンショットだけでライブ操作の検証済みとはしない。

ユーザーがその後に実装を依頼した場合は、前後の画面比較と、指保持中の更新・縦スクロール・入力保持の回帰確認まで行ってほしい。

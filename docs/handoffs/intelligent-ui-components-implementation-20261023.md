# Hermes 部品方式の対話 UI 実装

2026年10月23日。Android v50 / Hermes catalog hermes.native/1。

積立計算、比較表、折れ線・棒グラフを、短い設定データからComposeで描画する。モデルがHTML・CSS・計算コードを毎回書く必要がなく、完成した部品から回答終了前に表示できる。旧HTML回答とMarkdown画像の経路は維持する。

## 構成と採用判断

AndroidX A2UIのengine/model、標準描画のソース・配布POMを確認した。`material3-a2ui:1.0.0-alpha02` の配布POMはMaterial3を厳密に `[1.5.0-alpha28]`、Compose UI/runtimeを1.13.0-alpha03としている。現在のアプリはMaterial3 1.5.0-alpha29を使用しており、標準描画導入には既存依存関係の調整が必要になる。今回は既存のkotlinx.serializationとComposeを使う小さなカタログとし、A2UI互換は宣言しない。公開SDUIのDivKitも比較候補として確認したが、同じ機能のために別の描画基盤を重ねて導入しない。

一次資料: [AndroidX A2UI](https://developer.android.com/jetpack/androidx/releases/a2ui)、[標準描画の配布POM](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-a2ui/1.0.0-alpha02/material3-a2ui-1.0.0-alpha02.pom)、[DivKit](https://github.com/divkit/divkit)。公開実装からコードをコピーしたものではない。

画面は、静かな面と整列した数値を基本にする。条件→結果→グラフ→前提→相談の順に置き、入力の発見しやすさを優先する。操作は連続更新、グラフ位置の選択、系列表示の切替とし、数値の追従に余分なアニメーション遅延を加えない。Materialの外観を厳密に再現する要求はなく、既存テーマの文字・色・形・操作部品を再利用する。

## 実装箇所

| ファイル | 役割 |
| --- | --- |
| NativeUiProtocol.kt | 有限の型、制約、NDJSON解析、更新順序、月末積立計算 |
| NativeUiCard.kt | savings/comparison/chartのCompose描画とローカル操作 |
| NativeUiState.kt | 接続先・会話・ターン・部品のキー、入力保持、暗号化設定への保存 |
| NativeUiInstructions.kt | モデルへ送る短いカタログ仕様 |
| MarkdownText.kt | 新旧フェンスの振り分けと生成中の部品表示 |
| FullBleedChatList.kt | 本番会話のターン識別、typing演出によるJSON破損・表示遅延の回避 |
| ChatScreen.kt | 現在の会話に結び付く「この条件で相談」の下書き追加 |
| server/mobile_display | 新旧端末の能力分岐、指示の重複削減、plugin 0.4.0候補 |

就活用SDUIの検証・描画パターンを参考にしたが、career.mypageのID・権限・API・操作契約をチャットへ流用していない。単一`:app`、署名、アプリID、既存DBを維持し、新規ランタイム依存を追加していない。

## モデル出力の契約

新しいフェンス名は `hermes-ui-v2`、内部カタログの `v` は `1`。これはHermes独自の形式で、A2UIのプロトコル版とは別物。

```hermes-ui-v2
{"v":1,"id":"plan","type":"savings","title":"積立試算","monthly":30000,"years":20,"rate":5}
{"v":1,"id":"options","type":"comparison","title":"候補比較","columns":["候補","利点"],"rows":[["A","軽い"],["B","大きい"]]}
{"v":1,"id":"trend","type":"chart","title":"推移","labels":["開始","終了"],"series":[{"name":"値","values":[0,10]}]}
```

一行につき一つのJSON object。外側の配列は付けない。共通項目はv/id/type/title、任意でnote/rev。更新は同じidへ、より大きいrevを指定する。重複・古いrevisionは適用しない。種類の変更では既存部品を置き換えない。新規部品は最大12個。

積立部品はJPY、月末積立、実効年率を月率へ換算する。既定値は月30,000円・20年・年率5%・月額上限100,000円・新規積立元本上限18,000,000円・開始資産0円。開始資産は新規積立枠に含めない。制度や税制の判定をする機能ではなく、前提と上限は画面にも表示する。年数1..60、年率-20..30、月額上限は最大10,000,000円、開始資産と積立元本上限は最大1,000,000,000円。端数の最終積立額も上限を超えない。

比較表は2..5列・1..30行の文字列。グラフは2..120個のラベルと同じ長さの有限数値列、最大4系列。未知の項目・部品・版、過大サイズ、非有限数、深すぎる構造を拒否する。コード・式・外部URL・ツール操作を実行する契約はない。

## 逐次表示と状態

既存のテキストストリーム内から、構文・型の検証を通過したJSON行を順次表示する。閉じるフェンスや回答全体の終了は待たない。途中のJSONは待機し、不正な後続行は最後に有効だった部品を壊さず警告を表示する。全体が64,000文字を超える場合も、制限内の有効な先頭部分を保持する。

ターンのキーは直前ユーザー本文・タイムスタンプと応答内の位置から作り、応答の一時IDからREST側IDへの変更に依存しない。接続先・ローカル接続profile・実行profile・会話・部品を分離し、キーはSHA256で保存する。ユーザーが変更した値を、後続revisionの初期値で巻き戻さない。

積立の月額・年数・年率は、メモリおよび既存の暗号化設定へ最大128部品分保持する。ドラッグ中はメモリだけ更新し、操作終了時にIOスレッドで保存する。比較の選択とグラフ位置はComposeの保存可能状態で保持する。128件を超えた古い入力、保持された直前ユーザー行がページングで変わる場合、別のユーザー本文として再構成された履歴は、同じ入力を復元できる保証の対象外。

「この条件で相談」は現在値・計算前提・結果を現在の入力欄へ追加する。既存下書きを上書きせず、自動送信しない。通常の送信・認証・Unknown送信の扱いは既存経路に任せ、ドラッグイベントをモデルへ送らない。

## 検証と測定

- 対象単体82件、8スイートが成功。全JVMスイートとは区別する。
- 新旧Android操作25件が成功。新方式13件には開いたフェンス、指を保持中の入力・結果・グラフ更新、raw touch注入、後続revision、入力復元、明暗・文字1.5倍、比較行の並べ替え後の選択保持、グラフ選択と縦スクロール、途中終了、負値を含む4系列棒グラフを含む。旧HTML方式12件は別経路の回帰確認。
- Debug APK / AndroidTest APK / compileReleaseKotlin / checkColorLiterals / 変更ファイルのktlintが成功。
- 全体lintDebugはUASTのソース探索に長時間を要し、完了前にこのタスクのGradleクライアントを中断した。成功とは扱わない。daemonはクライアント切断を検出してキャンセル・終了した。
- モデル・有料APIを呼ばず、tiktoken o200k_baseで参考比較を実施。既存の積立HTML fixtureは3,048、新しい設定記述は64トークン相当で97.9%削減。表示指示一式は参考計測で40.06%削減。これは単一fixtureであり、実モデルの生成・課金・中央値50%目標達成の証拠ではない。
- 実モデルの最初の操作可能表示までの待ち時間、実機、実際に生成されたMobile回答は未検証。エミュレーターの操作検証と区別する。

エミュレーターの入力注入から次の描画までの最終12サンプルはp95 72.6176ms。指を保持したまま31,700円から78,800円まで12段階の表示更新を確認した。記録は `output/intelligent-ui-components-20261023/android-final/native-ui/touch-latency.txt`。これは表示経路の参考値で、フレームレートや実機性能の保証ではない。

再現: `scripts/benchmark_native_ui.py`。結果は `output/intelligent-ui-components-20261023/token-comparison.json`。詳細な最終状態・APK配布情報・サーバー反映結果は同ディレクトリのvalidation.jsonへ保存する。

## サーバー反映と配布

対象ZIPはmobile-display-0.4.0.zip、11,855 bytes、SHA256 `7db8542ad99885e80e099a989df73a9f7f34fb885c26cdebc3dfa10d241513ef`。ローカル24件中21件成功、実ホストを必要とする3件はローカルでskip。Hermes側では実PluginManager/PluginContextの隔離登録・dispatch 12ケースと独立テスト9件が成功したと報告されている。

[Slack反映依頼スレッド](https://chatgpt-lll7864.slack.com/archives/C0C74STS7D4/p1792718994432009)。Slack添付が403となったため、同じスレッドでZIPをBase64の4分割にして再送し、Hermes側でサイズ・ハッシュ一致を確認した。正規reloadは同一profileの全plugin再登録を伴うことが判明し、変更しないpluginの通常再登録は許容する旨を明確にした。ファイル・設定・権限を変更する対象はmobile_displayに限定する。gateway/Dashboard再起動、drain、他作業中断、認証・履歴・lock・cached promptの変更、追加モデル/API呼出は依頼していない。

既存HTML回答の自動変換は行わない。新方式の生成は新しい表示能力を送る端末に限定し、旧端末には旧方式の指示を維持する。

## 最終配布状態

- [v50 最終APK on Drive](https://drive.google.com/file/d/1U6W7sKiHZvTY37ti_iwV6gpX5-TIkyAw/view?usp=drivesdk)
- versionCode 50 / versionName 1.0.50-local / applicationId com.m57.hermescontrol
- 47,171,937 bytes。Driveの名前・ID・MIME・サイズを再取得して照合した。
- SHA256: `a2fcc3b1caa7bf832ef72823cc4909deee743437e17a4cd02dc91ceaaa3b8a8e`
- 署名SHA256: `8561de18c9143b61a26d970ec28c30a20f4260ffba6879eab8250badc3f3524b`。従来と同一で、上書き更新用。実機への導入は行っていない。
- 初回Drive候補 `1FuTM0JEcu87OvBV6-sjJdNREz0AkOtnz` は最終版ではない。途中終了表示・グラフ縦スクロール・比較選択保持を含む上記finalを使う。
- 検証用emulator-5584は停止済み。アンインストール・アプリデータ消去は行わず、firstInstallTimeを保持した。

[Hermesの反映完了報告](https://chatgpt-lll7864.slack.com/archives/C0C74STS7D4/p1792720206372149)で、正規disable→enableによる0.3.1→0.4.0の反映・稼働登録を確認。短命PM worker、通常install lock、処理記録の書込みを正規更新の必要な内部手順として明確化し、環境のdesired stamp一致を確認して実施された。依存132 packageの名前・version、選択環境・lock・factsが保持され、全65 pluginの登録比較で対象以外が一致し、設定のバイト一致・他pluginの読取可能ファイル不変も報告された。gateway PID 2029151、Dashboard PID 2184728を保持し、再起動・drain・所有lock強制解除・追加モデル呼出は行っていない。

稼働メモリで確認されたのは登録メタデータまでで、callback本文のハッシュ・実Mobile生成は未確認。既存会話のcached system promptは書き換えず、静的system sectionは次の新規sessionから適用する。継続Mobileには現在のdescriptorに応じるhookを登録した。最終報告の写しは `output/intelligent-ui-components-20261023/server-deployment-evidence.txt`。

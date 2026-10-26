# Hermes Mobile Intelligent UI 引き継ぎ

## 依頼と完成条件

ユーザーは、会話内でグラフ・図解・操作UIを表示する機能と、エージェントが画像を見せる機能を希望している。
HTMLプレビューの初期実装に対して「ダサくないか」「自分自身をREAにかけることできないんか？」と指摘。
現在の依頼は「なんとかして❤ この作業苦手ならclaudeに投げるから、引き継ぎ書いてよ」。

単にHTMLが実行できるだけでは完成ではない。図・結果を主役にし、ホストと文字・色・余白・角丸を揃え、
入力を動かした結果が分かること。明暗テーマ・狭い画面・大きな文字で確認し、実装とエージェント向け指示を一致させる。
エージェントが「このTUIでは画像を添付できない」と回答しないよう、Mobileの表示能力を認識できる経路も必要。

## 作業場所と必須の境界

- Gitルート: `E:/OSHIIRE/software/Hermes/hermes-mobile`
- ブランチ: `feat/career-coach`
- 開始時HEAD: `719a809810f69503e2aafcc5ba5a0e295b63e27f`
- origin: `https://github.com/msidragonsasus/hermes-mobile.git`
- 非常に多い未コミット変更がある共有ワークツリー。Career、グループ、設定など他作業を含む。
  reset/clean/全体フォーマット/一括コミットをしない。最初に現在の差分を確認する。
- `AGENTS.md`、`DESIGN.md`、関係するUX/Architecture/Securityを読む。四角い入力欄、独自の別テーマ、汎用JSブリッジは不可。
- 既存のMaterial 3 Expressive、HermesControlTheme、LocalSpacing、MaterialTheme.shapes、LocalHermesStatusColorsを使う。
- 新しい有料APIやモデルを勝手に使わない。既存モデルで出力する方式。
- 端末のアプリデータ・DB・ペアリング・資格情報を保つ。アンインストール/データ消去で直さない。
- 実機インストール、本番サーバー反映、push/PR、外部送信は許可が必要。
- Hermesへの依頼は必ずSlack workspace `T0C7AE5UBGC` / `#hermes-bro` channel `C0C74STS7D4`、
  Hermes user `U0C7537MQTG`へ。Relay、直接HTTP/SSH、古い試験経路は使わない。
  ユーザーはSlack送信と安全なサーバー反映を明示承認済み。0.2.0 ZIPを送信し、反映と登録確認を依頼済み。
  依頼: https://chatgpt-lll7864.slack.com/archives/C0C74STS7D4/p1792579272392279
  実機APKインストールは未承認。送信完了とサーバー反映完了は区別する。

## REA調査で分かったこと

`E:/OSHIIRE/software/Hermes/output/codex-intelligent-ui-rea-20261021/findings.md`を読む。
このPCのCodex 26.1013.4894.0のapp.asarを読み取り解析した。モデル内部・サーバー処理は未解析。
HTML sandbox経路と事前定義DILウィジェット経路を区別すること。

調査した配布物にはホスト由来の意味的テーマ変数、共通スタイル、内容高通知、inline/fullscreen、
生成中/ready/error、登録ウィジェットの処理がある。HermesのHTMLという土台は維持してよい。
足りなかったのは共有デザインと表示の統合。配布物の独自コード/CSSをHermesにコピーしていない。
今回のホスト・スタイルはHermesの既存テーマから独自に実装した。

同フォルダにREAのsummary/item Evidence、関連参照、抽出20ファイルの出所とハッシュがある。
選択した描画ソースは宣言ハッシュと一致。一部ネイティブ部品のASAR記録との不一致は別途記録し、実行していない。
静的コードがあることはこのアカウントで実際に全機能が有効な証明ではない。

## 主な実装

以下はすべてリポジトリからの相対パス。

- `app/src/main/java/com/m57/hermescontrol/ui/chat/IntelligentUiDocument.kt`
  - `hermes-ui` / `intelligent-ui` / `liveui`の閉じたコードフェンスを実行。ソース上限240,000文字。
  - 生成HTMLはopaque originのiframe、`sandbox="allow-scripts"`、same-originなし。
  - CSPでネットワーク/フォーム/フレーム/ワーカー/外部リソース等を禁止。画像はdata URLのみ。
  - 子のResizeObserverが内容高を外側へ通知。外側は同じ子からの有限・範囲内の高さだけを保持。
- `IntelligentUiStyle.kt`
  - テーマ色、コントラスト、文字サイズ、角丸、余白、グラフ系列色をホストから受ける。
  - `viz`, `viz-header`, `viz-description`, `viz-result`, `viz-value`, `viz-unit`, `viz-controls`,
    `viz-row`, `viz-columns`, `viz-chart`, `viz-legend`, `viz-caption`の共通スタイル。
  - 角丸の塗り入力、48px操作部品、reduced-motion、相対文字サイズ。端末とチャットの文字倍率を両方反映する。
  - 同ファイルのauthoringInstructionsとサーバーのSTYLE_GUIDE.mdは一致テストがある。
- `IntelligentUiSizing.kt`
  - Androidは固定のevaluateJavascript式から表示高の数値だけをpullする。
  - 生成UIへAndroidオブジェクト、任意コマンド/ツール用の受信口、認証情報は渡さない。
  - detach/window非表示でpolling停止。別ドキュメントへ切り替えた後の古い結果を捨てる。
- `IntelligentUiCard.kt`
  - 旧360/640dp固定高を廃止。内容高、最小96dp、inline上限520dp、画面基準の拡大上限。
  - ソース/リセット/必要時の拡大を下のオプションメニューへ移動。コード表示からの戻りはコードの上へ置く。
  - 拡大や内容高の変更でWebViewを作り直さず状態を保つ。リセットとコード切替は再作成。
  - WebViewはfile/contentアクセス、ネットワーク、ストレージ、外部遷移、権限要求、ダウンロードを許可しない。
- `RichAnswerPrompt.kt` / `RichAnswerSubmissionTransport.kt`
  - 普通の送信とsteerへ表示能力・作成ガイドを付ける。保存下書きは元の本文。
  - 表示では完全一致した現行/旧版の生成suffixだけを取り除く。旧版互換を壊さない。
- `server/mobile_display/`
  - Hermes Agentの正式なsystem prompt sectionとpre_llm_callを使う0.2.0プラグイン。
  - 新規会話はシステム指示、継続会話は現在のMobile通知をフックで補足。
  - SYSTEM_CONTEXT.md + STYLE_GUIDE.mdをネイティブ4,000文字上限内で登録。
  - 過去のMobile履歴やtuiという通信名だけで現在の表示環境を決めつけない。
  - 本番反映・実モデル応答は別検証。ローカル`.audit/hermes-agent`はagent/system_prompt.pyがない不完全スナップショットで、本番互換性の証明に使わない。

画像関連はすでに同じ作業で実装済み。`MessageContentText.kt`、`EventParser.kt`、`MarkdownText.kt`などで
REST/WSの画像ブロックを扱い、Markdown画像と単独の実ホスト画像パスを描画する。
コマンドや文章内のパスを勝手に画像へ変換しない。共有GatewayFileClientの認証付きダウンロードを通す。
保存済みGoogle認証スクショのためにログインを再実行しない。

## 検証例と手順

- `app/src/test/resources/rich-answers/`: 割り勘、移動時間比較グラフ、抵抗と電流の図解。
- JVMのIntelligentUiPresentationTestが本番Document builderで明暗6ファイルを
  `app/build/reports/rich-answers/styled/`へ出す。
- `app/build.gradle.kts`の今回の追加は、androidTest assetsから同じsrc/test/resourcesを参照する1行。
  同ファイルには他作業の変更もあるのでまとめて戻さない。
- `IntelligentUiRenderingTest`は専用エミュレーターで実際のMarkdownText/Materialテーマ/WebViewを確認する。
- 専用AVD: `Hermes_IntelligentUI_20261021`。
  データは`output/intelligent-ui-design-avd`内で新規作成したもの。実機や他AVDを変更しない。
  起動ポート5580、adb serialは`emulator-5580`。停止するならこのserialのみ。
- 画像・ブラウザーの成果物は`output/playwright/`。APKは`output/intelligent-ui-layout-fix-build.log`、
  Release Kotlin/JVMテストは`output/intelligent-ui-layout-final-validation.log`、
  最終lint/テストAPKは`output/intelligent-ui-final-ui-checks.log`。
- 最終的な検証結果、APK/ZIPのパスとハッシュは同じ引き継ぎの末尾と成果物validation.jsonを確認。

JDKは`C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot`。Gradleは常に`--max-workers=1`。
ほかのGradle/Node/Codexプロセスを一括終了しない。

```powershell
python -m unittest discover -s server/mobile_display/tests -v
# Native host contract checks additionally use the read-only snapshot:
$env:HERMES_AGENT_CONTRACT_ROOT='E:\OSHIIRE\software\Hermes\.audit\hermes-agent'
python -m unittest discover -s server/mobile_display/tests -v
```

APKビルド、Release Kotlin、lint、色ガード、対象JVMテストを実施する。
Playwrightでは実Document builder出力を使い、操作で数値が変わること、高さの増減、横溢れ、
大文字、テーマ、親DOM/ストレージ/ネットワークの分離を確認する。
ブラウザーだけでAndroid実機表示・本番エージェント対応済みとは報告しない。

## 残る判断

任意HTMLを許す以上、すべてのモデル出力が自動的に美しくなる保証はない。共有部品と良い作例を使わせること。
HTMLの実行基盤・生成ガイド・実際のモデル出力・端末表示の4つを分けて確認する。
ユーザーの機能要求を実機とサーバーへ届ける段階では、既存許可を確認してから指定の経路を使う。

## Android描画の回帰確認

ブラウザーでの表示だけでは不足。WebViewのchild本文が487pxを報告し、native Viewも974pxあっても、
外側のiframeとbodyのoffsetHeightが0となり空白になる現象を専用AVDで確認した。
ハードウェア描画は有効で、ソフトウェア描画への切替では改善しなかった。
メニュー・高さだけのテストが成功しても、この描画不良を見落とすため、表示領域そのものを確認すること。
スクリーンショットはComposeのcaptureToImageではWebViewが含まれないため、UiAutomationを使う。
修正は`IntelligentUiCard.kt`のWebView factoryで明示するMATCH_PARENTのLayoutParams。
AndroidView既定のWRAP_CONTENTでは、100%だけでなく100vhへ変えても解決しなかった。
同じDocumentと同じWebViewでLayoutParamsだけを変え、iframe高0→487pxと実描画を確認した。
本番にも反映済み。captureではiframe.offsetHeightが96px以上であることを検査し、見えない図解の成功扱いを防ぐ。
入力の回帰試験はUiAutomationのアクセシビリティ経由で24,000円へ変更し、6,000円へ再計算されることを検査する。
WebViewのアクセシビリティを最初のWebView作成前から接続すること。数値の検索APIだけでは入力ノードが見つからず、
ツリーを辿り、出力はtext/contentDescriptionの両方を見る必要があった。検証例に個人情報は使っていない。

## サーバー側の送信・適用状況

ユーザーがZIP送信・安全な適用を承認し、指定のSlackへ送信済み。
通常のSlack添付はHermes側から403だったため、同じSlackスレッドでBase64を5分割し、ZIPを復元してもらった。
12,584バイト・ZIP SHA256・内包マニフェスト8ファイルすべて一致。添付preflightはready、Plugin Doctorは成功。
これは別プロセスでの確認。実ワーカーにはまだ適用していない。

- プロファイル: default / HERMES_HOME: `/var/lib/hermes/.hermes`
- 実チェックアウト: `/var/lib/hermes/app`, HEAD `b2860025adc1478eca63a0b2a82440798eadbfd1`
- サービス: `home-hermes-dashboard.service`
- 証拠: `/var/lib/hermes/.hermes/cache/scratch/mobile-display-0.2.0-87ea9f3f4f60/verification-evidence.json`
- 認証経路の証拠: 同じstageの`auth-route-evidence.json`
- 静的セクション: 3,900文字、ツール0、フック1。実ワーカーでの他セクション込み総量は未確認。

`probe_live.py`は自動審査で停止したが、ユーザーがSlack上で承認し、通常のスクリプト起動で実行できた。
ホスト接続用トークンとDashboard認証は別物であり、前者でidentity/idleを確認すると401になる。
認証切れと決めつけない。drain専用資格情報をidleへ流用しない。認証設定変更や認証迂回は禁止。
Chromeの既存認証もこのDashboardにはなかったため、正規のログイン画面をユーザーに開き、入力を依頼している。
パスワード・cookie・トークンをチャットへ求めない。

実ワーカーのidentity/idleを認証済みの正規経路で確認し、競合を防ぐ正規の受付制御と安全な境界が必要。
瞬間的なidle:trueだけで再起動しない。サービス・会話・他エージェントをまとめて停止しない。
導入/有効化/ワーカー切替/実モデル応答/実機表示は引き続き未確認。Google再認証や追加有料API呼び出しは行っていない。

## 成果物と検証記録

- 検証用APK: `E:/OSHIIRE/software/Hermes/hermes-mobile/output/intelligent-ui-design-20261021/HermesMobile-intelligent-ui.apk`
  - SHA256: `2062c4ad34292cc2f551cc35475e7ddf9df82cfb00606be652e583a388ac79f4`
- サーバープラグイン: 同じディレクトリの`mobile_display-0.2.0.zip`
  - SHA256: `87ea9f3f4f60674536e3a80316f232f28b6060f899d05e9978f208e9da61ed21`
- 機械可読の最新状況: 同じディレクトリの`validation.json`
- APK/テストAPKビルド、Release Kotlin、色ガード、対象JVM58テスト、プラグイン22テスト、変更Kotlinのktlintは成功。
- 最終lintDebug: エラー0、警告246。全リポジトリの既存警告を今回まとめて修正していない。
- ブラウザー: 本番Document builder出力で3例の入力・更新、高さ増減、状態維持、320px/大文字、明暗、sandbox分離を確認。
- Androidの最終6テストは`output/intelligent-ui-native-final-tests.log`を確認。
  入力ノードのtext検索だけでは不十分で、ツリー走査とcontentDescriptionも必要。
  @Beforeで最初から接続し、API34以上では検索前にUiAutomation.clearCache()でテスト用アクセシビリティキャッシュを更新する。
  画面が6,000円へ更新されても古いノード情報が残る現象を確認した。アプリやWebViewのデータを消去する処理ではない。
- スクリーンショット: `output/playwright/android/`の`calculator-light.png`、`calculator-input.png`、
  `calculator-menu.png`、`comparison-dark.png`、`comparison-dark-expanded.png`、`circuit-large-text.png`。
  `diagnose-*`と`*-direct.png`は調査中の空白/比較画像なので完成の証拠には使わない。
- 実機インストールはしていない。APK自体のSHAはAndroidのテストハーネス変更では変わらない。

最終結果: Android 6/6成功（`OK (6 tests)`、18.372秒）。明暗、大きな文字、拡大、数値入力と再計算、
短い図解の高さ、システム文字倍率×チャット倍率、コード→プレビュー復帰を確認。
最終lintDebugも成功、エラー0・警告246。APK/Release Kotlin/JVM58件/プラグイン22件/変更ktlintも成功。
専用AVDは停止済み。実機・本番サーバー・実モデル応答の確認完了とは区別する。
正規ログイン画面はChromeで本人入力待ち。サーバーの次の一手は認証済みの稼働確認と安全な適用境界の確認。

## 追記: Dashboard認証情報の復旧

本人からID・パスワードを忘れた、Chrome/スマホのパスワードマネージャーにも保存がなかったとの回答あり。
現在の認証変更・パスワード再設定の許可はまだない。プラグイン適用の許可とは区別する。
Chromeの内部パスワード管理URLはブラウザー操作ポリシーで拒否されたため、迂回せず本人に確認してもらった。

Slackの読み取り専用調査では、`/var/lib/hermes/.hermes/config.yaml`の`dashboard.basic_auth`にIDとscryptハッシュがあり、
平文パスワードや環境変数による上書きはなかった。固定のsession署名secretは設定済み。実値は出力していない。
サーバー側ブラウザーには対象Dashboardの保存ログイン・セッションがなく、本人端末の有効セッションはサーバーから列挙できない。
Mobileのコードでは、gated REST認証は暗号化したCookieJarを使う。WSチャットだけ使えることをREST認証の成功扱いにしない。
本人端末の接続を維持し、ログアウト・cookie破棄・アプリ初期化をしない。

defaultのHermes Vaultは別の保管先として存在する。対象originの保存項目の有無だけを、標準APIの内部処理で確認するよう
Slackへ追加依頼した。出力は有無・件数のみ。秘密・ID・ハッシュ・cookie・全項目一覧の出力やDB直読みは許可していない。
再設定が必要な場合に備え、標準CLI/保護入力経路、既存ID/session署名secretの保持、適用タイミングと安全な切替、
バックアップ/復旧の具体案までを依頼中。パスワード生成・保存・変更やサービス再起動はまだ実行していない。

Hermesから、標準Vault一覧APIで対象originのみを処理内で絞り込んだ結果、保存ログイン0件との報告あり。
ファイル・鍵・権限は変更していない。再設定案では、保護入力経路が使えなければ、ホスト内で強いランダム値を生成し、
default Vaultへ対象Dashboardのログインとして正規APIで暗号化保存し、既存scrypt実装でpassword_hashだけ更新する案も
読み取り検討するよう依頼した。この方式でのパスワード生成・新規Vault保存・認証変更も本人の明示承認が必要で、未実行。

本人のスマホブラウザーでも対象URLはログイン画面となり、ブラウザーの既存ログイン状態は利用できなかった。
隔離領域の仮設定・仮Vaultのみを使う再設定ヘルパーの準備と検証をSlackへ依頼済み。
本番パスへの書込みを拒否する段階で、実資格情報の生成・保存・認証変更・drain・再起動は許可していない。
既存の正規drainがDashboard全体の受付・入力待ち・委任を含めた安全な切替を保証するかも読み取り監査中。
ランダム生成してVaultに保存するだけでは本人がログインに使える保証にならないため、標準の受け渡し経路を
最終案の受入条件として明示した。MobileのVault画面で表示/コピーできると推測しない。独自の認証迂回は作らない。

隔離ヘルパーはテストと独立レビューの工程まで進んでいるが、最終結果・ハッシュ・drain監査結論は未受領。
本人からIDが`mobile`でよいか確認があり、実設定との一致のみを読み取り照合するようSlackへ依頼済み（結果未受領）。
パスワードの桁数をハッシュから調べることはできない旨を説明し、本人は桁数確認を取り下げた。
これをパスワード変更や作業中断の承認として扱わない。認証変更・本番適用はいずれも未実行。

## 追記: 再設定・サーバー反映の明示承認

2026-10-21、本人から「じゃあパスワード再設定して、サーバー反映まで進めて」と承認された。
上記の「未承認」はこの発言以前の経緯。現在はdefault Dashboardの既存ID・session署名secret・auth modeを保持した
パスワード再設定、安全な資格情報保管、mobile_display 0.2.0の適用・確認が承認範囲となる。
対象Dashboardの必要な短い切替も範囲に含め、進行中の作業・他サービス・データを保護するようSlackへ依頼済み。
承認伝達: https://chatgpt-lll7864.slack.com/archives/C0C74STS7D4/p1792584559760779

現状は未適用。隔離ヘルパーの22テストはPython 3.13/3.14で通ったが、独立レビューでテスト隔離・同一仮Vaultへの
並行実行防止・復旧設定のディスク同期順に不足があり、修正中。テスト成功だけで本番準備完了としない。
drain監査では、`/api/gateway/drain`はGateway向け非同期受付抑止であり、Dashboardの入力待ち・委譲・queued/buildを
一括凍結できないとの結論。正規Dashboardの安全な切替方法と本人が資格情報を使える経路は、引き続き実行前の条件。
これらを確認してから承認範囲内で適用まで続け、認証迂回や他エージェントの一括停止をしないよう伝えている。

本人はサーバー本体の端末を開けると回答した。本人が標準getpassの非エコー入力で新パスワードを2回入力する方式に変更し、
ランダム生成・秘密の受渡し・新規Vault項目追加を実適用の必須条件から外した。既存Vault/鍵は保持する。
Hermesへ、標準hash/config保存APIを用い、既存ID/署名secret/無関係設定を保持する最小ヘルパー、仮データ検証、
安全な対象Dashboard切替手順、本人へ渡すコマンド1本を依頼済み。本人入力後に行き詰まらない手順を先に確定する。
既存IDが`mobile`に一致することは、実設定のboolean照合で確認済み。秘密実値の出力はない。
最新方針伝達: https://chatgpt-lll7864.slack.com/archives/C0C74STS7D4/p1792585114387319

## 追記: 明示メンテナンス停止の代案

正規retirementにもDashboardの認証が必要で、既存の利用可能な認証済みセッションがないため、現状では安全なidle切替を確保できない。
Hermesが実設定・コードで確認した代案は`home-hermes-dashboard.service`だけを明示的に停止する方法。
`KillMode=control-group`、停止猶予45秒、`SendSIGKILL=yes`。Dashboard本体と配下プロセスが対象となる。
他サービスへの停止伝播設定は見つからず、現在の設定ではDesktop所有Gateway終了の分岐も対象外との報告。
Messaging Gateway・他エージェント・Tailscale・接続制限用guardを停止する操作は含めない。

ただし、Dashboard管理下のPTY、実行中ツール、ログイン、入力待ち、委任、グループ処理は中断・失敗する可能性がある。
保存済み会話やDBを削除・初期化しないが、未保存出力と実行状態の保持・自動再開は保証できない。
既に発生した外部副作用は取り消せず、中断後に自動で再実行しない。
この中断を伴う停止についての本人了承は、通常の短い安全な切替の承認とは区別し、まだ取得していない。

予定手順は、対象のみ停止→inactive/dead・MainPID=0・保留service jobなし・空cgroup・待受終了を確認→
default設定の別writerと短時間調整し基準再照合→本人getpass2回→password_hashだけ更新→
照合済みプラグインと該当設定だけ退避・適用→対象のみ起動→正規認証・HTTP稼働・実登録を検証。
停止・基準条件を満たさなければgetpassより前に拒否する。起動前の失敗では今回の変更だけを復旧する。
一度でも新コードの起動を試した後は、新規作業の受付があり得るため、検証失敗だけで自動再停止・巻戻しをしない。

getpass最小ヘルパーの修正担当からの結果回収が停滞していたため、Hermes本体へ回収と検証完了を明示依頼した。
Hermes本体は委任状態を確認し、担当を止めて生成済みファイル・検証JSON・テストを直接確認中。
本番停止・認証変更・プラグイン適用は未実行。完成前に中断の最終了承を求めず、未検証コマンドも渡していない。

## 追記: 実行用ヘルパーの具体的な残課題

長時間の編集・検証反復に対し、現状の具体的報告を要求した。
作成中の本番用ファイルは`/var/lib/hermes/.hermes/cache/scratch/mobile-password-helper-reviewed-20261021/live_password_hash.py`。
報告時のSHA256は`4c32b204ead2b9c5b531cc5e448fa7d8806e512e9da877da4bf5316c18d1ae23`。
未完成・独立レビュー未完了であり、このSHAの版を実行しない。
既存`password_hash_helper.py`は仮設定専用版で、本番版とは別。Vault取引やサービス停止/起動を実行用ヘルパーへ含めていない。

Python 3.14.7の隔離仮データ検証は63件中62成功・1失敗・エラー0。旧隔離テストも含む合計であり、本番用単体の件数ではない。
失敗は`test_private_tty_accepts_private_sudo_origin_without_reopening_owner_path`。
端末所有者と`sudo -u hermes`後の実行uidが異なる正当な本人起動を独自TTY検査が拒否し、端末パスの開き直しにも権限問題がある。
本番基準`live-baseline.json`は未作成、現版レビュー未完了、別管理者の起動や設定writerの調整も未完了だった。
当時の読み取りでは対象はactive/running、PID 2029092。これを次の実行時点の状態とみなさず、基準を取り直す。

CPython v3.14.7の公開getpass実装を確認し、標準getpass・継承stdin fdでの非エコー入力を再利用するよう具体的に修正依頼した。
stdinのTTY確認、GetPassWarningを例外扱いとして平文入力フォールバック前に拒否、echo_char=Noneの2回入力を検討する。
端末ownerと実行uidの一致を独自の承認条件にしない。管理者本人の端末という利用条件・秘密非表示・承認境界は維持する。
根拠: https://raw.githubusercontent.com/python/cpython/v3.14.7/Lib/getpass.py
最新依頼: https://chatgpt-lll7864.slack.com/archives/C0C74STS7D4/p1792587591651849
元の修正担当は承認待ちではなく、回収時の停止によりモデル応答待ちから中断したとの報告。完了扱いにしない。

## 追記: 本人入力手順の検証完了・中断了承待ち

標準getpassへ修正後、Python 3.14.7の隔離回帰63件成功・失敗/エラー0、実PTY追加2件成功。
追加2件では/dev/ttyを開けない場合の継承stdinによる非エコー2回入力と、仮パスワード非出力、echo不能時の入力前拒否を確認。
実際のsudo UID切替自体は未検証。限定レビューはpassed=true、セキュリティ/ロジック指摘なし。
レビュー対象・現在の実行ヘルパー・live-baselineの照合一致、本番設定とnative実装も基準一致との報告。
現ヘルパーSHA256: `6bf01a0ae9e7a9011755fa50605d26fcad2604c554762b687c8ff02daf75f2af`。
同じstageの`limited-review.json`、`PERSON-HANDOFF.md`に記録されている。

本人用コマンド（まだ実行しない）:

```sh
sudo -u hermes -- /var/lib/hermes/.hermes/cache/scratch/mobile-password-helper-reviewed-20261021/run-person-password.sh
```

既存sudo権限を使い、権限変更は行わない。対象Dashboard停止・別writer調整・直前のファイル/基準再照合後にのみ本人へ実行依頼する。
本人が新パスワードを非エコーで2回入力。成功出力は`{"activated": false, "status": "staged"}`。
このコマンドはhash更新だけをstageし、サービス停止・起動・プラグイン適用は行わない。
入力後、Hermesが同じメンテナンス境界でプラグイン適用・起動・正規認証/実登録の検証まで続ける必要がある。

準備が揃ったため、元の「進行中ターンを止めずに反映」からの条件変更として、Dashboard配下の中断について本人へ最終了承を依頼した。
質問は中断/終了の可能性、未保存出力・自動再開の非保証、保存済み会話/DB保持を明記。回答待ち。
再設定・反映自体の承認は取得済み。中断の了承前に停止せず、承認後もscratch存在・SHA・基準を取り直す。
本番停止・hash変更・プラグイン適用は依然未実行。実sudo・本番適用・正規ログイン・実ワーカー登録の成功とは区別する。

## 追記: Dashboard配下の中断も承認済み

本人は最終質問へ「中断を了承して進める」と回答した。Dashboard配下の実行中ツール・ログイン・PTY・入力待ち・委任が
中断/終了し、未保存出力と自動再開を保証できない点まで承認済みとなった。同じ停止の許可を取り直さない。
Slackへ直前再照合・別writer調整・対象のみ停止・閉じた境界の確認を実行するよう依頼した。
依頼: https://chatgpt-lll7864.slack.com/archives/C0C74STS7D4/p1792591889171349
停止確認の結果を受けてから本人へgetpassコマンドを渡し、成功通知後に同じ境界でプラグイン適用・起動・検証まで続ける。
この追記時点では停止結果は未受領、パスワード変更・プラグイン反映も未実行。

停止前のscratch/SHA/レビュー/基準/ZIP/service照合は一致した。Hermesが通常経路の
`systemctl --no-ask-password stop home-hermes-dashboard.service`を実行したが、OSの
`Interactive authentication required.`で終了コード1となった。自動審査は許可しており、自動審査拒否とは区別する。
拒否後はactive/running、PID 2029092、service jobなし。sudo/polkit変更や別経路の自動再試行はしていない。
同じdefaultへの設定書込み・対象起動を本作業に一本化する調整を宣言。観測範囲で設定writerはなかったが、
同一UIDの1プロセスはメタデータ参照不可であり、全writer不在を証明したとは扱わない。
本人の既存sudo権限による正規停止が必要。本人の停止許可は取得済みで再承認を求めない。
停止と検証済みhelperの実行を`&&`でつなぐ本人コマンドを安全に渡せるか、現版の全事前ガードをHermesへ照合依頼中。
追加開発は依頼していない。まだ本人へパスワード入力コマンドの実行依頼は出していない。

Hermesが現版を直接確認し、次の本人用1行を実行可能と回答した（ファイル編集なし、SHA/レビュー/基準再照合も一致）。

```sh
sudo systemctl stop home-hermes-dashboard.service && sudo -u hermes -- /var/lib/hermes/.hermes/cache/scratch/mobile-password-helper-reviewed-20261021/run-person-password.sh
```

停止失敗時は`&&`でhelperを実行しない。helperはgetpassより前にinactive/dead、MainPID=0、jobなし、空cgroup、
9119待受終了、default/HEAD/helper/native/ZIP/設定の基準一致を検査し、拒否/読取不能時には入力前に拒否する。
本人へこの手順を渡す段階。sudoが求める既存管理者パスワードと、新しいDashboardパスワード2回は端末のみへ入力する。
成功出力staged以外の秘密・復旧ファイルはチャットへ求めない。停止成功後にhelperが拒否した場合も対象は停止したままなので、
その状態を受領して確認し、機械的に再起動しない。成功後にHermesが限定プラグイン適用まで進めてから対象を起動する。
起動もOS管理者認証が必要な場合は、本人へ正規の起動コマンドを渡す。既存権限設定の変更・迂回はしない。
まだ本人実行結果は未受領、停止・パスワード変更・プラグイン反映の成功は未確認。

## 追記: 本人からstaged成功通知を受領

本人が`{"activated": false, "status": "staged"}`を返した。停止&&検証済みgetpassコマンドの成功通知として受領。
新パスワード・ハッシュ・復旧ファイルは受け取っていない。新しい認証情報はdisk上のstageであり、起動・正規ログイン成功ではない。
Hermesへ、停止境界と正常完了記録を再確認し、今回hashのみ更新された差分を識別して既存ID/署名secret/auth mode/Vault/他設定を
保持したことを値非表示で確認し、そのまま限定バックアップ・mobile_display 0.2.0導入/有効化まで実行するよう依頼した。
依頼: https://chatgpt-lll7864.slack.com/archives/C0C74STS7D4/p1792592962001719
適用/起動の許可は取得済み。起動もOS管理者認証が必要なら、本人へ正規起動コマンドを渡す。
この追記時点ではサーバー側のstage照合・プラグイン適用結果は未受領。稼働反映や実ワーカー登録の完了扱いにしない。

Hermesからstageと停止境界の照合結果を受領した。inactive/dead、PID 0、jobなし、空cgroup、9119待受終了。
現在のconfigの新hash部分を旧hashへ戻したバイト列が旧baselineと一致し、今回のconfig差分がpassword_hashだけと
実値を出さずに確認された。ID/署名secret等を丸ごと初期化したものではない。
同じ停止境界で標準plugin install/enable/admissionの実装を確認し、限定退避・適用を実行中。配置/有効化完了報告はまだない。

## 追記: プラグイン配置・有効化完了、本人起動待ち

Hermesから配置/有効化完了と起動前再照合成功を受領した。
適用先はdefaultの`/var/lib/hermes/.hermes/plugins/mobile_display`、版0.2.0。
承認済みZIP SHA256と全8ファイルが一致、標準native有効化処理を使用しallow_tool_override=false。
退避先は`/var/lib/hermes/.hermes/backups/mobile_display-20261021T143644Z`（0700）。
旧同名ディレクトリは存在せず、その不在と該当設定の退避記録を保存した。
新password_hash、既存ID/署名secret、無関係設定を保持し、Vault/鍵のmetadataも適用前後で不変との報告。
preflight ready、隔離Plugin Doctor成功（tools 0、pre_llm_call 1）。
対象はinactive/dead、PID 0、jobなし、閉鎖条件維持、guard稼働中。稼働登録確認とは区別する。

新パスワードを保持したまま起動可能な状態との確認を受け、本人へ既存sudoで次を実行するよう案内する段階。

```sh
sudo systemctl start home-hermes-dashboard.service
```

起動後はID mobileと本人が設定した新パスワードで正規ログインを確認してもらう。秘密値はチャットへ求めない。
HermesへHTTP bootstrap/保護endpoint、service/NRestarts、実ワーカーsection/hook登録の検証を依頼済み。
本人の起動/ログイン通知はまだなく、本番稼働・正規認証・実ワーカー登録・実モデル応答は未確認。

## 追記: 起動・認証成功とHeadless表示の訂正

本人が起動後にrootを開くと`Headless backend (hermes serve): web UI disabled`と表示された。
rootをログイン確認先として案内したのは不適切だった。現行serveではrootのブラウザーUIを無効化しており、これだけでは認証失敗ではない。
Hermesが実コードと実GETで`/login`は200・password formあり、`/api/auth/me`は未認証401と確認した。
認証後の`/api/auth/me`は本人情報/provider/有効期限だけを返し、秘密や設定・会話を返さない。
nextは`/api/*`遷移を拒否するため、/login後にrootのHeadless表示になっても同じbrowserで/api/auth/meを直接開く。
mode変更・再起動・追加resetは不要。パスワードをチャットへ求めない。

本人は一度「ログインできない」を選んだ後、実際の/api/auth/meのアカウントJSONを返した。
user_id=mobile、provider=basic、有効期限付きの正常な認証済み応答として受領し、直前の選択よりこの応答を優先した。
cookie・passwordは受け取っていない。新設定後の本人の正規ログイン確認は成功として記録する。

Hermesの最新service観測はactive/running、PID 2058807、NRestarts 0、保留jobなし。
/api/healthと/api/auth/providersは200（Basic登録あり）。未認証/api/auth/me、/api/host/identity、/api/health/idleは401。
現在のHTTP稼働と認証保護を確認済み。実workerのsection/hook登録、新規/継続Mobile補足と総量制限は引き続き確認中。
最新依頼: https://chatgpt-lll7864.slack.com/archives/C0C74STS7D4/p1792594505047079

## 追記: 実worker確認の観測限界と実機更新用版46

2026-10-22、正常な/api/auth/me応答を本人から受領済み。認証再設定・サービス起動は完了。
mobile_display 0.2.0の配置・標準有効化・service稼働を「適用済み」と記録する。
validation.jsonのproduction_deployed=trueはこの配置・起動を意味し、受入検証完了を意味しない。
production_acceptance_complete=false、live_worker_registration_verified=false、live_agent_response_verified=false。

実worker stdout/stderrは/dev/null、journal参照は権限拒否。既存HTTP plugin hubはディスクmanifestと設定からの
一覧であり、runtime_status=enabledも実登録ledgerの証明にならない。plugins.list RPCは実managerを参照するが
section/hook詳細やプロンプト組込み・総量を返さない。Mobileにはraw RPC UIがなく、/pluginsもCLI専用として
ブロックされるため、利用者へRPC JSONを普通のチャットに送信させない。cookie抽出やservice注入は行わない。
既存の正規診断接続が対象9119実プロセスへ到達する場合だけplugins.listの確認を依頼した。
別gatewayへの接続を対象Dashboardの証拠にしない。新helper開発・mode変更・再起動は依頼していない。
依頼: https://chatgpt-lll7864.slack.com/archives/C0C74STS7D4/p1792595080638559
補足: https://chatgpt-lll7864.slack.com/archives/C0C74STS7D4/p1792595315483179

接続中SC-51F（RFCY201PM5F）はversionCode45、versionName1.0.45-local。
初回インストール時刻2026-09-06 23:04:00、最終更新2026-10-21 04:05:13。
元の検証APKはversionCode1だったため、そのまま上書き更新させない。downgradeやuninstallはしない。
コード変更なし、Gradleの既存version overrideで版46（1.0.46-local）をビルド。
assembleDebugと対象JVM58件が2026-10-22 00:09に成功。署名のSHA256は実機版45と一致。

更新用APK: output/intelligent-ui-design-20261021/HermesMobile-intelligent-ui-v46.apk
SHA256: 64fbe585b1d410a9c5ba01be437ecbd16067c584277a4a38dae53e54424b3bae
元の版1APKとハッシュは過去のemulator証跡用として保持し、更新候補とは区別する。

実機更新はまだ未承認・未実行。版46をデータ保持の上書き更新で導入し、新規通常チャットで無害な画像・
操作UIの表示を確認してよいか本人へ質問済み。回答待ち。AGENTS.mdのdevice deployment承認要件による。
許可後は直前の版・署名・データ保持基準を再確認し、adb install -r --no-incrementalだけを使う。
既存の認証・接続・履歴・DBを保持し、初回インストール時刻と安定した設定/DB存在を前後で照合する。
実機へのインストール成功・画面表示・実worker登録・新規/継続hook実行・8000字総量実測は別々に報告する。

### 最終静的確認（2026-10-22）

Hermesから最終報告を受領。既存CLIの接続先は対象9119 Dashboardと不一致のため探索終了。
別gatewayにはplugins.listを送っていない。対象へ接続可能な既存の正規診断経路は確認できなかった。
実ソースの通常mobile_displayロードはregister成功・帰属処理後にenabled=Trueとなり、例外/timeout時は所有登録を
dispose/ledgerから除去・購読解除するrollbackあり。ただし汎用enabledにはlazy-load例外があり、全般の登録証明ではない。
他のenabledユーザープラグインに静的section定義は検出されず、確認できた定義はhermes-mobile-display 3900字のみ。
既知の静的競合なし（3900/8000）。これは実workerの登録総量や動的条件の実測ではない。
実worker ledger・新規/継続Mobileターン補足実行・組立時総量・実画像/UI応答は未確認を維持する。
本人にraw RPC送信は求めない。新しい診断開発・再起動・追加モデル呼出しは行っていない。

## 追記: 本人の依頼によりAPKをGoogle Driveへ保存

本人は実機更新への承認ではなく、Google Driveへの保存を依頼した。端末更新・実チャット送信は実行していない。
Google Driveスキル/コネクタで版46 APKをMy Driveのrootへ新規アップロードし、完了後のmetadataで名前・APK MIME・
46238334 bytesを確認。ローカルのサイズと一致。権限・公開共有は変更していない。
アップロード直前のローカルSHA256は64fbe585b1d410a9c5ba01be437ecbd16067c584277a4a38dae53e54424b3bae。
Driveのreadbackはchecksumを返さないため、リモートSHA256一致確認とまでは扱わない。
保存先: https://drive.google.com/file/d/12eFOcaPd1yKXO05bR4Ce7clHxpd6Y52P/view?usp=drivesdk
file ID: 12eFOcaPd1yKXO05bR4Ce7clHxpd6Y52P

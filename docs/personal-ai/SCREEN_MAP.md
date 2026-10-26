# Phase 1 画面と確認範囲

更新: 2026-09-06。ローカル実装の画面表。実Hermesへの接続結果ではない。

## 通常の入口

| 入口 | route / 実装 | 操作できる範囲 |
|---|---|---|
| Today | `shell/today` / ObservedActivityScreen | 観測済みの判断待ちを優先し、Chat・要求詳細へ移動。観測期間・接続状態・不確実性を表示 |
| Chat | `hermes/chat` / 既存ChatScreen | 既存会話・streaming・添付・下書き。接続先が確定しないwriteは保留せず拒否。結果不明は自動再送しない |
| Activity | `shell/activity` / ObservedActivityScreen | 現プロセスで受信したイベントと最後に確認した状態。既知のsaved session IDから会話へ移動 |
| Apps | `shell/apps` / MiniAppsScreen | 同梱3例のmanifest情報・予定権限の確認。すべて準備中・未許可。インストール・有効化・業務機能はない |
| 共通メニュー | ShellMenu | 設定、接続、プロファイル、Advanced。認証後のみ |
| Advanced | `shell/advanced` / AdvancedScreenContent | 下表の既存27画面を元のsection・順序で表示 |
| Attention詳細 | `shell/attention` / AttentionDetailScreen | 固定scope/session/request/questionの説明と状態。once/denyまたは質問回答。未知の対象・影響・権限・期限を明示 |

4タブは別々の履歴と保存状態を持つ。タブ切替だけではsession/socketを作成しない。
詳細からBackは選択タブの前画面へ戻り、Today以外のrootからBackはTodayへ戻る。
Todayでは既存の二度押し終了を維持する。ログアウト後の再ログインはTodayから開始する。
認証中の通知待機先は保存するが、未確認の要求内容や別接続の要求を復元して応答可能にしない。

## 既存27画面

すべて `ScreenRegistry.ALL_SCREENS` の既存contentを利用する。
routeは内部登録用の安定IDであり、任意外部deep linkを有効にする宣言ではない。
管理画面の表示・保存・実行が実サーバーで動作するかは、そのサーバーの既存契約に依存する。

| NavKey | route | 入口 |
|---|---|---|
| ChatScreen | `hermes/chat` | Chatタブ、Today、Advanced、既知session通知 |
| HistoryScreen | `hermes/history` | Advanced、Activity |
| ProfilesScreen | `hermes/profiles` | Advanced、共通メニュー |
| BotsScreen | `hermes/bots` | Advanced |
| CronJobsScreen | `hermes/cron-jobs` | Advanced |
| WebhooksScreen | `hermes/webhooks` | Advanced |
| KanbanScreen | `hermes/kanban` | Advanced |
| SkillsScreen | `hermes/skills` | Advanced |
| ToolsetsScreen | `hermes/toolsets` | Advanced |
| PluginsScreen | `hermes/plugins` | Advanced |
| ConfigScreen | `hermes/config` | Advanced |
| McpServersScreen | `hermes/mcp-servers` | Advanced |
| MemoryScreen | `hermes/memory` | Advanced |
| ModelScreen | `hermes/model` | Advanced |
| PairingScreen | `hermes/pairing` | Advanced |
| KeysScreen | `hermes/keys` | Advanced |
| ChannelsScreen | `hermes/channels` | Advanced |
| ProvidersScreen | `hermes/providers` | Advanced |
| GatewayScreen | `hermes/gateway` | Advanced |
| SystemScreen | `hermes/system` | Advanced |
| LogsScreen | `hermes/logs` | Advanced |
| ProcessesScreen | `hermes/processes` | Advanced |
| AnalyticsScreen | `hermes/analytics` | Advanced |
| BillingScreen | `hermes/billing` | Advanced |
| FilesScreen | `hermes/files` | Advanced |
| AchievementsScreen | `hermes/achievements` | Advanced |
| SettingsScreen | `hermes/settings` | Advanced、共通メニュー |

## 認証・subpage・引数付き詳細

| NavKey | route | 入口 / 引数 |
|---|---|---|
| LandingScreen | `hermes/landing` | 未認証起動、logout |
| AuthLoginScreen | `hermes/auth-login` | Landing |
| SettingsConnection | `hermes/settings-connection` | 設定、共通メニュー |
| SettingsAppearance | `hermes/settings-appearance` | 設定 |
| SettingsLanguage | `hermes/settings-language` | 設定 |
| SettingsChat | `hermes/settings-chat` | 設定 |
| SettingsBehavior | `hermes/settings-behavior` | 設定 |
| SettingsAbout | `hermes/settings-about` | 設定 |
| ToolsetDetailKey | `hermes/toolset-detail` | Toolsets / name, label |
| MemoryProviderDetailKey | `hermes/memory-provider-detail` | Plugins / name, label |
| GroupChatKey | `hermes/group-chat` | Bots / groupName |
| AttentionDetailKey | `shell/attention` | Today・Activity・Chat・通知 / RequestRef |

既存38件とShell追加5件、合計43件を重複検査する。登録・NavDisplay entry・元画面のcontent参照を照合する。
保存状態試験はStateRestorationTesterによる再生成を含む。
別途、専用エミュレーターの実APKで未認証AuthLogin画面を背景化してプロセスを終了し、
PIDが消失・変更したことと同画面への復元を確認した。認証済み会話・未解決要求を伴う実OS再生成は未確認。

## 検証証拠の読み方

- 全体・最終結果とAPKの対応は [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) のPR11を参照。
- `AppBackStackTest` は引数付き詳細・選択タブ・非表示タブ・認証分離、`ShellChromeTest` はタッチとメニューを検査する。
- `PhaseOneEvidenceTest` は実MainNavigationとproduction composableを使う。ChatのViewModel・認証flowおよび観測入力は合成fixture。画像はエミュレーターの実描画を取得する。
- `AttentionDetailTest` / `ObservedActivityTest` / `MiniAppsScreenTest` は切断・欠落・不明・無効操作・文字拡大・日本語の表示を検査する。
- 通知のオブジェクト・固定参照・保存復元は専用テストで検査する。実サーバーからの通知配送や実機のバックグラウンド制限は未確認。
- TalkBackの操作対象には文字ラベル・選択状態・無効状態を提供する。専用エミュレーターでTalkBackを一時有効にし、実APKのAuthLogin見出しからServer URL入力へのフォーカスとIME表示を確認した。終了後に元の無効状態へ戻した。読み上げ実音とAttention全項目の連続フォーカス順は未確認。
- 既存管理画面の全操作やサーバーへのwriteを、一覧項目の到達テストだけで検証済みとは扱わない。

## 取得した画面

ローカル証跡: `E:/OSHIIRE/software/Hermes/phase1-validation/screenshots/`。
AI生成画像ではなく、専用API 36エミュレーター（1280×2856）の実描画を取得した。
01〜11と19はproduction composableに合成入力を与えたテスト。15〜17はインストール済みAPKの手動操作。
サーバーの正常応答を示す画像ではない。

| ファイル | 内容 |
|---|---|
| `01-today-ja-offline.png` | 日本語Today、オフラインと最後の観測状態 |
| `02-activity-ja-uncertain.png` | 日本語Activity、不確実な実行状態 |
| `03-apps-ja-example.png` | Appsの準備中表示、就活サンプルの未許可権限 |
| `04-approval-ja-font200.png` | 日本語200％文字の承認詳細とonce/deny |
| `05-approval-ja-awaiting-ack.png` | ACK待ちで操作無効の同詳細 |
| `06-advanced-ja.png` | 既存27項目のAdvanced一覧 |
| `07-chat-ja-streaming-input.png` | 合成streaming本文、日本語入力、実IME |
| `08-main-shell-ja-chat.png` | 実MainNavigationでタブ復帰後に下書きを保持 |
| `09-main-shell-ja-menu.png` | 実MainNavigationの共通メニュー |
| `10-main-shell-ja-logged-out.png` | 認証flowの変化で内部shellを破棄 |
| `11-main-shell-ja-ime.png` | 実MainNavigationのIME表示中、下部バーを非表示 |
| `15-talkback-next.png` / `16-talkback-url-focus.png` | 実APK認証画面のTalkBackフォーカス |
| `17-apk-landing-after-update.png` | データ保持更新後の実APK起動 |
| `19-main-shell-ja-advanced.png` | 実MainNavigationのメニューからAdvancedへ移動 |

12〜14は読み上げサービス開始中・権限ダイアログ等の補助記録。
テストContextのActivity参照・ViewModelのApplication情報の初期不備は修正して再実行した。
画像だけでなく、対応するassertionと最終JUnit XMLを保存している。
